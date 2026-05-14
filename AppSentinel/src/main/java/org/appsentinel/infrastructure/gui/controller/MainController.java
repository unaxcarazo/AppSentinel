package org.appsentinel.infrastructure.gui.controller;

import javafx.fxml.FXML;
import javafx.fxml.FXMLLoader;
import javafx.scene.Parent;
import javafx.scene.layout.AnchorPane;
import org.appsentinel.domain.port.out.CategoriaRepositoryPort;
import org.appsentinel.domain.port.out.RegistroRepositoryPort;
import org.appsentinel.domain.service.TimeTrackingService;

import java.io.IOException;

public class MainController {

    @FXML
    private AnchorPane contenedor;
    @FXML
    private Button btnDashboard;
    @FXML
    private Button btnAppBlocker;
    @FXML
    private Button btnPerformance;
    @FXML
    private Button btnHistory;
    @FXML
    private Button btnDeepFocus;

    // Dependencias que vienen de AppWiring via AppSentinel
    private TimeTrackingService tracking;
    private RegistroRepositoryPort repositorio;
    private CategoriaRepositoryPort categorias;

    /**
     * AppSentinel llama esto justo después de cargar Main.fxml.
     * Inyecta las dependencias y carga la vista por defecto.
     */
    public void init(TimeTrackingService tracking,
            RegistroRepositoryPort repositorio,
            CategoriaRepositoryPort categorias) {
        this.tracking = tracking;
        this.repositorio = repositorio;
        this.categorias = categorias;
        onDashboard(); // vista por defecto al arrancar
    }

    @FXML
    public void onDashboard() {
        resetNavStyles();
        btnDashboard.getStyleClass().add("nav-item-active");
        cargarVista("Dashboard");
    }

    @FXML
    public void onAppBlocker() {
        resetNavStyles();
        btnAppBlocker.getStyleClass().add("nav-item-active");
        cargarVista("AppBlocker");
    }

    @FXML
    public void onPerformance() {
        resetNavStyles();
        btnPerformance.getStyleClass().add("nav-item-active");
        cargarVista("Performance");
    }

    @FXML
    public void onHistory() {
        resetNavStyles();
        btnHistory.getStyleClass().add("nav-item-active");
        cargarVista("UsageHistory");
    }

    @FXML
    public void onDeepFocus() {
        /* lógica modo focus */ }

    private void resetNavStyles() {
        btnDashboard.getStyleClass().remove("nav-item-active");
        btnAppBlocker.getStyleClass().remove("nav-item-active");
        btnPerformance.getStyleClass().remove("nav-item-active");
        btnHistory.getStyleClass().remove("nav-item-active");
    }

    private void cargarVista(String nombre) {
        try {
            FXMLLoader loader = new FXMLLoader(
                    getClass().getResource(
                            "/org/appsentinel/infrastructure/adapter/in/gui/views/" + nombre + ".fxml"));
            Parent vista = loader.load();

            // Inyectar dependencias al controlador de la vista
            Object ctrl = loader.getController();
            if (ctrl instanceof DashboardController dc) {
                dc.init(repositorio);
            } else if (ctrl instanceof AppBlockerController ab) {
                ab.init(categorias);
            } else if (ctrl instanceof PerformanceController pc) {
                pc.init(tracking);
            } else if (ctrl instanceof UsageHistoryController uh) {
                uh.init(repositorio);
            }

            // Ajustar la vista al tamaño del contenedor
            AnchorPane.setTopAnchor(vista, 0.0);
            AnchorPane.setBottomAnchor(vista, 0.0);
            AnchorPane.setLeftAnchor(vista, 0.0);
            AnchorPane.setRightAnchor(vista, 0.0);

            contenedor.getChildren().setAll(vista);

        } catch (IOException e) {
            System.err.println("Error cargando vista: " + nombre);
            e.printStackTrace();
        }
    }
}
