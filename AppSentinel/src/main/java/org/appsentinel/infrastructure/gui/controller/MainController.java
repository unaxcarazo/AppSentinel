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
