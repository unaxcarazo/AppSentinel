package org.appsentinel.infrastructure.adapter.in.gui.controller;

import javafx.application.Platform;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.fxml.FXML;
import javafx.fxml.Initializable;
import javafx.scene.control.Button;
import javafx.scene.control.ListView;
import javafx.scene.control.TextField;
import javafx.scene.layout.VBox;

import org.appsentinel.domain.model.Categoria;
import org.appsentinel.domain.port.out.CategoriaRepositoryPort;
import org.appsentinel.domain.service.DistractionDetector;
import org.appsentinel.domain.service.TimeTrackingService;
import org.appsentinel.infrastructure.bootstrap.AppContext;

import java.net.URL;
import java.util.ResourceBundle;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * AppBlockerController: Gestión de clasificación de apps y webs.
 *
 * ARQUITECTURA HEXAGONAL:
 * - Adapter de ENTRADA (infraestructura/UI).
 * - Orquesta: lee interacción del usuario, persiste vía CategoriaRepositoryPort,
 *   invalida sesiones activas en TimeTrackingService.
 * - NO contiene lógica de negocio: no decide qué categoría es válida,
 *   no ejecuta bloqueos directamente.
 *
 * MAPEO DE CATEGORÍAS UI → DOMINIO:
 *   "Trabajo" (UI)  → Categoria.PRODUCTIVO
 *   "Ocio" (UI)     → Categoria.DISTRACCION
 *
 * FIX: Antes usaba "Trabajo"/"Ocio" literales que no coincidían con el dominio,
 *      causando que apps clasificadas no aparecieran en reportes ni fueran
 *      detectadas por DistractionDetector.
 */
public class AppBlockerController implements Initializable, Controllable {

    private static final Logger LOGGER = Logger.getLogger(AppBlockerController.class.getName());

    // Puertos y servicios inyectados desde AppContext
    private CategoriaRepositoryPort categoriaService;
    private TimeTrackingService trackingService;
    private DistractionDetector detector;

    // Componentes FXML
    @FXML private TextField txtNuevaApp;
    @FXML private ListView<String> listaTrabajo;
    @FXML private ListView<String> listaOcio;
    @FXML private Button btnAnadir;
    @FXML private Button btnPasarAOcio;
    @FXML private Button btnPasarATrabajo;

    // Listas observables para binding reactivo
    private final ObservableList<String> obsTrabajo = FXCollections.observableArrayList();
    private final ObservableList<String> obsOcio = FXCollections.observableArrayList();

    @Override
    public void initialize(URL url, ResourceBundle rb) {
        if (listaTrabajo != null) {
            listaTrabajo.setItems(obsTrabajo);
        }
        if (listaOcio != null) {
            listaOcio.setItems(obsOcio);
        }
        LOGGER.log(Level.INFO, "[GUI] AppBlocker inicializado visualmente.");
    }

    @Override
    public void init(AppContext ctx) {
        this.categoriaService = ctx.categorias();
        this.trackingService = ctx.tracking();
        this.detector = ctx.detector();

        Platform.runLater(() -> {
            cargarCssDinamico();
            cargarDatosIniciales();
        });
    }

    /**
     * Inyecta la hoja de estilos CSS al nodo raíz de la escena si no está presente.
     */
    private void cargarCssDinamico() {
        if (txtNuevaApp == null || txtNuevaApp.getScene() == null) {
            LOGGER.log(Level.FINE, "[CSS] Escena no disponible aún, omitiendo inyección CSS.");
            return;
        }

        var root = txtNuevaApp.getScene().getRoot();
        String cssUrl = getClass().getResource("/styles/appblocker.css") != null
            ? getClass().getResource("/styles/appblocker.css").toExternalForm()
            : null;

        if (cssUrl != null && !root.getStylesheets().contains(cssUrl)) {
            root.getStylesheets().add(cssUrl);
            LOGGER.log(Level.INFO, "[CSS] appblocker.css inyectado en nodo raíz.");
        }
    }

