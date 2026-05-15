package org.appsentinel.domain.port.out;

import java.util.function.Consumer;

/**
 * Puerto de salida para alertas visuales y notificaciones de novedades.
 */
public interface NotificacionPort {
    void mostrarAlertaDistraccion(String mensaje, String nombreApp);
    void mostrarAlertaBloqueo(String nombreApp, long tiempoExcedido);
    void notificarAppSinClasificar(String nombreApp, String detalle);
    
    // Mecanismo de registro:
    void registrarCallbackNuevaApp(Consumer<AppNueva> callback);
    
    record AppNueva(String nombreApp, String detalle) {}
}