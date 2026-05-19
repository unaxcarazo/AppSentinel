package org.appsentinel;

import java.net.URL;
import javafx.application.Application;
import javafx.fxml.FXMLLoader;
import javafx.scene.Scene;
import javafx.scene.Parent;
import javafx.stage.Stage;
import org.appsentinel.infrastructure.config.AppWiring;
import org.appsentinel.domain.service.TimeTrackingService;
import org.appsentinel.infrastructure.gui.controller.MainController;

public class AppSentinel extends Application {

    private TimeTrackingService tracking;
    
    @Override
    public void start(Stage stage) throws Exception {
        // 1. Ensamblar todo el sistema (Capa de Dominio e Infraestructura mediante AppWiring)
        tracking = AppWiring.construir();

        // 2. Localizar de forma segura el layout maestro unificado
        URL fxmlLocation = getClass().getResource("/org/appsentinel/infrastructure/adapter/in/gui/views/Main.fxml");
        
        if (fxmlLocation == null) {
            System.err.println("❌ ERROR CRÍTICO: No se encuentra el archivo Main.fxml en los recursos.");
            return;
        }

        // 3. Inicializar el cargador FXML
        FXMLLoader loader = new FXMLLoader(fxmlLocation);
        Parent root = loader.load();
        
        // 4. CABLEADO DE ARQUITECTURA: Pasar dependencias vivas de AppWiring al MainController
        MainController mainController = loader.getController();
        mainController.init(
            tracking, 
            AppWiring.obtenerRegistroRepositoryPort(), // Consigue el repositorio real de Postgres
            AppWiring.obtenerCategoriaRepositoryPort() // Consigue las categorías reales de Postgres
        );

        // 5. Crear la Escena Principal
        Scene scene = new Scene(root, 1200, 700);

        // 6. Configurar y desplegar la ventana principal
        stage.setScene(scene);
        stage.setTitle("EXTREMA :: AppSentinel Dashboard");
        
        // 7. CIERRE SEGURO: Asegura apagar el escáner y liberar los puertos al cerrar la ventana
        stage.setOnCloseRequest(e -> {
            System.out.println("Stopping core background services...");
            if (tracking != null) {
                tracking.finalizar();
            }
            System.exit(0);
        });
        
        stage.show();
        System.out.println("🚀 GUI iniciada con éxito en entorno unificado.");
    }

    public static void main(String[] args) {
        launch(args);
    }
}