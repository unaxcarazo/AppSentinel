package org.appsentinel.infrastructure.gui.controller;

import javafx.application.Platform;
import javafx.fxml.FXML;
import javafx.fxml.FXMLLoader;
import javafx.scene.Parent;
import javafx.scene.layout.AnchorPane;
import org.appsentinel.infrastructure.config.AppContext;

import java.io.IOException;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * MainController: Controlador raíz de la interfaz gráfica.
 * Orquesta el intercambio dinámico de pantallas e inyecta dependencias de forma polimórfica.
 */
public class MainController {

    private static final Logger LOGGER = Logger.getLogger(MainController.class.getName());

    @FXML private AnchorPane contenedor;

    private AppContext ctx;

    /**
     * Método nativo de JavaFX. Se ejecuta automáticamente 
     * tras la inyección de los componentes FXML.
     */
    @FXML
    public void initialize() {
        if (contenedor == null) {
            throw new IllegalStateException("Error crítico: fx:id=\"contenedor\" no fue inyectado correctamente. Verificar main.fxml.");
        }
    }

    /**
     * Inyección inicial del contexto global de la aplicación.
     * Carga de forma segura la pantalla por defecto del sistema.
     *
     * @param ctx Grafo de dependencias inmutable con todos los puertos.
     */
    public void init(AppContext ctx) {
        if (ctx == null) {
            throw new IllegalArgumentException("El contexto de la aplicación (AppContext) no puede ser nulo.");
        }
        this.ctx = ctx;
        
        Platform.runLater(() -> cargarVista("dashboard"));
    }

    // =========================================================================
    // ACCIONES DEL MENÚ LATERAL (@FXML)
    // =========================================================================
    @FXML public void onDashboard()   { cargarVista("dashboard"); }
    @FXML public void onAppBlocker()  { cargarVista("appblocker"); }
    @FXML public void onPerformance() { cargarVista("performance"); }
    @FXML public void onHistory()     { cargarVista("usagehistory"); }
    @FXML public void onDeepFocus()   { cargarVista("deepfocus"); }

    // =========================================================================
    // DESPACHADOR DINÁMICO DE PANTALLAS (MÉTODO NÚCLEO)
    // =========================================================================
    /**
     * Busca un archivo FXML por nombre, instancia su vista, resuelve 
     * polimórficamente su controlador e inyecta el AppContext.
     *
     * @param nombre Nombre del archivo .fxml sin extensión (ej: "dashboard").
     */
    private void cargarVista(String nombre) {
        if (ctx == null) {
            LOGGER.log(Level.SEVERE, "[ERROR] Intento de navegación ignorado: AppContext no inicializado.");
            return;
        }

        try {
            FXMLLoader loader = new FXMLLoader(
                getClass().getResource("/org/appsentinel/infrastructure/adapter/in/gui/views/" + nombre + ".fxml"));
            Parent vista = loader.load();

            Object ctrl = loader.getController();
            
            if (ctrl instanceof Controllable controladorUIVisible) {
                controladorUIVisible.init(ctx);
            } else {
                LOGGER.log(Level.WARNING, "[ADVERTENCIA] El controlador de {0} no implementa la interfaz Controllable.", nombre);
            }

            AnchorPane.setTopAnchor(vista, 0.0);
            AnchorPane.setBottomAnchor(vista, 0.0);
            AnchorPane.setLeftAnchor(vista, 0.0);
            AnchorPane.setRightAnchor(vista, 0.0);

            contenedor.getChildren().setAll(vista);

        } catch (IOException e) {
            LOGGER.log(Level.SEVERE, "[ERROR CRÍTICO] No se pudo cargar el archivo FXML: {0}", nombre);
            LOGGER.log(Level.SEVERE, e.getMessage(), e);
        }
    }
}