package org.appsentinel.domain.port.out;

/**
 * Puerto de salida para enviar comandos al navegador.
 * El dominio ordena cerrar una pestaña sin saber
 * que por debajo hay un WebSocket.
 *
 * tabId es long porque Chrome genera IDs que pueden
 * superar Integer.MAX_VALUE (2.147.483.647).
 */
public interface BrowserCommandPort {
    void cerrarPestana(int tabId);
}