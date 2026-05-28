package org.appsentinel.infrastructure.adapter.in.gui.controller;

import javafx.application.Platform;
import javafx.fxml.FXML;
import javafx.fxml.Initializable;
import javafx.geometry.Pos;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.TextField;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import org.appsentinel.domain.model.Categoria;
import org.appsentinel.domain.model.Registro;
import org.appsentinel.domain.port.out.RegistroRepositoryPort;
import org.appsentinel.infrastructure.bootstrap.AppContext;

import java.net.URL;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.ResourceBundle;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.stream.Collectors;

/**
 * UsageHistoryController: Adaptador de ENTRADA (GUI) para la vista del
 * historial.
 *
 * DISEÑO: - El FXML usa VBox con fx:id="vboxTableRows" - Este controlador
 * inyecta VBox y construye filas HBox dinámicamente. - La consulta a PostgreSQL
 * corre en un hilo de fondo (ScheduledExecutorService). - La actualización de
 * la UI siempre pasa por Platform.runLater (thread-safe).
 *
 * CATEGORÍAS: todas las referencias usan Categoria.java del dominio.
 * Categoria.BACKGROUND = "BACKGROUND_" (prefijo de apps en segundo plano).
 */
public class UsageHistoryController implements Initializable, Controllable {

    private static final Logger LOGGER = Logger.getLogger(UsageHistoryController.class.getName());
    private static final DateTimeFormatter FMT_HORA = DateTimeFormatter.ofPattern("HH:mm:ss");

    private static final String FILTER_ALL = "All Activities";
    private static final String FILTER_WORK = "Productive";
    private static final String FILTER_DISTRACTIONS = "Distractions";
    private static final String FILTER_NEUTRAL = "Neutral";
    private static final String FILTER_UNCLASSIFIED = "Unclassified";
    private static final String FILTER_BACKGROUND = "Background";

    // -------------------------------------------------------------------------
    // @FXML — nombres exactos de fx:id en UsageHistory.fxml
    // -------------------------------------------------------------------------
    @FXML
    private VBox vboxTableRows;
    @FXML
    private TextField txtSearch;
    @FXML
    private ComboBox<String> comboFilter;

    // -------------------------------------------------------------------------
    // Estado interno
    // -------------------------------------------------------------------------
    private List<Registro> listaCompletaMaster = new ArrayList<>();
    private RegistroRepositoryPort repositorio;
    private ScheduledExecutorService uiScheduler;

    // -------------------------------------------------------------------------
    // Ciclo de vida JavaFX
    // -------------------------------------------------------------------------
    @Override
    public void initialize(URL url, ResourceBundle rb) {
        if (comboFilter != null) {
            comboFilter.getItems().setAll(
                    FILTER_ALL, FILTER_WORK, FILTER_DISTRACTIONS,
                    FILTER_NEUTRAL, FILTER_BACKGROUND, FILTER_UNCLASSIFIED
            );
            comboFilter.setValue(FILTER_ALL);
            comboFilter.valueProperty().addListener((obs, old, nuevo) -> aplicarFiltros());
        }

        if (txtSearch != null) {
            txtSearch.textProperty().addListener((obs, old, nuevo) -> aplicarFiltros());
        }
    }

