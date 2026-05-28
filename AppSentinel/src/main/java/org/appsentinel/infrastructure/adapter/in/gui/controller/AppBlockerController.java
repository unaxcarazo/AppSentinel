package org.appsentinel.infrastructure.adapter.in.gui.controller;

import javafx.application.Platform;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.collections.transformation.FilteredList;
import javafx.fxml.FXML;
import javafx.fxml.Initializable;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.ListView;
import javafx.scene.control.TextField;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.stage.Modality;
import javafx.stage.Stage;
import javafx.stage.StageStyle;

import org.appsentinel.domain.model.Categoria;
import org.appsentinel.domain.port.out.CategoriaRepositoryPort;
import org.appsentinel.domain.service.DistractionDetector;
import org.appsentinel.domain.service.TimeTrackingService;
import org.appsentinel.infrastructure.bootstrap.AppContext;

import java.io.IOException;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * AppBlockerController: Gestión de clasificación de apps y webs.
 *
 * MAPEO UI → DOMINIO:
 *   Trabajo → PRODUCTIVO | Ocio → DISTRACCION
 *
 * BUSCADOR: txtNuevaApp filtra ambas listas en tiempo real vía FilteredList.
 *
 * MODAL AÑADIR APP:
 *   Al pulsar "+ ADD NEW APP" se abre un Stage modal programático con:
 *   - Pestaña Desktop: apps de escritorio escaneadas (procesos + menú inicio)
 *   - Pestaña Web: dominios detectados automáticamente por TimeTrackingService
 *   - Botón "SAVE & APPLY RULE" que persiste en BD
 *
 *   FIX CSS: El root del modal es StackPane (Region) para que los estilos
 *   de borde y fondo se apliquen correctamente. El CSS se carga en el root
 *   antes de que los nodos hijos se creen, garantizando aplicación de estilos.
 */
public class AppBlockerController implements Initializable, Controllable {

    private static final Logger LOGGER = Logger.getLogger(AppBlockerController.class.getName());

