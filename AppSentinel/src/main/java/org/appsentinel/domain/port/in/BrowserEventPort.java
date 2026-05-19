// ============================================
// org.appsentinel.domain.port.in.BrowserEventPort
// ============================================
package org.appsentinel.domain.port.in;

/**
 * Puerto de entrada para eventos del navegador.
 * El WebSocketAdapter implementará esto para pasarle URLs
 * al dominio cuando la extensión detecte un cambio de pestaña.
 */
public interface BrowserEventPort {
    
    /*
     * Reporta una URL detectada por la extensión del navegador.
     * @param url URL completa (ej: "https://www.youtube.com/watch?v=...")
     * @param titulo Título de la pestaña
     */
    void reportarEventoNavegador(String url, String titulo, int tabId);
}