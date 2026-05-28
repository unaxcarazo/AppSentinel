package org.appsentinel.infrastructure.adapter.in.gui.controller;

import javafx.application.Platform;
import javafx.fxml.FXML;
import javafx.fxml.FXMLLoader;
import javafx.scene.Parent;
import javafx.scene.layout.StackPane; // 🔄 Mantenemos el StackPane líquido que descubrimos antes
import java.io.IOException;
import java.util.logging.Level;
import java.util.logging.Logger;
import org.appsentinel.infrastructure.bootstrap.AppContext;

/**
 * MainController: Controlador raíz de la interfaz gráfica. Orquesta el
 * intercambio dinámico de pantallas de forma polimórfica y desacoplada.
 */
public class MainController {

    private static final Logger LOGGER = Logger.getLogger(MainController.class.getName());

    //   Usamos StackPane en lugar de AnchorPane para que las pantallas del grupo sean 100% responsive
    @FXML
    private StackPane contenedor;

    private AppContext ctx;


    @FXML
    public void initialize() {
        if (contenedor == null) {
            throw new IllegalStateException("Error crítico: fx:id=\"contenedor\" no fue inyectado correctamente. Verificar main.fxml.");
        }
    }

    /**
     * Inyección inicial del contexto global de la aplicación. Carga de forma
     * segura la pantalla por defecto del sistema de manera asíncrona.
     */
    public void init(AppContext ctx) {
        if (ctx == null) {
            throw new IllegalArgumentException("El contexto de la aplicación (AppContext) no puede ser nulo.");
        }
        this.ctx = ctx;

        // Carga el Dashboard de forma segura una vez el hilo de JavaFX esté listo
        Platform.runLater(() -> cargarVista("Dashboard"));
    }

    // =========================================================================
    // ACCIONES DEL MENÚ LATERAL (@FXML) - Coincidiendo con las mayúsculas de tus archivos
    // =========================================================================
    @FXML
    public void onDashboard() {
        cargarVista("Dashboard");
    }

    @FXML
    public void onAppBlocker() {
        cargarVista("AppBlocker");
    }

    @FXML
    public void onPerformance() {
        cargarVista("Performance");
    }

    @FXML
    public void onHistory() {
        cargarVista("UsageHistory");
    }


    // =========================================================================
    // DESPACHADOR DINÁMICO DE PANTALLAS (MÉTODO NÚCLEO POLIMÓRFICO)
    // =========================================================================
    private void cargarVista(String nombre) {
        if (ctx == null) {
            LOGGER.log(Level.SEVERE, "[ERROR] Intento de navegación ignorado: AppContext no inicializado.");
            return;
        }

        try {
            FXMLLoader loader = new FXMLLoader(
                    getClass().getResource("/org/appsentinel/infrastructure/adapter/in/gui/views/" + nombre + ".fxml"));
            Parent vista = loader.load();

            // 🔀 INYECCIÓN POLIMÓRFICA: Magia SOLID aplicada al grupo
            Object ctrl = loader.getController();
            if (ctrl instanceof Controllable controladorUIVisible) {
                controladorUIVisible.init(ctx); // Se inicializa pasándole el contexto unificado
            } else {
                LOGGER.log(Level.WARNING, "[ADVERTENCIA] El controlador de {0} no implementa la interfaz Controllable.", nombre);
            }

            // Al usar StackPane, limpiamos el contenido e inyectamos la vista.
            // Se expande automáticamente al 100% del tamaño disponible sin anclas manuales.
            contenedor.getChildren().setAll(vista);
            LOGGER.log(Level.INFO, "[OK] Subvista [{0}] incrustada correctamente.", nombre);

        } catch (IOException e) {
            LOGGER.log(Level.SEVERE, "[ERROR CRÍTICO] No se pudo cargar el archivo FXML: {0}", nombre);
            LOGGER.log(Level.SEVERE, e.getMessage(), e);
        }
    }

}
