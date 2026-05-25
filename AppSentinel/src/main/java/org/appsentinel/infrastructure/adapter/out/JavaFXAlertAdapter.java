package org.appsentinel.infrastructure.adapter.out;

import javafx.application.Platform;
import javafx.geometry.Pos;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.layout.VBox;
import javafx.stage.Stage;
import javafx.stage.StageStyle;
import org.appsentinel.domain.port.out.NotificacionPort;

import java.util.function.Consumer;
import java.util.logging.Level;
import java.util.logging.Logger;

import javafx.animation.FadeTransition;
import javafx.animation.PauseTransition;
import javafx.geometry.Rectangle2D;
import javafx.scene.paint.Color;
import javafx.stage.Screen;
import javafx.util.Duration;

/**
 * JavaFXAlertAdapter: Adaptador de SALIDA para notificaciones visuales.
 */
public class JavaFXAlertAdapter implements NotificacionPort {

    private static final Logger LOGGER = Logger.getLogger(JavaFXAlertAdapter.class.getName());

    private Consumer<AppNueva> onNuevaAppSinClasificar;

    // 🛡️ CONTROL DE SEGURIDAD: Guarda la referencia de la ventana abierta para evitar duplicados
    private Stage stageBloqueoActivo = null;

    @Override
    public void registrarCallbackNuevaApp(Consumer<AppNueva> callback) {
        this.onNuevaAppSinClasificar = callback;
        LOGGER.log(Level.INFO, "[UI] Callback de apps sin clasificar registrado.");
    }

    @Override
    public void mostrarAlertaDistraccion(String mensaje, String nombreApp) {
        
        LOGGER.log(Level.INFO, "[ALERTA] {0}: {1}", new Object[]{mensaje, nombreApp});

        // Forzamos a que TODO pase estrictamente en el hilo de la UI
        Platform.runLater(() -> {
            try {
                Stage toastStage = new Stage();

                // 1. Configuración de estilo e interacción
                toastStage.initStyle(StageStyle.TRANSPARENT);
                toastStage.setAlwaysOnTop(true);

                // 2. Diseño del Toast
                VBox layoutToast = new VBox(8);
                layoutToast.setStyle(
                        "-fx-background-color: rgba(20, 20, 20, 0.95); "
                        + "-fx-padding: 15 25 15 25; "
                        + "-fx-background-radius: 8; "
                        + "-fx-border-color: #FFB000; "
                        + "-fx-border-radius: 8; "
                        + "-fx-border-width: 2;"
                );

                Label lblTitulo = new Label("⚠️ Atención: " + nombreApp);
                lblTitulo.setStyle("-fx-text-fill: #FFB000; -fx-font-size: 16px; -fx-font-weight: bold;");

                Label lblMensaje = new Label(mensaje);
                lblMensaje.setStyle("-fx-text-fill: #FFFFFF; -fx-font-size: 14px;");

                layoutToast.getChildren().addAll(lblTitulo, lblMensaje);

                Scene escenaToast = new Scene(layoutToast);
                escenaToast.setFill(Color.TRANSPARENT);
                toastStage.setScene(escenaToast);

                // 3. Posicionamiento dinámico ANTES de mostrarlo
                Rectangle2D limitesPantalla = Screen.getPrimary().getVisualBounds();

                // Valores por defecto estimados por si las dimensiones aún son 0
                double anchoEstimado = 300;
                double altoEstimado = 80;

                toastStage.setX(limitesPantalla.getMaxX() - anchoEstimado - 20);
                toastStage.setY(limitesPantalla.getMaxY() - altoEstimado - 20);

                // 4. Mostrar e intentar traer al frente de manera agresiva
                toastStage.setOpacity(1.0); // Quitamos la opacidad 0 inicial para asegurar visibilidad directa
                toastStage.show();
                toastStage.toFront(); // Lo empuja al frente de todas las ventanas en el SO

                // 5. Animación de salida (Fade Out) tras 5 segundos
                FadeTransition fadeOut = new FadeTransition(Duration.millis(500), layoutToast);
                fadeOut.setFromValue(1.0);
                fadeOut.setToValue(0.0);
                fadeOut.setOnFinished(e -> toastStage.close());

                PauseTransition delay = new PauseTransition(Duration.seconds(5));
                delay.setOnFinished(e -> fadeOut.play());
                delay.play();

            } catch (Exception e) {
                LOGGER.log(Level.SEVERE, "Error al pintar el toast visual", e);
            }
        });
    }

