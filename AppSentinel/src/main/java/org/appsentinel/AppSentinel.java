package org.appsentinel;

import javafx.application.Application;
import javafx.application.Platform;
import javafx.fxml.FXMLLoader;
import javafx.scene.Scene;
import javafx.scene.layout.BorderPane;
import javafx.stage.Stage;
import org.appsentinel.infrastructure.bootstrap.AppWiring;
import org.appsentinel.infrastructure.bootstrap.AppContext; // 🚀 Importación del contexto
import org.appsentinel.infrastructure.adapter.in.gui.controller.MainController; // 🚀 Importación del controlador

import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * AppSentinel: Punto de entrada de la aplicación JavaFX.
 *
 * FIX 1.3: El ciclo de vida de apagado está completamente conectado.
 */
public class AppSentinel extends Application {

    private static final Logger LOGGER = Logger.getLogger(AppSentinel.class.getName());

    @Override
    public void start(Stage primaryStage) throws Exception {
        LOGGER.log(Level.INFO, "[APP] Iniciando AppSentinel...");

        // 🚀 CONEXIÓN DEL GRAFO: Capturamos el AppContext devuelto por el método construir()
        AppContext ctx = AppWiring.construir();

        // Cargar UI desde la ruta correcta
        FXMLLoader loader = new FXMLLoader(getClass().getResource("/org/appsentinel/infrastructure/adapter/in/gui/views/Main.fxml"));
        BorderPane root = loader.load();

        // 🚀 INYECCIÓN CRÍTICA: Extraemos el controlador e inyectamos el contexto antes de renderizar
        Object controller = loader.getController();
        if (controller instanceof MainController) {
            LOGGER.log(Level.INFO, "[APP] Enlazando MainController con el ecosistema de servicios (AppContext).");
            MainController mainCtrl = (MainController) controller;
            mainCtrl.init(ctx); // <--- Aquí se le pasa el contexto real y se activa la navegación profunda
        } else if (controller != null) {
            LOGGER.log(Level.WARNING, "[APP] El controlador cargado no es una instancia válida de MainController: {0}", controller.getClass().getName());
        }

        // Definición de las dimensiones de la ventana principal
        Scene scene = new Scene(root);
        scene.getStylesheets().add(getClass().getResource("/styles/usagehistory.css").toExternalForm());
        primaryStage.initStyle(javafx.stage.StageStyle.DECORATED);
        primaryStage.setTitle("AppSentinel — Productividad consciente");
        primaryStage.setScene(scene);

        // Garantizar el apagado limpio del sistema mediante el evento de cierre
        primaryStage.setOnCloseRequest(event -> {
            LOGGER.log(Level.INFO, "[APP] Evento de cierre de ventana detectado.");
            Platform.exit();
        });
        
        // Evitar que JavaFX cierre la JVM automáticamente antes del shutdown graceful
        Platform.setImplicitExit(false);

        primaryStage.show();
        LOGGER.log(Level.INFO, "[APP] AppSentinel iniciado correctamente.");
    }

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
    }

    public static void main(String[] args) {
        launch(args);
    }
}
