package org.appsentinel;

import java.net.URL;
import javafx.application.Application;
import javafx.fxml.FXMLLoader;
import javafx.scene.Scene;
import javafx.stage.Stage;
import org.appsentinel.infrastructure.config.AppWiring;
import org.appsentinel.domain.service.TimeTrackingService;

public class AppSentinel extends Application {

    private TimeTrackingService tracking;

    @Override
    public void start(Stage stage) throws Exception {
        // Ensamblar todo el sistema
        tracking = AppWiring.construir();

        // Cargar la ventana principal
        /* FXMLLoader loader = new FXMLLoader(
            getClass().getResource("/Dashboard.fxml")
        );
        stage.setScene(new Scene(loader.load(), 1100, 700));
        stage.setTitle("AppSentinel");
        stage.setOnCloseRequest(e -> tracking.finalizar());
        stage.show();
    }
         */
        // Cambia tu bloque de carga por este para debuguear:
        URL fxmlLocation = getClass().getResource("/org/appsentinel/infrastructure/adapter/in/gui/views/Dashboard.fxml");
        if (fxmlLocation == null) {
            System.err.println("❌ ERROR: ¡No encuentro el archivo FXML! Revisa la carpeta y el nombre.");
        } else {
            FXMLLoader loader = new FXMLLoader(fxmlLocation);
            stage.setScene(new Scene(loader.load(), 1100, 700));
            stage.show();
        }

    }

    public static void main(String[] args) {
        launch();
    }
}
