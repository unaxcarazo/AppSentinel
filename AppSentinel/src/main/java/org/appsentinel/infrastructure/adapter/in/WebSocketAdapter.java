package org.appsentinel.infrastructure.adapter.in;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.java_websocket.WebSocket;
import org.java_websocket.handshake.ClientHandshake;
import org.java_websocket.server.WebSocketServer;
import org.appsentinel.domain.port.in.BrowserEventPort;
import org.appsentinel.domain.port.out.BrowserCommandPort;

import java.net.InetSocketAddress;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * WebSocketAdapter: Adaptador de entrada para eventos del navegador via WebSocket.
 *
 * RESPONSABILIDAD: Recibir eventos de navegacion desde la extension Chrome,
 * validar seguridad (origin, rate limit, formato), RECORTAR URL a dominio,
 * y delegar al puerto de dominio BrowserEventPort.
 *
 * CAMBIO DEDUPLICACION: La clave ya no es solo el dominio, sino (dominio, tabId).
 * Esto permite detectar cuando el usuario abre el mismo dominio en una
 * pestaña diferente, para que TimeTrackingService pueda actualizar el tabId
 * y el cierre de pestañas afecte la pestaña correcta.
 *
 * Ejemplo:
 *   youtube.com tabId=123 → NUEVO evento (crear sesion)
 *   youtube.com tabId=123 → DEDUPLICADO (misma pestaña, ignorar)
 *   youtube.com tabId=456 → NUEVO evento (actualizar tabId en sesion existente)
 *   github.com  tabId=456 → NUEVO evento (nueva sesion, dominio diferente)
 */
public class WebSocketAdapter extends WebSocketServer implements BrowserCommandPort {

    private static final Logger LOGGER = Logger.getLogger(WebSocketAdapter.class.getName());
    private final BrowserEventPort browserEventPort;
    private final ObjectMapper mapper = new ObjectMapper();

    private static final Set<String> ORIGINS_PERMITIDOS = Set.of(
        "chrome-extension://", "moz-extension://", "safari-extension://",
        "safari-web-extension://", "edge-extension://"
    );

    private static final int MAX_MENSAJES_POR_SEGUNDO = 10;
    private static final int ORIGIN_MAX_LEN = 256;
    private static final int URL_MAX_LEN = 2048;
    private static final int TITULO_MAX_LEN = 500;

    private final Map<WebSocket, AtomicInteger> mensajesPorSegundo = new ConcurrentHashMap<>();

    /**
     * CAMBIO: Trackea ultimo evento por (dominio, tabId) en lugar de solo dominio.
     * Permite detectar cambio de pestaña dentro del mismo dominio.
     */
    private final Map<WebSocket, UltimoEvento> ultimoEventoPorCliente = new ConcurrentHashMap<>();

