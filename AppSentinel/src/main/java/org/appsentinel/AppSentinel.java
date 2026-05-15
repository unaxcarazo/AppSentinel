package org.appsentinel;

import javafx.application.Application;
import javafx.fxml.FXMLLoader;
import javafx.scene.Scene;
import javafx.stage.Stage;
import org.appsentinel.infrastructure.adapter.out.persistence.DatabaseConnection;
import org.appsentinel.infrastructure.config.AppContext;
import org.appsentinel.infrastructure.config.AppWiring;
import org.appsentinel.infrastructure.gui.controller.MainController;

public class AppSentinel extends Application {

    // Flag de modo prueba: true = sin UI, solo dominio
    private static final boolean MODO_HEADLESS = Boolean.getBoolean("appsentinel.headless");

    @Override
    public void start(Stage stage) throws Exception {
        AppContext ctx = AppWiring.construir();

        if (MODO_HEADLESS) {
            System.out.println("[HEADLESS] Modo prueba activo. Sin UI.");
            // No cargar FXML, no crear MainController
            // El sistema corre solo con el scheduler de TimeTrackingService
            Thread.sleep(30000); // Prueba de 30 segundos
            ctx.tracking().finalizar();
            DatabaseConnection.cerrarPool();
            System.exit(0);
            return;
        }

        // Modo normal: JavaFX + MainController
        FXMLLoader loader = new FXMLLoader(
            getClass().getResource("/org/appsentinel/main.fxml"));
        stage.setScene(new Scene(loader.load(), 900, 600));
        stage.setTitle("AppSentinel");

        MainController controller = loader.getController();
        if (controller != null) {
            controller.init(ctx);
        }

        stage.setOnCloseRequest(e -> {
            ctx.tracking().finalizar();
            DatabaseConnection.cerrarPool();
        });
        stage.show();
    }

    public static void main(String[] args) {
        launch();
    }
}

    /*@Override
    public void start(Stage stage) throws Exception {
        AppContext ctx = AppWiring.construir();

        FXMLLoader loader = new FXMLLoader(
            getClass().getResource("/org/appsentinel/main.fxml")
        );
        stage.setScene(new Scene(loader.load(), 900, 600));
        stage.setTitle("AppSentinel");

        MainController controller = loader.getController();
        if (controller != null) {
            controller.init(ctx);
        }

        // Cierre graceful: dominio + pool de conexiones
        stage.setOnCloseRequest(e -> {
            ctx.tracking().finalizar();
            DatabaseConnection.cerrarPool();
        });
        
        stage.show();
    }

    public static void main(String[] args) {
        launch();
    }
}*/