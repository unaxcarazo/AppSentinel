package org.appsentinel.infrastructure.adapter.in;

import org.appsentinel.domain.port.in.BrowserEventPort;
import org.appsentinel.domain.port.out.BrowserCommandPort; // <-- Importamos tu puerto de salida
import org.java_websocket.WebSocket;
import org.java_websocket.handshake.ClientHandshake;
import org.java_websocket.server.WebSocketServer;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.net.InetSocketAddress;

/**
 * WebSocketAdapter: Servidor WebSocket que escucha mensajes
 * de la extensión Chrome y los pasa al dominio.
 */
// LA SINTAXIS CORRECTA: Extiende la clase primero, e implementa la interfaz después
public class WebSocketAdapter extends WebSocketServer implements BrowserCommandPort {

    private final BrowserEventPort browserEventPort;
    private final ObjectMapper     mapper = new ObjectMapper();

    // Origin de la extensión — seguridad CORS
    private static final String ORIGIN_PERMITIDO = "chrome-extension://";

    public WebSocketAdapter(BrowserEventPort browserEventPort) {
        super(new InetSocketAddress(8080));
        this.browserEventPort = browserEventPort;
    }

    // ── Ciclo de vida del servidor ────────────────────────────────
    
    // Descomentamos el método para cumplir con el contrato de BrowserCommandPort
    @Override
    public void cerrarPestaña(int tabId) {
        String json = String.format(
            "{\"accion\":\"cerrar_pestaña\",\"tabId\":%d}", tabId);

        // Enviar el comando JSON a la extensión de Chrome conectada
        broadcast(json);
        System.out.println("Comando cerrar pestaña enviado: tabId=" + tabId);
    }
    
    @Override
    public void onOpen(WebSocket conn, ClientHandshake handshake) {
        String origin = handshake.getFieldValue("Origin");

        // Validación de seguridad: solo acepta la extensión
        if (origin == null || !origin.startsWith(ORIGIN_PERMITIDO)) {
            System.err.println("Conexión rechazada — origen no permitido: " + origin);
            conn.close();
            return;
        }

        System.out.println("Extensión Chrome conectada: " + conn.getRemoteSocketAddress());
    }

    @Override
    public void onMessage(WebSocket conn, String message) {
        try {
            // Parsear el JSON que manda la extensión
            JsonNode json = mapper.readTree(message);

            String url    = json.has("url")    ? json.get("url").asText()    : null;
            String titulo = json.has("titulo")  ? json.get("titulo").asText() : null;

            if (url == null || url.isBlank()) {
                System.err.println("Mensaje sin URL ignorado: " + message);
                return;
            }

            // Pasar al dominio — el adaptador no decide nada más
            browserEventPort.reportarEventoNavegador(url, titulo);

        } catch (Exception e) {
            System.err.println("Error procesando mensaje WebSocket: " + e.getMessage());
        }
    }

    @Override
    public void onClose(WebSocket conn, int code, String reason, boolean remote) {
        System.out.println("Extensión Chrome desconectada. Motivo: " + reason);
    }

    @Override
    public void onError(WebSocket conn, Exception e) {
        System.err.println("Error en WebSocket: " + e.getMessage());
    }

    @Override
    public void onStart() {
        System.out.println("Servidor WebSocket iniciado en puerto 8080");
    }

    // ── Control del servidor ──────────────────────────────────────

    public void iniciar() {
        this.start();
    }

    public void detener() {
        try {
            this.stop();
            System.out.println("Servidor WebSocket detenido.");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
