package org.appsentinel.infrastructure.adapter.in.gui.controller;

import javafx.application.Platform;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.fxml.FXML;
import javafx.fxml.Initializable;
import javafx.scene.control.ComboBox;
import javafx.scene.control.TableView;
import javafx.scene.control.TextField;
import org.appsentinel.domain.model.Registro;
import org.appsentinel.domain.port.out.RegistroRepositoryPort;
import org.appsentinel.infrastructure.bootstrap.AppContext;

import java.net.URL;
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
 * historial. Soporta auto-refresh asíncrono y filtros combinados por texto y
 * categoría.
 */
public class UsageHistoryController implements Initializable, Controllable {

    private static final Logger LOGGER = Logger.getLogger(UsageHistoryController.class.getName());

    @FXML
    private TableView<Registro> tablaHistorial;
    @FXML
    private TextField txtSearch;
    @FXML
    private ComboBox<String> comboFilter;

    /**
     * Lista maestra que almacena los datos reales bajados de PostgreSQL.
     */
    private List<Registro> listaCompletaMaster = new ArrayList<>();

    /**
     * Lista observable vinculada directamente a la TableView de JavaFX.
     */
    private final ObservableList<Registro> listaVisualHistorial = FXCollections.observableArrayList();

    private RegistroRepositoryPort repositorio;
    private ScheduledExecutorService uiScheduler;

    /**
     * Inicialización nativa de JavaFX. Vincula la tabla y configura los
     * listeners de los filtros.
     */
    @Override
    public void initialize(URL url, ResourceBundle rb) {
        if (tablaHistorial != null) {
            tablaHistorial.setItems(listaVisualHistorial);
        }

        if (comboFilter != null) {
            comboFilter.getItems().clear();
            comboFilter.getItems().addAll("All Activities", "Work Only", "Distractions Only");
            comboFilter.setValue("All Activities");

            // Reaccionar cuando cambie el desplegable
            comboFilter.valueProperty().addListener((obs, viejo, nuevo) -> aplicarFiltrosCombinados());
        }

        if (txtSearch != null) {
            // Reaccionar cuando el usuario escriba en el buscador
            txtSearch.textProperty().addListener((obs, viejo, nuevo) -> aplicarFiltrosCombinados());
        }
    }

    @Override
    public void init(AppContext ctx) {
        this.repositorio = ctx.repositorio();

        // 1. Carga inicial inmediata de datos
        recargarDatosDesdeBd();

        // 2. Arranque seguro del planificador para auto-refresh (cada 5 segundos)
        if (uiScheduler == null || uiScheduler.isShutdown()) {
            uiScheduler = Executors.newSingleThreadScheduledExecutor(r -> {
                Thread t = new Thread(r, "UsageHistory-UI-Scheduler");
                t.setDaemon(true);
                return t;
            });

            uiScheduler.scheduleAtFixedRate(() -> {
                Platform.runLater(this::recargarDatosDesdeBd);
            }, 5, 5, TimeUnit.SECONDS);
        }
    }

    /**
     * Trae los datos frescos de la BD y actualiza la lista maestra.
     */
    private void recargarDatosDesdeBd() {
        if (repositorio != null) {
            try {
                List<Registro> registrosDeHoy = repositorio.obtenerTodosHoy(System.getProperty("user.name"));

                // Actualizamos la lista maestra en memoria
                this.listaCompletaMaster = (registrosDeHoy != null) ? registrosDeHoy : new ArrayList<>();

                // Aplicamos los filtros actuales para no romper lo que el usuario esté buscando o filtrando
                aplicarFiltrosCombinados();

            } catch (Exception e) {
                LOGGER.log(Level.SEVERE, "Error al consultar la base de datos desde el scheduler", e);
            }
        }
    }

    /**
     * Filtra la lista maestra según el texto y el combobox, y vuelca el
     * resultado en la TableView.
     */
    private void aplicarFiltrosCombinados() {
        if (listaCompletaMaster == null) {
            return;
        }

        // Obtener valores de los componentes de forma segura
        String textoBusqueda = (txtSearch != null) ? txtSearch.getText().toLowerCase().trim() : "";
        String opcionFiltro = (comboFilter != null) ? comboFilter.getValue() : "All Activities";

        // Filtrado mediante Stream API
        List<String> categoriasTrabajo = List.of("TRABAJO", "PRODUCTIVIDAD");

        List<Registro> listaFiltrada = listaCompletaMaster.stream()
                .filter(item -> item.getNombreActividad() != null
                && item.getNombreActividad().toLowerCase().contains(textoBusqueda))
                .filter(item -> {
                    if ("Work Only".equals(opcionFiltro)) {
                        return item.getCategoria() != null && categoriasTrabajo.contains(item.getCategoria().toUpperCase());
                    } else if ("Distractions Only".equals(opcionFiltro)) {
                        return "DISTRACCION".equalsIgnoreCase(item.getCategoria());
                    }
                    return true; // "All Activities"
                })
                .collect(Collectors.toList());

        // PASO CRÍTICO: Limpieza y repoblación de la tabla visual
        listaVisualHistorial.clear();
        listaVisualHistorial.addAll(listaFiltrada);
    }

    @Override
    public void shutdown() {
        if (uiScheduler != null && !uiScheduler.isShutdown()) {
            uiScheduler.shutdown();
            LOGGER.log(Level.INFO, "[UI-HISTORIAL] Auto-refresh scheduler detenido.");
        }
    }
}
