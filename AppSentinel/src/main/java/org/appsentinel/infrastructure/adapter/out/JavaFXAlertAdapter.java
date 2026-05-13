package org.appsentinel.infrastructure.adapter.out;

import javafx.application.Platform;
import org.appsentinel.domain.port.out.NotificacionPort;

import java.util.function.Consumer;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * JavaFXAlertAdapter: Adaptador de SALIDA para notificaciones visuales.
 * 
 * Conecta el dominio con la interfaz JavaFX. Usa Platform.runLater
 * para ejecutar callbacks en el hilo de la UI de forma segura.
 */
public class JavaFXAlertAdapter implements NotificacionPort {

    private static final Logger LOGGER = Logger.getLogger(JavaFXAlertAdapter.class.getName());

    // Callback para nuevas apps sin clasificar (se inyecta desde la UI al arrancar)
    private Consumer<AppNueva> onNuevaAppSinClasificar;

    /**
     * Registra un callback que se ejecutará cuando llegue una app sin clasificar.
     * Se llama desde MainController o AppSentinel al inicializar la UI.
     */
    public void registrarCallbackNuevaApp(Consumer<AppNueva> callback) {
        this.onNuevaAppSinClasificar = callback;
    }

    @Override
    public void mostrarAlertaDistraccion(String mensaje, String nombreApp) {
        LOGGER.log(Level.INFO, "[ALERTA] {0}: {1}", new Object[]{mensaje, nombreApp});
        
        try {
            Platform.runLater(() -> {
                // TODO: Implementar toast/banner visual en JavaFX por vuestro equipo
            });
        } catch (IllegalStateException e) {
            LOGGER.log(Level.FINE, "Entorno gráfico no inicializado (Modo Headless / Test)");
        }
    }

    @Override
    public void mostrarAlertaBloqueo(String nombreApp, long tiempoExcedido) {
        LOGGER.log(Level.INFO, "[BLOQUEO] {0} excedió en {1}s", new Object[]{nombreApp, tiempoExcedido});
        
        try {
            Platform.runLater(() -> {
                // TODO: Implementar ventana modal de bloqueo en JavaFX por vuestro equipo
            });
        } catch (IllegalStateException e) {
            LOGGER.log(Level.FINE, "Entorno gráfico no inicializado (Modo Headless / Test)");
        }
    }

    @Override
    public void notificarAppSinClasificar(String nombreApp, String detalle) {
        LOGGER.log(Level.INFO, "[NOVEDAD] App sin clasificar detectada: {0}", nombreApp);
        
        if (onNuevaAppSinClasificar != null) {
            try {
                Platform.runLater(() -> 
                    onNuevaAppSinClasificar.accept(new AppNueva(nombreApp, detalle))
                );
            } catch (IllegalStateException e) {
                LOGGER.log(Level.FINE, "Entorno gráfico no inicializado (Modo Headless / Test)");
            }
        }
    }

    /**
     * DTO para transferir datos de nueva app a la UI.
     */
    public record AppNueva(String nombreApp, String detalle) {}
}