    // -------------------------------------------------------------------------
    // Controllable
    // -------------------------------------------------------------------------
    @Override
    public void init(AppContext ctx) {
        this.repositorio = ctx.repo();

        recargarDesdeDb();

        uiScheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "UsageHistory-Refresh");
            t.setDaemon(true);
            return t;
        });
        uiScheduler.scheduleAtFixedRate(this::recargarDesdeDb, 5, 5, TimeUnit.SECONDS);
    }

    @Override
    public void shutdown() {
        if (uiScheduler != null && !uiScheduler.isShutdown()) {
            uiScheduler.shutdown();
            try {
                if (!uiScheduler.awaitTermination(800, TimeUnit.MILLISECONDS)) {
                    uiScheduler.shutdownNow();
                }
            } catch (InterruptedException e) {
                uiScheduler.shutdownNow();
                Thread.currentThread().interrupt();
            }
            LOGGER.log(Level.INFO, "[HISTORIAL] Scheduler de auto-refresh detenido.");
        }
    }

    // -------------------------------------------------------------------------
    // Carga de datos desde PostgreSQL (hilo de fondo)
    // -------------------------------------------------------------------------
    private void recargarDesdeDb() {
        if (repositorio == null) {
            return;
        }

        try {
            String usuario = System.getProperty("user.name");
            List<Registro> registros = repositorio.obtenerTodosHoy(usuario);
            List<Registro> seguros = (registros != null) ? registros : new ArrayList<>();

            Platform.runLater(() -> {
                listaCompletaMaster = seguros;
                aplicarFiltros();
            });

        } catch (Exception e) {
            LOGGER.log(Level.SEVERE, "[HISTORIAL] Error consultando PostgreSQL", e);
        }
    }

    // -------------------------------------------------------------------------
    // Filtrado y renderizado
    // -------------------------------------------------------------------------
    private void aplicarFiltros() {
        if (vboxTableRows == null) {
            LOGGER.log(Level.WARNING, "[HISTORIAL] vboxTableRows es null — revisar fx:id en FXML.");
            return;
        }

        String busqueda = (txtSearch != null)
                ? txtSearch.getText().toLowerCase().trim()
                : "";
        String filtro = (comboFilter != null && comboFilter.getValue() != null)
                ? comboFilter.getValue()
                : FILTER_ALL;

        List<Registro> filtrados = listaCompletaMaster.stream()
                .filter(r -> coincideTexto(r, busqueda))
                .filter(r -> coincideCategoria(r, filtro))
                .collect(Collectors.toList());

        renderizarFilas(filtrados);
    }

    private boolean coincideTexto(Registro r, String busqueda) {
        if (busqueda.isEmpty()) {
            return true;
        }
        String nombre = r.getNombreActividad();
        return nombre != null && nombre.toLowerCase().contains(busqueda);
    }

    /**
     * Normaliza categorías BACKGROUND_* usando Categoria.BACKGROUND del
     * dominio. Ejemplo:
     * "BACKGROUND_DISTRACCION".startsWith(Categoria.BACKGROUND) → true catBase
     * = "DISTRACCION" → coincide con FILTER_DISTRACTIONS
     */
    private boolean coincideCategoria(Registro r, String filtro) {
        if (FILTER_ALL.equals(filtro)) {
            return true;
        }

        String cat = r.getCategoria();
        if (cat == null) {
            return false;
        }

        boolean esBackground = cat.startsWith(Categoria.BACKGROUND);
        String catBase = esBackground
                ? cat.substring(Categoria.BACKGROUND.length())
                : cat;

        return switch (filtro) {
            case FILTER_WORK ->
                Categoria.PRODUCTIVO.equals(catBase);
            case FILTER_DISTRACTIONS ->
                Categoria.DISTRACCION.equals(catBase);
            case FILTER_NEUTRAL ->
                Categoria.NEUTRAL.equals(catBase);
            case FILTER_UNCLASSIFIED ->
                Categoria.SIN_CLASIFICAR.equals(catBase);
            case FILTER_BACKGROUND ->
                esBackground;
            default ->
                true;
        };
    }

    private void renderizarFilas(List<Registro> registros) {
        vboxTableRows.getChildren().clear();

        if (registros.isEmpty()) {
            vboxTableRows.getChildren().add(construirFilaVacia());
            return;
        }

        for (Registro r : registros) {
            vboxTableRows.getChildren().add(construirFila(r));
        }
    }

    // -------------------------------------------------------------------------
    // Construcción de filas
    // -------------------------------------------------------------------------
    private HBox construirFila(Registro r) {
        HBox fila = new HBox(10);
        fila.setAlignment(Pos.CENTER_LEFT);
        // Clase base de fila — definida en usagehistory.css
        fila.getStyleClass().add("table-row-custom");

        // APPLICATION NAME
        Label lblNombre = new Label(r.getNombreActividad() != null ? r.getNombreActividad() : "—");
        lblNombre.setPrefWidth(250);
        lblNombre.setMinWidth(250);
        lblNombre.setMaxWidth(250);
        // app-name-label → texto blanco bold 14px según usagehistory.css
        lblNombre.getStyleClass().add("app-name-label");

        Region spacer1 = new Region();
        HBox.setHgrow(spacer1, Priority.ALWAYS);

        // START TIME
        String hora = (r.getFechaRegistro() != null)
                ? r.getFechaRegistro().format(FMT_HORA)
                : "--:--:--";
        Label lblHora = new Label(hora);
        lblHora.setPrefWidth(120);
        lblHora.setMinWidth(120);
        lblHora.setMaxWidth(120);
        lblHora.setAlignment(Pos.CENTER);
        // table-cell-custom → texto #e6edf3 13px según usagehistory.css
        lblHora.getStyleClass().add("table-cell-custom");

        Region spacer2 = new Region();
        HBox.setHgrow(spacer2, Priority.ALWAYS);

        // DURATION
        Label lblDuracion = new Label(formatearDuracion(r.getDuracionSeg()));
        lblDuracion.setPrefWidth(120);
        lblDuracion.setMinWidth(120);
        lblDuracion.setMaxWidth(120);
        lblDuracion.setAlignment(Pos.CENTER);
        lblDuracion.getStyleClass().add("table-cell-custom");

        Region spacer3 = new Region();
        HBox.setHgrow(spacer3, Priority.ALWAYS);

        // STATUS — badge con color según categoría
        String catMostrada = categoriaMostrada(r.getCategoria());
        Label lblStatus = new Label(catMostrada);
        lblStatus.setPrefWidth(100);
        lblStatus.setMinWidth(100);
        lblStatus.setMaxWidth(100);
        lblStatus.setAlignment(Pos.CENTER);
        // badge-work / badge-distraction según usagehistory.css
        lblStatus.getStyleClass().add(badgeCategoria(r.getCategoria()));

        fila.getChildren().addAll(lblNombre, spacer1, lblHora, spacer2, lblDuracion, spacer3, lblStatus);
        return fila;
    }

    private HBox construirFilaVacia() {
        HBox fila = new HBox();
        fila.setAlignment(Pos.CENTER);
        // Reutiliza table-row-custom para mantener el padding y fondo consistente
        fila.getStyleClass().add("table-row-custom");
        Label lbl = new Label("No activity recorded for today.");
        // table-cell-custom → color #e6edf3 consistente con el resto de celdas
        lbl.getStyleClass().add("table-cell-custom");
        fila.getChildren().add(lbl);
        return fila;
    }

    // -------------------------------------------------------------------------
    // Utilidades de presentación
    // -------------------------------------------------------------------------
    /**
     * Devuelve la clase CSS del badge STATUS según la categoría. Clases
     * definidas en usagehistory.css: badge-work → azul (PRODUCTIVO)
     * badge-distraction → naranja (DISTRACCION) status-allowed → verde
     * (NEUTRAL) status-blocked → rojo (SIN_CLASIFICAR) Las categorías
     * BACKGROUND_* se mapean a su base.
     */
    private String badgeCategoria(String cat) {
        if (cat == null) {
            return "status-blocked";
        }
        if (cat.startsWith(Categoria.BACKGROUND)) {
            String base = cat.substring(Categoria.BACKGROUND.length());
            return switch (base) {
                case "PRODUCTIVO" ->
                    "badge-work";
                case "DISTRACCION" ->
                    "badge-distraction";
                default ->
                    "status-allowed";
            };
        }
        return switch (cat) {
            case "PRODUCTIVO" ->
                "badge-work";
            case "DISTRACCION" ->
                "badge-distraction";
            case "NEUTRAL" ->
                "status-allowed";
            case "SIN_CLASIFICAR" ->
                "status-blocked";
            default ->
                "status-allowed";
        };
    }

    private String categoriaMostrada(String cat) {
        if (cat == null) {
            return "Unknown";
        }
        if (cat.startsWith(Categoria.BACKGROUND)) {
            return "Background";
        }
        return switch (cat) {
            case "PRODUCTIVO" ->
                "Productive";
            case "DISTRACCION" ->
                "Distraction";
            case "NEUTRAL" ->
                "Neutral";
            case "SIN_CLASIFICAR" ->
                "Unclassified";
            default ->
                cat;
        };
    }

    private String formatearDuracion(long segundos) {
        long h = segundos / 3600;
        long m = (segundos % 3600) / 60;
        long s = segundos % 60;
        if (h > 0) {
            return String.format("%dh %02dm %02ds", h, m, s);
        }
        if (m > 0) {
            return String.format("%dm %02ds", m, s);
        }
        return String.format("%ds", s);
    }
}
