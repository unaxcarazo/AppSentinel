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

package org.appsentinel.infrastructure.gui.controller;

import javafx.fxml.FXML;
import javafx.fxml.FXMLLoader;
import javafx.scene.Parent;
import javafx.scene.layout.StackPane;
import org.appsentinel.domain.port.out.CategoriaRepositoryPort;
import org.appsentinel.domain.port.out.RegistroRepositoryPort;
import org.appsentinel.domain.service.TimeTrackingService;

import java.io.IOException;

public class MainController {

    @FXML
    private StackPane contenedor;

    // Dependencias globales inyectadas desde AppWiring via AppSentinel
    private TimeTrackingService tracking;
    private RegistroRepositoryPort repositorio;
    private CategoriaRepositoryPort categorias;

    public MainController() {
        // Constructor vacío para JavaFX
    }

    /**
     * Inicializa el controlador principal distribuyendo el cableado de la
     * arquitectura hexagonal.
     */
    public void init(TimeTrackingService tracking, RegistroRepositoryPort repo, CategoriaRepositoryPort cat) {
        this.tracking = tracking;
        this.repositorio = repo;
        this.categorias = cat;

        System.out.println("✅ MainController cableado con éxito. Repositorio listo: " + (this.repositorio != null));

        // CARGA INICIAL: Cargamos el Dashboard por defecto tras asegurar que las dependencias existen
        onDashboard();
    }

    // ====================================================================
    // 🛠️ CONTROLADORES DE EVENTOS DEL MENÚ LATERAL (Mapeados con Main.fxml)
    // ====================================================================
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

    @FXML
    public void onDeepFocus() {
        System.out.println("🔥 Modo Deep Focus activado desde el menú lateral.");
        // Aquí podéis inyectar vuestro servicio de bloqueo extremo ("tracking.activarBloqueoAbsoluto()")
    }

    // ⚙️ MOTOR CENTRALIZADO DE CARGA DINÁMICA
    
    private void cargarVista(String nombre) {
        try {
            // Generamos la ruta basándonos en la estructura de paquetes de vuestro grupo
            String rutaFxml = "/org/appsentinel/infrastructure/adapter/in/gui/views/" + nombre + ".fxml";
            java.net.URL fxmlUrl = getClass().getResource(rutaFxml);

            if (fxmlUrl == null) {
                System.err.println("❌ ERROR: No se encuentra el archivo FXML en la ruta: " + rutaFxml);
                return;
            }

            FXMLLoader loader = new FXMLLoader(fxmlUrl);
            Parent vista = loader.load();

            // INYECCIÓN DE DEPENDENCIAS CRUCIAL ENTRE MIEMBROS DEL GRUPO
            Object ctrl = loader.getController();

            if (ctrl instanceof DashboardController dc) {
                dc.init(this.repositorio); // Le pasamos el repositorio real al Dashboard de tu compañero
            } else if (ctrl instanceof UsageHistoryController uh) {
                uh.init(this.repositorio); // Le pasamos el mismo repositorio real a tu Usage History
            }
            // NOTA PARA EL GRUPO: A medida que tus compañeros terminen sus controladores,
            // simplemente añade sus bloques correspondientes aquí:
            // else if (ctrl instanceof AppBlockerController abc) { abc.init(this.categorias); }

            // Al usar StackPane, simplemente limpiamos el centro e inyectamos la vista.
            // Automáticamente se expandirá al 100% del ancho y alto sin necesidad de usar anclas manuales.
            contenedor.getChildren().setAll(vista);
            System.out.println("🖥️ Subvista [" + nombre + "] incrustada correctamente.");

        } catch (IOException e) {
            System.err.println("❌ Error crítico cargando la subvista dinámica: " + nombre);
            e.printStackTrace();
        }
    }
}



