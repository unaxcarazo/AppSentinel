package org.appsentinel.infrastructure.adapter.out;

import javafx.application.Platform;
import org.appsentinel.domain.port.out.NotificacionPort;

import java.util.function.Consumer;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * JavaFXAlertAdapter: Adaptador de SALIDA para notificaciones visuales.
 * 
 * Conecta el dominio con la interfaz JavaFX. Ejecuta callbacks en el hilo
 * de la UI de forma segura. Diseño funcional: referencia fuerte al callback
 * para garantizar entrega de eventos sin pérdidas.
 */
public class JavaFXAlertAdapter implements NotificacionPort {

    private static final Logger LOGGER = Logger.getLogger(JavaFXAlertAdapter.class.getName());

    // Referencia fuerte al callback de la UI. Garantiza entrega de eventos.
    // Nota: Si la vista se destruye, desregistrar el callback desde MainController
    // llamando registrarCallbackNuevaApp(null) para evitar retención.
    private Consumer<AppNueva> onNuevaAppSinClasificar;

    @Override
    public void registrarCallbackNuevaApp(Consumer<AppNueva> callback) {
        this.onNuevaAppSinClasificar = callback;
        LOGGER.log(Level.INFO, "[UI] Callback de apps sin clasificar registrado.");
    }

    @Override
    public void mostrarAlertaDistraccion(String mensaje, String nombreApp) {
        LOGGER.log(Level.INFO, "[ALERTA] {0}: {1}", new Object[]{mensaje, nombreApp});
        ejecutarEnHiloUI(() -> {
            // TODO: Implementar toast/banner visual en JavaFX
        });
    }

    @Override
    public void mostrarAlertaBloqueo(String nombreApp, long tiempoExcedido) {
        LOGGER.log(Level.INFO, "[BLOQUEO] {0} excedió en {1}s", new Object[]{nombreApp, tiempoExcedido});
        ejecutarEnHiloUI(() -> {
            // TODO: Implementar ventana modal de bloqueo en JavaFX
        });
    }

    @Override
    public void notificarAppSinClasificar(String nombreApp, String detalle) {
        LOGGER.log(Level.INFO, "[NOVEDAD] App sin clasificar: {0}", nombreApp);
        
        if (onNuevaAppSinClasificar != null) {
            ejecutarEnHiloUI(() -> 
                onNuevaAppSinClasificar.accept(new AppNueva(nombreApp, detalle))
            );
        }
    }

    /**
     * Ejecuta acción en el hilo de JavaFX. Si ya estamos en él, ejecuta directamente.
     */
    private void ejecutarEnHiloUI(Runnable accion) {
        try {
            if (Platform.isFxApplicationThread()) {
                accion.run();
            } else {
                Platform.runLater(accion);
            }
        } catch (IllegalStateException e) {
            LOGGER.log(Level.FINE, "Entorno gráfico no inicializado (Headless / Test)");
        }
    }
}