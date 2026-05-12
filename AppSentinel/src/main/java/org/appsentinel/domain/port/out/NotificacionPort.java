// ============================================
// org.appsentinel.domain.port.out.NotificacionPort
// ============================================
package org.appsentinel.domain.port.out;

/**
 * Puerto de salida para alertas visuales.
 * El dominio decide CUÁNDO alertar, JavaFXAlertAdapter decide CÓMO.
 */
public interface NotificacionPort {
    
    /**
     * Muestra una alerta de distracción detectada.
     * @param mensaje Texto descriptivo
     * @param nombreApp Nombre de la aplicación distractora
     */
    void mostrarAlertaDistraccion(String mensaje, String nombreApp);
    
    /**
     * Muestra una alerta de bloqueo (cuando se supera el tiempo límite).
     * @param nombreApp App que será bloqueada
     * @param tiempoExcedido Segundos que estuvo abierta
     */
    void mostrarAlertaBloqueo(String nombreApp, long tiempoExcedido);
}