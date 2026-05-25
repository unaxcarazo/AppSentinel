package org.appsentinel.infrastructure.adapter.in.gui.controller;

import javafx.application.Platform;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.fxml.FXML;
import javafx.fxml.Initializable;
import javafx.scene.control.Button;
import javafx.scene.control.ListView;
import javafx.scene.control.TextField;
import java.net.URL;
import java.util.ResourceBundle;
import java.util.logging.Level;
import java.util.logging.Logger;

// Importaciones de la arquitectura hexagonal de AppSentinel
import org.appsentinel.domain.port.out.KillerPort;
import org.appsentinel.domain.port.out.CategoriaRepositoryPort;
import org.appsentinel.infrastructure.bootstrap.AppContext;

/**
 * FXML Controller class para la sección App Blocker. Inyecta dinámicamente la
 * hoja de estilos CSS y coordina la persistencia de categorías.
 */
public class AppBlockerController implements Initializable, Controllable {

    private static final Logger LOGGER = Logger.getLogger(AppBlockerController.class.getName());

    // Puertos de salida reales inyectados desde la infraestructura de la aplicación
    private KillerPort killerService;
    private CategoriaRepositoryPort categoriaService;

    // Referencias FXML vinculadas al diseño Neón de la GUI
    @FXML
    private TextField txtNuevaApp;
    @FXML
    private ListView<String> listaTrabajo;
    @FXML
    private ListView<String> listaOcio;

    @FXML
    private Button btnAnadir;
    @FXML
    private Button btnPasarAOcio;
    @FXML
    private Button btnPasarATrabajo;

    // Listas observables mapeadas para un rendimiento fluido y reactivo
    private final ObservableList<String> obsTrabajo = FXCollections.observableArrayList();
    private final ObservableList<String> obsOcio = FXCollections.observableArrayList();

    @Override
    public void initialize(URL url, ResourceBundle rb) {
        // Enlazamos de forma definitiva las ListViews con nuestras listas observables
        if (listaTrabajo != null) {
            listaTrabajo.setItems(obsTrabajo);
        }
        if (listaOcio != null) {
            listaOcio.setItems(obsOcio);
        }
        LOGGER.log(Level.INFO, "[GUI] AppBlocker inicializado visualmente.");
    }

    /**
     * Configura el controlador con el contexto global y fuerza la inyección del
     * CSS.
     */
    @Override
    public void init(AppContext ctx) {
        this.killerService = ctx.killer();
        this.categoriaService = ctx.categorias();

        // Carga delegada de datos e inyección de CSS al hilo prioritario de JavaFX
        Platform.runLater(() -> {
            cargarCssDinamico();
            cargarDatosIniciales();
        });
    }

    /**
     * 🔥 LA CLAVE: Detecta el contenedor de la escena e inyecta la hoja de
     * estilos si falta
     */
    private void cargarCssDinamico() {
        if (txtNuevaApp != null && txtNuevaApp.getScene() != null) {
            var root = txtNuevaApp.getScene().getRoot();
            if (!root.getStylesheets().contains("/styles/appblocker.css")) {
                String rutaCss = getClass().getResource("/styles/appblocker.css").toExternalForm();
                root.getStylesheets().add(rutaCss);
                LOGGER.log(Level.INFO, "[CSS] appblocker.css inyectado con éxito en el nodo raíz.");
            }
        }
    }

    /**
     * Recupera las listas del almacén de persistencia real y actualiza la vista
     * de forma segura.
     */
    private void cargarDatosIniciales() {
        if (categoriaService != null) {
            try {
                java.util.List<String> appsTrabajo = categoriaService.obtenerAppsPorCategoria("Trabajo");
                java.util.List<String> appsOcio = categoriaService.obtenerAppsPorCategoria("Ocio");

                obsTrabajo.clear();
                if (appsTrabajo != null) {
                    obsTrabajo.addAll(appsTrabajo);
                }

                obsOcio.clear();
                if (appsOcio != null) {
                    obsOcio.addAll(appsOcio);
                }

                LOGGER.log(Level.INFO, "[GUI] Listas sincronizadas con la base de datos.");
            } catch (Exception e) {
                LOGGER.log(Level.SEVERE, "Error al cargar categorías iniciales", e);
            }
        }
    }

    /**
     * Acción para registrar y bloquear inmediatamente una nueva aplicación.
     */
    @FXML
    private void handleAnadirApp() {
        String appName = txtNuevaApp.getText().trim();

        if (!appName.isEmpty() && categoriaService != null && killerService != null) {
            if (!obsOcio.contains(appName)) {
                obsOcio.add(appName);
            }
            killerService.cerrarProceso(appName, 0);
            categoriaService.guardarCategoria(appName, "Ocio");

            txtNuevaApp.clear();
            LOGGER.log(Level.INFO, "[ACTION] App ''{0}'' añadida a Ocio.", appName);
        }
    }

    /**
     * Transfiere una aplicación de Trabajo a Ocio de manera segura.
     */
    @FXML
    private void handlePasarAOcio() {
        String selected = listaTrabajo.getSelectionModel().getSelectedItem();
        if (selected != null && categoriaService != null && killerService != null) {
            obsTrabajo.remove(selected);
            if (!obsOcio.contains(selected)) {
                obsOcio.add(selected);
            }

            killerService.cerrarProceso(selected, 0);
            categoriaService.guardarCategoria(selected, "Ocio");
            LOGGER.log(Level.INFO, "[ACTION] ''{0}'' movida a Ocio y bloqueada.", selected);
        }
    }

    /**
     * Revoca el bloqueo de una aplicación transfiriéndola a Trabajo.
     */
    @FXML
    private void handlePasarATrabajo() {
        String selected = listaOcio.getSelectionModel().getSelectedItem();
        if (selected != null && categoriaService != null) {
            obsOcio.remove(selected);
            if (!obsTrabajo.contains(selected)) {
                obsTrabajo.add(selected);
            }

            categoriaService.guardarCategoria(selected, "Trabajo");
            LOGGER.log(Level.INFO, "[ACTION] ''{0}'' indultada y movida a Trabajo.", selected);
        }
    }

    @Override
    public void shutdown() {
        LOGGER.log(Level.INFO, "[GUI] AppBlocker cerrado correctamente.");
    }
}