    @Override
    public void mostrarAlertaBloqueo(String nombreApp, long tiempoExcedido) {
        LOGGER.log(Level.INFO, "[BLOQUEO] {0} excedió en {1}s", new Object[]{nombreApp, tiempoExcedido});

        ejecutarEnHiloUI(() -> {
            // 1. Si ya hay una pantalla de bloqueo mostrándose, no hacemos nada
            if (stageBloqueoActivo != null && stageBloqueoActivo.isShowing()) {
                return;
            }

            // 2. Creamos el nuevo Stage (la ventana) en modo Dios
            stageBloqueoActivo = new Stage();
            stageBloqueoActivo.initStyle(StageStyle.UNDECORATED); // Sin botones del sistema (X, _, mini)
            stageBloqueoActivo.setFullScreen(true);              // Ocupa TODA la pantalla
            stageBloqueoActivo.setAlwaysOnTop(true);             // Se superpone a cualquier app

            // 3. Diseño visual de la pantalla de bloqueo (Fondo oscuro y limpio)
            VBox layoutBloqueo = new VBox(30); // 30px de separación entre elementos
            layoutBloqueo.setAlignment(Pos.CENTER);
            layoutBloqueo.setStyle("-fx-background-color: rgba(15, 15, 15, 0.98); -fx-padding: 40;");

            // Etiqueta del título (Rojo neón de advertencia)
            Label lblTitulo = new Label("⚠️ ¡SISTEMA BLOQUEADO POR DISTRACCIÓN!");
            lblTitulo.setStyle("-fx-text-fill: #F00C26; -fx-font-size: 36px; -fx-font-weight: bold;");

            // Etiqueta de detalle
            Label lblDetalle = new Label("Has superado el tiempo límite permitido en: " + nombreApp);
            lblDetalle.setStyle("-fx-text-fill: #FFFFFF; -fx-font-size: 20px;");

            // Botón de Rendición / Retorno al trabajo
            Button btnVolver = new Button("Entendido, vuelvo a trabajar");
            btnVolver.setStyle("-fx-background-color: #00FF7F; "
                    + // Verde neón
                    "-fx-text-fill: #000000; "
                    + "-fx-font-size: 18px; "
                    + "-fx-font-weight: bold; "
                    + "-fx-padding: 12 30; "
                    + "-fx-background-radius: 8; "
                    + "-fx-cursor: hand;");

            // Acción del botón: Cierra la ventana de forma segura y limpia la referencia
            btnVolver.setOnAction(event -> {
                if (stageBloqueoActivo != null) {
                    stageBloqueoActivo.close();
                    stageBloqueoActivo = null; // Liberamos la memoria
                }
            });

            // Añadimos los elementos al contenedor
            layoutBloqueo.getChildren().addAll(lblTitulo, lblDetalle, btnVolver);

            // 4. Montamos la escena y la mostramos
            Scene escenaBloqueo = new Scene(layoutBloqueo);
            stageBloqueoActivo.setScene(escenaBloqueo);
            stageBloqueoActivo.show();
        });
    }

    @Override
    public void notificarAppSinClasificar(String nombreApp, String detalle) {
        LOGGER.log(Level.INFO, "[NOVEDAD] App sin clasificar: {0}", nombreApp);

        if (onNuevaAppSinClasificar != null) {
            ejecutarEnHiloUI(()
                    -> onNuevaAppSinClasificar.accept(new AppNueva(nombreApp, detalle))
            );
        }
    }

    /**
     * Ejecuta acción en el hilo de JavaFX. Si ya estamos en él, ejecuta
     * directamente.
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
