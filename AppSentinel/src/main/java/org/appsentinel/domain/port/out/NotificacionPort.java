package org.appsentinel.domain.port.out;

/**
 * Puerto de salida para alertas visuales y notificaciones de novedades.
 */
public interface NotificacionPort {
    
    /*
     * Alerta suave: distracción detectada (Nivel 1).
     */
    void mostrarAlertaDistraccion(String mensaje, String nombreApp);
    
    /*
     * Alerta fuerte: bloqueo de sesión o cierre (Nivel 2/3).
     */
    void mostrarAlertaBloqueo(String nombreApp, long tiempoExcedido);
    
    /*
     * Novedad: nueva app detectada sin clasificar.
     * La UI debe mostrarla en BlacklistView para que el usuario decida.
     */
    void notificarAppSinClasificar(String nombreApp, String detalle);
}