    private final ScheduledExecutorService rateLimitReset = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "WS-RateLimit-Reset");
        t.setDaemon(true);
        return t;
    });

    public WebSocketAdapter(BrowserEventPort browserEventPort) {
        super(new InetSocketAddress("127.0.0.1", 8080));
        this.browserEventPort = browserEventPort;
        rateLimitReset.scheduleAtFixedRate(() -> mensajesPorSegundo.clear(), 1, 1, TimeUnit.SECONDS);
        this.setConnectionLostTimeout(10);
    }

    /**
     * Record interno para trackear el ultimo evento de una conexion WebSocket.
     * Usado para deduplicacion: si (dominio, tabId) es igual al ultimo, se ignora.
     */
    private static final class UltimoEvento {
        final String dominio;
        final int tabId;

        UltimoEvento(String dominio, int tabId) {
            this.dominio = dominio;
            this.tabId = tabId;
        }
    }

    @Override
    public void cerrarPestana(int tabId) {
        String json = String.format("{\"accion\":\"cerrar_pestana\",\"tabId\":%d}", tabId);
        broadcast(json);
        LOGGER.log(Level.INFO, "[WS] Comando cerrar pestana broadcast: tabId={0}", tabId);
    }

    @Override
    public void onOpen(WebSocket conn, ClientHandshake handshake) {
        String origin = handshake.getFieldValue("Origin");
        if (origin != null && origin.length() > ORIGIN_MAX_LEN) {
            conn.close(1008, "Origin inválido");
            return;
        }
        if (origin == null || !esExtensionNavegador(origin)) {
            LOGGER.log(Level.WARNING, "[WS] Conexión rechazada — origin no autorizado: {0}", origin);
            conn.close(1008, "Origin no autorizado");
            return;
        }
        LOGGER.log(Level.INFO, "[WS] Extensión conectada desde {0}", conn.getRemoteSocketAddress());
    }

    private boolean esExtensionNavegador(String origin) {
        return ORIGINS_PERMITIDOS.stream().anyMatch(origin::startsWith);
    }

    @Override
    public void onMessage(WebSocket conn, String message) {
        AtomicInteger contador = mensajesPorSegundo.computeIfAbsent(conn, k -> new AtomicInteger(0));
        if (contador.incrementAndGet() > MAX_MENSAJES_POR_SEGUNDO) {
            conn.close(1008, "Rate limit excedido");
            return;
        }
        if (message.length() > 4096) return;

        try {
            JsonNode json = mapper.readTree(message);
            String url = json.has("url") ? json.get("url").asText() : null;
            String titulo = json.has("titulo") ? json.get("titulo").asText() : null;

            // Normalizar tabId que puede venir como string con separadores de miles
            int tabId = extraerTabId(json);

            if (url == null || url.isBlank() || url.length() > URL_MAX_LEN) return;
            if (!url.matches("https?://[\\\\w\\\\-\\\\.]+.*")) return;

            // Recortar URL a dominio antes de enviar al dominio
            String urlRecortada = recortarUrlIdempotente(url);

            // ========== CAMBIO DEDUPLICACION: clave = (dominio, tabId) ==========
            // ANTES: if (urlRecortada.equals(ultimaUrl)) return;
            // DESPUES: deduplicar solo si Mismo dominio Y misma pestaña
            UltimoEvento ultimo = ultimoEventoPorCliente.get(conn);
            if (ultimo != null && ultimo.dominio.equals(urlRecortada) && ultimo.tabId == tabId) {
                // Mismo dominio, misma pestaña → deduplicar (sin cambio relevante)
                return;
            }
            // Guardar nuevo evento (dominio, tabId) para proxima deduplicacion
            ultimoEventoPorCliente.put(conn, new UltimoEvento(urlRecortada, tabId));
            // ===================================================================

            if (titulo != null) {
                titulo = titulo.replaceAll("[\\p{Cntrl}]", "").trim();
                if (titulo.length() > TITULO_MAX_LEN) titulo = titulo.substring(0, TITULO_MAX_LEN);
            }

            // Delegar al dominio con URL RECORTADA (dominio) y tabId actual
            browserEventPort.reportarEventoNavegador(urlRecortada, titulo, tabId);

        } catch (Exception e) {
            LOGGER.log(Level.WARNING, "[WS] Error procesando mensaje JSON: {0}", e.getMessage());
        }
    }

    /**
     * Recorta una URL completa a su dominio principal.
     *
     * IDEMPOTENTE: Si la entrada ya es un dominio simple, se devuelve sin modificaciones.
     *
     * Ejemplos:
     *   "https://www.youtube.com/watch?v=abc123" → "youtube.com"
     *   "http://github.com/moonshot-ai/kimi"     → "github.com"
     *   "https://docs.oracle.com/javase/8/"      → "docs.oracle.com"
     *   "youtube.com"                            → "youtube.com" (idempotente)
     *   null/blank                              → "desconocido"
     */
    private String recortarUrlIdempotente(String url) {
        if (url == null || url.isBlank()) {
            return "desconocido";
        }

        // Si no contiene protocolo ni path, asumimos que ya es dominio
        if (!url.contains("://") && !url.contains("/")) {
            return url.toLowerCase();
        }

        try {
            URI uri = new URI(url);
            String host = uri.getHost();

            if (host == null || host.isBlank()) {
                // Fallback: extraer manualmente entre :// y /
                int inicio = url.indexOf("://");
                if (inicio != -1) {
                    inicio += 3; // saltar "://"
                    int fin = url.indexOf("/", inicio);
                    host = (fin == -1) ? url.substring(inicio) : url.substring(inicio, fin);
                }
            }

            if (host == null || host.isBlank()) {
                return url.toLowerCase(); // fallback: devolver original en minusculas
            }

            // Quitar prefijo www. si existe
            host = host.toLowerCase();
            if (host.startsWith("www.")) {
                host = host.substring(4);
            }

            return host;

        } catch (URISyntaxException e) {
            // Fallback manual si URI no puede parsear
            String limpia = url.toLowerCase().trim();
            if (limpia.startsWith("http://")) limpia = limpia.substring(7);
            if (limpia.startsWith("https://")) limpia = limpia.substring(8);
            if (limpia.startsWith("www.")) limpia = limpia.substring(4);
            int slash = limpia.indexOf("/");
            if (slash != -1) limpia = limpia.substring(0, slash);
            return limpia.isBlank() ? "desconocido" : limpia;
        }
    }

    /**
     * Extrae y normaliza el tabId del JSON.
     * Maneja: entero nativo, string numérico, string con separadores de miles (español).
     * Retorna -1 si no es parseable.
     */
    private int extraerTabId(JsonNode json) {
        if (!json.has("tabId")) return -1;

        JsonNode tabIdNode = json.get("tabId");

        if (tabIdNode.isInt()) {
            return tabIdNode.asInt();
        }

        if (tabIdNode.isTextual()) {
            String raw = tabIdNode.asText().trim();
            // Eliminar separadores de miles: "329.598.133" → "329598133"
            // o "1,234,567" → "1234567"
            String limpio = raw.replace(".", "").replace(",", "").replace(" ", "");
            try {
                return Integer.parseInt(limpio);
            } catch (NumberFormatException e) {
                LOGGER.log(Level.WARNING, "[WS] tabId no numérico después de limpieza: '{0}' (raw: '{1}')",
                    new Object[]{limpio, raw});
                return -1;
            }
        }

        LOGGER.log(Level.WARNING, "[WS] tabId de tipo inesperado: {0}", tabIdNode.getNodeType());
        return -1;
    }

    @Override
    public void onClose(WebSocket conn, int code, String reason, boolean remote) {
        mensajesPorSegundo.remove(conn);
        // CAMBIO: limpiar ultimoEventoPorCliente en lugar de ultimaUrlPorCliente
        ultimoEventoPorCliente.remove(conn);
        LOGGER.log(Level.INFO, "[WS] Conexión cerrada con código {0}. Motivo: {1}", new Object[]{code, reason});
    }

    @Override
    public void onError(WebSocket conn, Exception e) {
        LOGGER.log(Level.SEVERE, "[WS] Error interno en canal", e);
        if (conn != null) {
            mensajesPorSegundo.remove(conn);
            // CAMBIO: limpiar ultimoEventoPorCliente en lugar de ultimaUrlPorCliente
            ultimoEventoPorCliente.remove(conn);
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
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}