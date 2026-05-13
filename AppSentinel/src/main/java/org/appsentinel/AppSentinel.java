package org.appsentinel;

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
        FXMLLoader loader = new FXMLLoader(
            getClass().getResource("/org/appsentinel/main.fxml")
        );
        stage.setScene(new Scene(loader.load(), 900, 600));
        stage.setTitle("AppSentinel");
        stage.setOnCloseRequest(e -> tracking.finalizar());
        stage.show();
    }

    public static void main(String[] args) {
        launch();
    }
}