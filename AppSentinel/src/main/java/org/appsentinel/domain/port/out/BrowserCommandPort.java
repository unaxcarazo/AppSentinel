package org.appsentinel.domain.port.out;

/**
 * Puerto de salida para enviar comandos al navegador.
 * El dominio ordena cerrar una pestaña sin saber
 * que por debajo hay un WebSocket.
 */
public interface BrowserCommandPort {
    void cerrarPestaña(int tabId);
}
