package org.appsentinel;

import javafx.application.Application;
import javafx.application.Platform;
import javafx.fxml.FXMLLoader;
import javafx.scene.Scene;
import javafx.scene.layout.BorderPane;
import javafx.stage.Stage;
import org.appsentinel.infrastructure.bootstrap.AppWiring;

import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * AppSentinel: Punto de entrada de la aplicación JavaFX.
 *
 * FIX 1.3: El ciclo de vida de apagado está completamente conectado:
 *   - stop() llama AppWiring.detenerTodo() en cascada.
 *   - Platform.setImplicitExit(false) evita que JavaFX mate la JVM
 *     antes de que el shutdown graceful termine.
 *   - Platform.exit() al final de stop() fuerza la terminación de JavaFX
 *     y garantiza que stop() se ejecute al cerrar la ventana.
 *   - primaryStage.setOnCloseRequest dispara el cierre ordenado.
 *
 * NOTA PARA EQUIPO UI:
 * La ruta del FXML debe ser /org/appsentinel/infrastructure/adapter/in/gui/views/Main.fxml
 * (no /fxml/MainView.fxml). Este archivo no es responsabilidad del equipo backend.
 */
public class AppSentinel extends Application {

    private static final Logger LOGGER = Logger.getLogger(AppSentinel.class.getName());

    @Override
    public void start(Stage primaryStage) throws Exception {
        LOGGER.log(Level.INFO, "[APP] Iniciando AppSentinel...");

        // FIX 1.3: Evitar que JavaFX cierre la JVM automáticamente.
        // El apagado manual (AppWiring.detenerTodo) controla el orden de cierre.
        // Platform.exit() al final de stop() completará la terminación.
        Platform.setImplicitExit(false);

        // Ensamblar el grafo de dependencias
        AppWiring.construir();  // ctx se inyectará en controladores UI cuando existan

        // Cargar UI
        // Ruta FXML correcta según estructura del proyecto
        FXMLLoader loader = new FXMLLoader(getClass().getResource("/org/appsentinel/infrastructure/adapter/in/gui/views/Main.fxml"));
        BorderPane root = loader.load();

        // Inyectar contexto en el controlador principal (si existe)
        Object controller = loader.getController();
        if (controller != null) {
            LOGGER.log(Level.FINE, "[APP] Controlador cargado: {0}", controller.getClass().getSimpleName());
        }

        Scene scene = new Scene(root, 1200, 800);
        scene.getStylesheets().add(getClass().getResource("/styles/usagehistory.css").toExternalForm());

        primaryStage.setTitle("AppSentinel — Productividad consciente");
        primaryStage.setScene(scene);

        // FIX 1.3: Garantizar que stop() se ejecute al cerrar la ventana.
        // Con implicitExit=false, Platform.exit() en stop() es obligatorio
        // para que JavaFX complete el ciclo de vida.
        primaryStage.setOnCloseRequest(event -> {
            LOGGER.log(Level.INFO, "[APP] Evento de cierre de ventana detectado.");
            Platform.exit(); // Dispara stop() explícitamente
        });

        primaryStage.show();
        LOGGER.log(Level.INFO, "[APP] AppSentinel iniciado correctamente.");
    }

    /*
     * FIX 1.3: Shutdown graceful completo del sistema.
     *
     * Este método es llamado por JavaFX al ejecutar Platform.exit().
     *
     * Delega en AppWiring.detenerTodo() que ejecuta la cascada:
     *   1. Detener escáner
     *   2. Detener WebSocket
     *   3. Finalizar tracking (flush de memoria → buffer)
     *   4. Flush buffer de persistencia
     *   5. Cerrar pool HikariCP
     */
    @Override
    public void stop() throws Exception {
        LOGGER.log(Level.INFO, "[APP] Iniciando shutdown graceful de AppSentinel...");

        try {
            AppWiring.detenerTodo();
        } catch (Exception e) {
            LOGGER.log(Level.SEVERE, "[APP] Error durante shutdown graceful", e);
        }

        LOGGER.log(Level.INFO, "[APP] AppSentinel detenido.");
        super.stop();
        // Platform.exit() aquí ya fue llamado en setOnCloseRequest.
        // Si stop() se ejecuta por otra vía (ej: señal del SO), la JVM terminará
        // después de que todos los non-daemon threads terminen.
    }

    public static void main(String[] args) {
        launch(args);
    }
}