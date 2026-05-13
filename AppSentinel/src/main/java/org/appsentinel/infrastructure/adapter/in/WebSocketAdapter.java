package org.appsentinel.infrastructure.adapter.in;

import org.appsentinel.domain.port.in.BrowserEventPort;
import org.appsentinel.domain.port.out.BrowserCommandPort;
import org.java_websocket.WebSocket;
import org.java_websocket.handshake.ClientHandshake;
import org.java_websocket.server.WebSocketServer;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.net.InetSocketAddress;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * WebSocketAdapter multi-navegador con protección de flujo redundante.
 * Acepta extensiones de Chrome, Firefox, Edge, Brave, Opera y Safari.
 */
public class WebSocketAdapter extends WebSocketServer implements BrowserCommandPort {

    private static final Logger LOGGER = Logger.getLogger(WebSocketAdapter.class.getName());
    private final BrowserEventPort browserEventPort;
    private final ObjectMapper mapper = new ObjectMapper();

    private static final Set<String> ORIGINS_PERMITIDOS = Set.of(
        "chrome-extension://",
        "moz-extension://",
        "safari-extension://",
        "safari-web-extension://",
        "edge-extension://" 
    );

    private static final int MAX_MENSAJES_POR_SEGUNDO = 10;
    private static final int ORIGIN_MAX_LEN = 256;
    private static final int URL_MAX_LEN = 2048;
    private static final int TITULO_MAX_LEN = 500;

    private final Map<WebSocket, AtomicInteger> mensajesPorSegundo = new ConcurrentHashMap<>();
    
    // CORRECCIÓN: Caché local para evitar inundar el dominio con la misma URL consecutiva (Debounce)
    private final Map<WebSocket, String> ultimaUrlPorCliente = new ConcurrentHashMap<>();

    private final ScheduledExecutorService rateLimitReset = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "WS-RateLimit-Reset");
        t.setDaemon(true);
        return t;
    });

    public WebSocketAdapter(BrowserEventPort browserEventPort) {
        super(new InetSocketAddress(8080));
        this.browserEventPort = browserEventPort;
        rateLimitReset.scheduleAtFixedRate(() -> mensajesPorSegundo.clear(), 1, 1, TimeUnit.SECONDS);
    }

    @Override
    public void cerrarPestaña(int tabId) {
        String json = String.format("{\"accion\":\"cerrar_pestaña\",\"tabId\":%d}", tabId);
        broadcast(json);
        LOGGER.log(Level.INFO, "[WS] Comando cerrar pestaña broadcast: tabId={0}", tabId);
    }

    @Override
    public void onOpen(WebSocket conn, ClientHandshake handshake) {
        String origin = handshake.getFieldValue("Origin");

        if (origin != null && origin.length() > ORIGIN_MAX_LEN) {
            LOGGER.log(Level.WARNING, "[WS] Origin excede longitud máxima");
            conn.close(1008, "Origin inválido");
            return;
        }

        if (origin == null || !esExtensionNavegador(origin)) {
            LOGGER.log(Level.WARNING, "[WS] Conexión rechazada — origin no autorizado: {0}", origin);
            conn.close(1008, "Origin no autorizado");
            return;
        }

        LOGGER.log(Level.INFO, "[WS] Extensión conectada desde {0} | Origin: {1}", 
            new Object[]{conn.getRemoteSocketAddress(), origin});
    }

    private boolean esExtensionNavegador(String origin) {
        return ORIGINS_PERMITIDOS.stream().anyMatch(origin::startsWith);
    }

    @Override
    public void onMessage(WebSocket conn, String message) {
        AtomicInteger contador = mensajesPorSegundo.computeIfAbsent(conn, k -> new AtomicInteger(0));
        if (contador.incrementAndGet() > MAX_MENSAJES_POR_SEGUNDO) {
            LOGGER.log(Level.WARNING, "[WS] Rate limit excedido desde {0}", conn.getRemoteSocketAddress());
            conn.close(1008, "Rate limit excedido");
            return;
        }

        if (message.length() > 4096) {
            LOGGER.log(Level.WARNING, "[WS] Mensaje excede tamaño máximo");
            return;
        }

        try {
            JsonNode json = mapper.readTree(message);
            String url = json.has("url") ? json.get("url").asText() : null;
            String titulo = json.has("titulo") ? json.get("titulo").asText() : null;

            if (url == null || url.isBlank()) {
                return;
            }

            if (url.length() > URL_MAX_LEN) {
                LOGGER.log(Level.WARNING, "[WS] URL excede longitud máxima");
                return;
            }
            if (!url.matches("https?://[\\w\\-\\.]+.*")) {
                LOGGER.log(Level.WARNING, "[WS] URL con formato inválido descarta: {0}", url);
                return;
            }

            // Si la URL es idéntica a la procesada hace un instante, 
            // la descarta en la capa de transporte para proteger al dominio de llamadas ruidosas.
            String ultimaUrl = ultimaUrlPorCliente.get(conn);
            if (url.equals(ultimaUrl)) {
                return; 
            }
            
            // Actualizamos la caché con la nueva ubicación del navegador
            ultimaUrlPorCliente.put(conn, url);

            if (titulo != null) {
                titulo = titulo.replaceAll("[\\p{Cntrl}]", "").trim();
                if (titulo.length() > TITULO_MAX_LEN) {
                    titulo = titulo.substring(0, TITULO_MAX_LEN);
                }
            }

            // Comunicación limpia hacia el Puerto del Dominio
            browserEventPort.reportarEventoNavegador(url, titulo);

        } catch (Exception e) {
            LOGGER.log(Level.WARNING, "[WS] Error procesando mensaje JSON: {0}", e.getMessage());
        }
    }

    @Override
    public void onClose(WebSocket conn, int code, String reason, boolean remote) {
        mensajesPorSegundo.remove(conn);
        ultimaUrlPorCliente.remove(conn); // Limpieza de recursos asignados al cliente
        LOGGER.log(Level.INFO, "[WS] Cliente desconectado. Código={0}", code);
    }

    @Override
    public void onError(WebSocket conn, Exception e) {
        LOGGER.log(Level.SEVERE, "[WS] Error interno en canal", e);
        if (conn != null) {
            mensajesPorSegundo.remove(conn);
            ultimaUrlPorCliente.remove(conn);
        }
    }

    @Override
    public void onStart() {
        LOGGER.log(Level.INFO, "[WS] Servidor iniciado en puerto {0}", getPort());
    }

    public void iniciar() {
        this.start();
    }

    public void detener() {
        try {
            rateLimitReset.shutdownNow();
            this.stop();
            LOGGER.log(Level.INFO, "[WS] Servidor detenido.");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