    /**
     * Carga las apps clasificadas desde PostgreSQL y las distribuye en las listas UI.
     * Usa las constantes del dominio (Categoria.PRODUCTIVO / DISTRACCION).
     */
    private void cargarDatosIniciales() {
        if (categoriaService == null) {
            LOGGER.log(Level.WARNING, "[GUI] CategoriaService no disponible, listas vacías.");
            return;
        }

        try {
            obsTrabajo.setAll(categoriaService.obtenerAppsPorCategoria(Categoria.PRODUCTIVO));
            obsOcio.setAll(categoriaService.obtenerAppsPorCategoria(Categoria.DISTRACCION));

            LOGGER.log(Level.INFO,
                "[GUI] Listas sincronizadas: {0} productivas, {1} distracciones.",
                new Object[]{obsTrabajo.size(), obsOcio.size()});

        } catch (Exception e) {
            LOGGER.log(Level.SEVERE, "[GUI] Error cargando categorías iniciales", e);
        }
    }

    // -------------------------------------------------------------------------
    // Handlers de UI
    // -------------------------------------------------------------------------

    @FXML
    private void handleAnadirApp() {
        String appName = txtNuevaApp.getText().trim().toLowerCase();
        if (appName.isEmpty() || categoriaService == null) {
            return;
        }

        // Por defecto, nueva app sin clasificar va a Ocio (DISTRACCION) como precaución
        if (!obsOcio.contains(appName)) {
            obsOcio.add(appName);
        }

        persistirCategoria(appName, Categoria.DISTRACCION);
        txtNuevaApp.clear();

        LOGGER.log(Level.INFO, "[ACTION] App ''{0}'' añadida como DISTRACCION.", appName);
    }

    @FXML
    private void handlePasarAOcio() {
        String selected = listaTrabajo.getSelectionModel().getSelectedItem();
        if (selected == null || categoriaService == null) {
            return;
        }

        obsTrabajo.remove(selected);
        if (!obsOcio.contains(selected)) {
            obsOcio.add(selected);
        }

        persistirCategoria(selected, Categoria.DISTRACCION);
        LOGGER.log(Level.INFO, "[ACTION] ''{0}'' reclasificada a DISTRACCION.", selected);
    }

    @FXML
    private void handlePasarATrabajo() {
        String selected = listaOcio.getSelectionModel().getSelectedItem();
        if (selected == null || categoriaService == null) {
            return;
        }

        obsOcio.remove(selected);
        if (!obsTrabajo.contains(selected)) {
            obsTrabajo.add(selected);
        }

        persistirCategoria(selected, Categoria.PRODUCTIVO);
        LOGGER.log(Level.INFO, "[ACTION] ''{0}'' reclasificada a PRODUCTIVO.", selected);
    }

    // -------------------------------------------------------------------------
    // Persistencia y sincronización con dominio
    // -------------------------------------------------------------------------

    /**
     * Persiste la categoría en BD y sincroniza el estado activo en TimeTrackingService.
     *
     * Flujo:
     *   1. UPSERT en categorias_app (PostgreSQLCategoriaAdapter).
     *   2. Reclasificación en DistractionDetector (sin caché, efecto inmediato en nuevas detecciones).
     *   3. Invalidación de sesión activa en TimeTrackingService (si la app tiene foco ahora).
     */
    private void persistirCategoria(String nombreApp, String categoriaDominio) {
        // 1. Persistir en BD
        categoriaService.guardarCategoria(nombreApp, categoriaDominio);

        // 2. Actualizar detector (no tiene caché, pero por consistencia explícita)
        if (detector != null) {
            detector.reclasificar(nombreApp, categoriaDominio);
        }

        // 3. Invalidar sesión activa para forzar re-clasificación en próximo ciclo
        if (trackingService != null) {
            // Construir claves posibles (SYS| para procesos, WEB| para dominios)
            String claveSys = "SYS|" + nombreApp;
            String claveWeb = "WEB|" + nombreApp;

            trackingService.sincronizarCategoria(claveSys, categoriaDominio);
            trackingService.sincronizarCategoria(claveWeb, categoriaDominio);
        }
    }

    @Override
    public void shutdown() {
        LOGGER.log(Level.INFO, "[GUI] AppBlocker cerrado correctamente.");
    }
}