    private final ExecutorService ioExecutor = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "AppBlocker-IO");
        t.setDaemon(true);
        return t;
    });

    private CategoriaRepositoryPort categoriaService;
    private TimeTrackingService trackingService;
    private DistractionDetector detector;

    @FXML private TextField txtNuevaApp;
    @FXML private ListView<String> listaTrabajo;
    @FXML private ListView<String> listaOcio;
    @FXML private Button btnAnadir;
    @FXML private Button btnPasarAOcio;
    @FXML private Button btnPasarATrabajo;

    // Listas base (origen de verdad)
    private final ObservableList<String> obsTrabajo = FXCollections.observableArrayList();
    private final ObservableList<String> obsOcio = FXCollections.observableArrayList();

    // Listas filtradas para el buscador
    private FilteredList<String> filtradoTrabajo;
    private FilteredList<String> filtradoOcio;

    @Override
    public void initialize(URL url, ResourceBundle rb) {
        if (listaTrabajo != null) {
            filtradoTrabajo = new FilteredList<>(obsTrabajo, s -> true);
            listaTrabajo.setItems(filtradoTrabajo);
        }
        if (listaOcio != null) {
            filtradoOcio = new FilteredList<>(obsOcio, s -> true);
            listaOcio.setItems(filtradoOcio);
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
            configurarBuscador();
        });
    }

    /**
     * Inyecta la hoja de estilos CSS al nodo raíz de la escena principal.
     */
    private void cargarCssDinamico() {
        if (btnAnadir == null || btnAnadir.getScene() == null) return;
        var root = btnAnadir.getScene().getRoot();
        var recurso = getClass().getResource("/styles/appblocker.css");
        if (recurso == null) {
            LOGGER.log(Level.WARNING, "[CSS] appblocker.css no encontrado.");
            return;
        }
        String cssUrl = recurso.toExternalForm();
        if (!root.getStylesheets().contains(cssUrl)) {
            root.getStylesheets().add(cssUrl);
            LOGGER.log(Level.INFO, "[CSS] appblocker.css inyectado en escena principal.");
        }
    }

    /**
     * Configura el buscador txtNuevaApp para filtrar ambas listas en tiempo real.
     */
    private void configurarBuscador() {
        if (txtNuevaApp == null || filtradoTrabajo == null || filtradoOcio == null) return;

        txtNuevaApp.textProperty().addListener((obs, old, nuevo) -> {
            String filtro = nuevo == null ? "" : nuevo.trim().toLowerCase();
            if (filtro.isEmpty()) {
                filtradoTrabajo.setPredicate(s -> true);
                filtradoOcio.setPredicate(s -> true);
            } else {
                filtradoTrabajo.setPredicate(s -> s.toLowerCase().contains(filtro));
                filtradoOcio.setPredicate(s -> s.toLowerCase().contains(filtro));
            }
        });
    }

    private void cargarDatosIniciales() {
        if (categoriaService == null) return;
        CompletableFuture.supplyAsync(() -> {
            var trabajo = categoriaService.obtenerAppsPorCategoria(Categoria.PRODUCTIVO);
            var ocio = categoriaService.obtenerAppsPorCategoria(Categoria.DISTRACCION);
            return new AbstractMap.SimpleEntry<>(trabajo, ocio);
        }, ioExecutor).whenComplete((result, ex) -> Platform.runLater(() -> {
            if (ex != null) {
                LOGGER.log(Level.SEVERE, "[GUI] Error cargando categorías", ex);
                return;
            }
            obsTrabajo.setAll(result.getKey());
            obsOcio.setAll(result.getValue());
            LOGGER.log(Level.INFO, "[GUI] Sincronizado: {0} productivas, {1} distracciones.",
                new Object[]{obsTrabajo.size(), obsOcio.size()});
        }));
    }

    // =====================================================================
    // MODAL: Añadir nueva app / dominio web
    // =====================================================================

    @FXML
    private void handleAnadirApp() {
        mostrarModalAnadirApp();
    }

    private void mostrarModalAnadirApp() {
        Stage modal = new Stage();
        modal.initModality(Modality.APPLICATION_MODAL);
        modal.initOwner(btnAnadir.getScene().getWindow());
        modal.initStyle(StageStyle.UNDECORATED);

        // --- Root: StackPane (Region) aplica bordes y fondo CSS correctamente ---
        StackPane root = new StackPane();
        root.getStyleClass().add("modal-raiz");
        root.setPrefSize(420, 580);

        // --- Cargar CSS en el root ANTES de crear nodos hijos ---
        var cssRecurso = getClass().getResource("/styles/appblocker.css");
        if (cssRecurso != null) {
            String cssUrl = cssRecurso.toExternalForm();
            if (!root.getStylesheets().contains(cssUrl)) {
                root.getStylesheets().add(cssUrl);
            }
        }

        // --- Contenido interno ---
        VBox contenido = new VBox(12);
        contenido.setPadding(new Insets(20));
        contenido.setAlignment(Pos.TOP_CENTER);

        // --- Header ---
        HBox header = new HBox(10);
        header.setAlignment(Pos.CENTER_LEFT);
        Label titulo = new Label("+  ADD NEW APP TO LIST");
        titulo.getStyleClass().add("modal-titulo");
        HBox.setHgrow(titulo, Priority.ALWAYS);
        Button btnCerrar = new Button("×");
        btnCerrar.getStyleClass().add("modal-boton-cerrar");
        btnCerrar.setOnAction(e -> modal.close());
        header.getChildren().addAll(titulo, btnCerrar);

        // --- Toggle Desktop / Web ---
        HBox toggleBox = new HBox(0);
        toggleBox.setAlignment(Pos.CENTER);
        Button btnTabDesktop = new Button("Desktop Apps");
        Button btnTabWeb = new Button("Web Domains");
        btnTabDesktop.getStyleClass().add("modal-btn-guardar");
        btnTabWeb.getStyleClass().add("modal-btn-guardar");
        btnTabDesktop.setStyle(btnTabDesktop.getStyle() + "-fx-background-color: #00e5ff; -fx-text-fill: #121214;");
        toggleBox.getChildren().addAll(btnTabDesktop, btnTabWeb);

        // --- Búsqueda ---
        TextField busqueda = new TextField();
        busqueda.setPromptText("Search...");
        busqueda.getStyleClass().add("modal-txt-buscar");

        // --- Lista ---
        ListView<String> listaApps = new ListView<>();
        listaApps.getStyleClass().add("modal-lista");
        listaApps.setPrefHeight(280);
        VBox.setVgrow(listaApps, Priority.ALWAYS);

        // --- Botón guardar ---
        Button btnGuardar = new Button("SAVE & APPLY RULE");
        btnGuardar.getStyleClass().add("modal-btn-guardar");
        btnGuardar.setMaxWidth(Double.MAX_VALUE);
        btnGuardar.setDisable(true);

        contenido.getChildren().addAll(
            header, toggleBox, busqueda, listaApps, btnGuardar
        );
        root.getChildren().add(contenido);

        // --- Scene ---
        Scene scene = new Scene(root);
        modal.setScene(scene);

        // --- Estado del modal ---
        final String[] modoActivo = {"desktop"};
        List<String> memoriaApps = new ArrayList<>();
        List<String> memoriaDominios = new ArrayList<>();
        listaApps.setPlaceholder(new Label("Scanning..."));

        // --- Cargar desktop ---
        CompletableFuture.supplyAsync(this::escanearAppsUsuario, ioExecutor)
            .whenComplete((apps, ex) -> Platform.runLater(() -> {
                if (ex != null) {
                    LOGGER.log(Level.SEVERE, "[MODAL] Error escaneando apps", ex);
                    return;
                }
                memoriaApps.addAll(apps);
                if ("desktop".equals(modoActivo[0])) {
                    listaApps.setItems(FXCollections.observableArrayList(apps));
                    listaApps.setPlaceholder(null);
                }
            }));

        // --- Cargar web (desde TimeTrackingService) ---
        CompletableFuture.runAsync(() -> {
            if (trackingService != null) {
                Set<String> dominios = trackingService.obtenerDominiosDetectados();
                Platform.runLater(() -> {
                    memoriaDominios.addAll(dominios);
                    if ("web".equals(modoActivo[0])) {
                        listaApps.setItems(FXCollections.observableArrayList(dominios));
                        listaApps.setPlaceholder(null);
                    }
                });
            }
        }, ioExecutor);

        // --- Toggle handler ---
        Runnable mostrarDesktop = () -> {
            modoActivo[0] = "desktop";
            listaApps.setItems(FXCollections.observableArrayList(memoriaApps));
            btnTabDesktop.setStyle(btnTabDesktop.getStyle() + "-fx-background-color: #00e5ff; -fx-text-fill: #121214;");
            btnTabWeb.setStyle("");
        };
        Runnable mostrarWeb = () -> {
            modoActivo[0] = "web";
            listaApps.setItems(FXCollections.observableArrayList(memoriaDominios));
            btnTabWeb.setStyle(btnTabWeb.getStyle() + "-fx-background-color: #00e5ff; -fx-text-fill: #121214;");
            btnTabDesktop.setStyle("");
        };
        btnTabDesktop.setOnAction(e -> mostrarDesktop.run());
        btnTabWeb.setOnAction(e -> mostrarWeb.run());

        // --- Filtrado ---
        busqueda.textProperty().addListener((obs, old, nuevo) -> {
            String filtro = nuevo.trim().toLowerCase();
            List<String> base = "web".equals(modoActivo[0]) ? memoriaDominios : memoriaApps;
            if (filtro.isEmpty()) {
                listaApps.setItems(FXCollections.observableArrayList(base));
            } else {
                List<String> filtradas = base.stream()
                    .filter(a -> a.toLowerCase().contains(filtro))
                    .collect(Collectors.toList());
                listaApps.setItems(FXCollections.observableArrayList(filtradas));
            }
        });

        // --- Habilitar guardar ---
        listaApps.getSelectionModel().selectedItemProperty().addListener((obs, old, nuevo) -> {
            btnGuardar.setDisable(nuevo == null || nuevo.isBlank());
        });

        // --- Guardar ---
        btnGuardar.setOnAction(e -> {
            String seleccion = listaApps.getSelectionModel().getSelectedItem();
            if (seleccion == null || seleccion.isBlank()) return;

            String appName = seleccion.trim().toLowerCase();
            if (!obsOcio.contains(appName)) obsOcio.add(appName);

            persistirCategoria(appName, Categoria.DISTRACCION);
            LOGGER.log(Level.INFO, "[ACTION] ''{0}'' añadido como DISTRACCION ({1}).",
                new Object[]{appName, modoActivo[0]});
            modal.close();
        });

        modal.show();
    }

    // =====================================================================
    // Escaner de apps instaladas (procesos usuario + menú inicio)
    // =====================================================================

    private List<String> escanearAppsUsuario() {
        Set<String> apps = new TreeSet<>(String.CASE_INSENSITIVE_ORDER);

        // 1. Procesos en ejecución de usuario actual
        try {
            ProcessHandle.allProcesses()
                .filter(ph -> ph.info().command().isPresent())
                .filter(ph -> !esProcesoSistema(ph))
                .forEach(ph -> {
                    String cmd = ph.info().command().get();
                    Path p = Paths.get(cmd);
                    String nombre = p.getFileName().toString();
                    if (nombre.toLowerCase().endsWith(".exe")) {
                        apps.add(nombre.substring(0, nombre.length() - 4).toLowerCase());
                    } else {
                        apps.add(nombre.toLowerCase());
                    }
                });
        } catch (Exception e) {
            LOGGER.log(Level.WARNING, "[SCAN] Error escaneando procesos: {0}", e.getMessage());
        }

        // 2. Menú Inicio - usuario
        String appData = System.getenv("APPDATA");
        if (appData != null) {
            apps.addAll(escanearAccesosDirectos(Paths.get(appData, "Microsoft", "Windows", "Start Menu", "Programs")));
        }

        // 3. Menú Inicio - sistema (all users)
        String programData = System.getenv("PROGRAMDATA");
        if (programData != null) {
            apps.addAll(escanearAccesosDirectos(Paths.get(programData, "Microsoft", "Windows", "Start Menu", "Programs")));
        }

        // 4. Desktop
        String userProfile = System.getenv("USERPROFILE");
        if (userProfile != null) {
            apps.addAll(escanearAccesosDirectos(Paths.get(userProfile, "Desktop")));
        }

        return new ArrayList<>(apps);
    }

    private Set<String> escanearAccesosDirectos(Path directorio) {
        Set<String> nombres = new HashSet<>();
        if (!Files.exists(directorio) || !Files.isDirectory(directorio)) {
            return nombres;
        }
        try (Stream<Path> paths = Files.walk(directorio, 3)) {
            paths.filter(p -> {
                String s = p.toString().toLowerCase();
                return s.endsWith(".lnk") || s.endsWith(".url");
            }).forEach(p -> {
                String nombre = p.getFileName().toString();
                nombre = nombre.replaceAll("(?i)\\.(lnk|url)$", "").toLowerCase().trim();
                if (!nombre.isEmpty()) {
                    nombres.add(nombre);
                }
            });
        } catch (IOException e) {
            LOGGER.log(Level.FINE, "[SCAN] Error leyendo {0}: {1}",
                new Object[]{directorio, e.getMessage()});
        }
        return nombres;
    }

    private boolean esProcesoSistema(ProcessHandle ph) {
        return ph.info().user()
            .map(u -> {
                String lower = u.toLowerCase();
                return lower.contains("nt authority")
                    || lower.equals("system")
                    || lower.contains("local service")
                    || lower.contains("network service")
                    || lower.contains("root");
            })
            .orElse(false);
    }

    // =====================================================================
    // Handlers de intercambio entre listas
    // =====================================================================

    @FXML
    private void handlePasarAOcio() {
        String selected = listaTrabajo.getSelectionModel().getSelectedItem();
        if (selected == null || categoriaService == null) return;

        obsTrabajo.remove(selected);
        if (!obsOcio.contains(selected)) obsOcio.add(selected);

        persistirCategoria(selected, Categoria.DISTRACCION);
        LOGGER.log(Level.INFO, "[ACTION] ''{0}'' → DISTRACCION.", selected);
    }

    @FXML
    private void handlePasarATrabajo() {
        String selected = listaOcio.getSelectionModel().getSelectedItem();
        if (selected == null || categoriaService == null) return;

        obsOcio.remove(selected);
        if (!obsTrabajo.contains(selected)) obsTrabajo.add(selected);

        persistirCategoria(selected, Categoria.PRODUCTIVO);
        LOGGER.log(Level.INFO, "[ACTION] ''{0}'' → PRODUCTIVO.", selected);
    }

    // =====================================================================
    // Persistencia
    // =====================================================================

    private void persistirCategoria(String nombreApp, String categoriaDominio) {
        CompletableFuture.runAsync(() -> {
            categoriaService.guardarCategoria(nombreApp, categoriaDominio);
        }, ioExecutor).whenComplete((v, ex) -> Platform.runLater(() -> {
            if (ex != null) {
                LOGGER.log(Level.SEVERE, "[GUI] Error persistiendo: " + nombreApp, ex);
                return;
            }
            if (detector != null) detector.reclasificar(nombreApp, categoriaDominio);
            if (trackingService != null) {
                trackingService.sincronizarCategoria("SYS|" + nombreApp, categoriaDominio);
                trackingService.sincronizarCategoria("WEB|" + nombreApp, categoriaDominio);
            }
        }));
    }

    @Override
    public void shutdown() {
        ioExecutor.shutdown();
        LOGGER.log(Level.INFO, "[GUI] AppBlocker cerrado.");
    }
}
