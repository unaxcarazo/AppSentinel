package org.appsentinel.infrastructure.adapter.in.gui.controller;

import javafx.application.Platform;
import javafx.collections.FXCollections;
import javafx.collections.transformation.FilteredList;
import javafx.fxml.FXML;
import javafx.fxml.Initializable;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Scene;
import javafx.scene.control.*;
import javafx.scene.layout.*;
import javafx.scene.paint.Color;
import javafx.stage.Modality;
import javafx.stage.Stage;
import javafx.stage.StageStyle;

import java.net.URL;
import java.util.List;
import java.util.ArrayList;
import java.util.ResourceBundle;
import java.util.stream.Collectors;

// Importaciones de la arquitectura hexagonal de AppSentinel
import org.appsentinel.domain.port.out.KillerPort;
import org.appsentinel.domain.port.out.CategoriaRepositoryPort;
import org.appsentinel.infrastructure.bootstrap.AppContext;

/**
 * FXML Controller class para la sección App Blocker.
 */
public class AppBlockerController implements Initializable, Controllable {

    // NOMBRES CORREGIDOS: Adaptados estrictamente a la restricción CHECK de tu SQL
    private static final String CAT_TRABAJO = "PRODUCTIVO";
    private static final String CAT_OCIO = "DISTRACCION";

    private KillerPort killerService; 
    private CategoriaRepositoryPort categoriaService; 

    @FXML private ListView<String> listaTrabajo;
    @FXML private ListView<String> listaOcio;
    
    @FXML private Button btnAnadir;
    @FXML private Button btnPasarAOcio;
    @FXML private Button btnPasarATrabajo;
    @FXML private AnchorPane rootPane; 

    private String appSeleccionadaModal = null;

    @Override
    public void initialize(URL url, ResourceBundle rb) {
        System.out.println("[GUI] Inicializando AppBlocker y enlazando estilos...");

        // 1. Identificadores de clase para que el CSS aplique los rebordes de selección en la principal
        listaTrabajo.getStyleClass().add("lista-trabajo");
        listaOcio.getStyleClass().add("lista-ocio");

        // 2. Configuración de flechas limpias con sus respectivas clases CSS
        btnPasarAOcio.setText("»");
        btnPasarAOcio.getStyleClass().add("btn-pasar-ocio");

        btnPasarATrabajo.setText("«");
        btnPasarATrabajo.getStyleClass().add("btn-pasar-trabajo");

        // 3. Renderizadores de las listas de la pantalla principal
        listaTrabajo.setCellFactory(lv -> new ListCell<String>() {
            @Override
            protected void updateItem(String item, boolean empty) {
                super.updateItem(item, empty);
                if (empty || item == null) {
                    setText(null);
                    setStyle("-fx-background-color: transparent;"); 
                } else {
                    setText("💻  " + item);
                    setStyle(null); 
                    getStyleClass().add("celda-app-base");
                }
            }
        });

        listaOcio.setCellFactory(lv -> new ListCell<String>() {
            @Override
            protected void updateItem(String item, boolean empty) {
                super.updateItem(item, empty);
                if (empty || item == null) {
                    setText(null);
                    setStyle("-fx-background-color: transparent;");
                } else {
                    setText("🚫  " + item);
                    setStyle(null);
                    getStyleClass().add("celda-app-base");
                }
            }
        });
    }

    @Override
    public void init(AppContext ctx) {
        this.killerService = ctx.killer();
        this.categoriaService = ctx.categorias(); 
        Platform.runLater(this::cargarDatosIniciales);
    }

    private void cargarDatosIniciales() {
        List<String> appsTrabajo = categoriaService.obtenerAppsPorCategoria(CAT_TRABAJO);
        List<String> appsOcio = categoriaService.obtenerAppsPorCategoria(CAT_OCIO);
        listaTrabajo.setItems(FXCollections.observableArrayList(appsTrabajo));
        listaOcio.setItems(FXCollections.observableArrayList(appsOcio));
    }

    /**
     * VENTANA MODAL FLOTANTE - DETECTA EN TIEMPO REAL LAS APPS DE CUALQUIER PC
     */
    @FXML
    private void handleAnadirApp() {
        appSeleccionadaModal = null; 

        List<String> procesosDetectadosEnElPC = new ArrayList<>();

        // ESCANEO DIRECTO Y PURO DE LA MÁQUINA ACTUAL EN TIEMPO REAL
        try {
            ProcessHandle.allProcesses().forEach(p -> {
                String cmd = p.info().command().orElse("");
                if (!cmd.isEmpty()) {
                    int lastSlash = Math.max(cmd.lastIndexOf('/'), cmd.lastIndexOf('\\'));
                    String name = lastSlash != -1 ? cmd.substring(lastSlash + 1) : cmd;
                    String nameLower = name.toLowerCase();

                    if (nameLower.endsWith(".exe")) {
                        procesosDetectadosEnElPC.add(name);
                    }
                }
            });
        } catch (Exception e) {
            System.out.println("[MONITOR] Error accediendo a la API nativa de procesos locales.");
        }

        // Eliminamos duplicados y ordenamos alfabéticamente
        List<String> listaOrdenada = procesosDetectadosEnElPC.stream().distinct().sorted().collect(Collectors.toList());

        Stage popupStage = new Stage();
        popupStage.initModality(Modality.APPLICATION_MODAL);
        popupStage.initStyle(StageStyle.UNDECORATED); 
        popupStage.initStyle(StageStyle.TRANSPARENT); 

        VBox rootModal = new VBox(15);
        rootModal.setPadding(new Insets(20));
        rootModal.getStyleClass().add("modal-raiz");

        HBox cabecera = new HBox();
        cabecera.setAlignment(Pos.CENTER_LEFT);
        Label lblTitulo = new Label("ADD NEW APP TO LIST");
        lblTitulo.getStyleClass().add("modal-titulo");
        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);
        Button btnCerrarX = new Button("✕");
        btnCerrarX.getStyleClass().add("modal-boton-cerrar");
        btnCerrarX.setOnAction(e -> popupStage.close());
        cabecera.getChildren().addAll(lblTitulo, spacer, btnCerrarX);

        HBox cajaBusqueda = new HBox(8);
        cajaBusqueda.setAlignment(Pos.CENTER_LEFT);
        cajaBusqueda.setPadding(new Insets(6, 10, 6, 10));
        cajaBusqueda.getStyleClass().add("modal-caja-busqueda");
        Label lupaIcono = new Label("🔍");
        lupaIcono.setStyle("-fx-text-fill: #969696; -fx-font-size: 14px;"); 
        TextField txtBuscar = new TextField();
        txtBuscar.setPromptText("Search apps installed on your PC...");
        txtBuscar.getStyleClass().add("modal-txt-buscar");
        HBox.setHgrow(txtBuscar, Priority.ALWAYS);
        cajaBusqueda.getChildren().addAll(lupaIcono, txtBuscar);

        ListView<String> listaUI = new ListView<>();
        listaUI.setPrefHeight(260);
        listaUI.setPrefWidth(340);
        listaUI.getStyleClass().add("modal-lista");
        
        listaUI.setCellFactory(lv -> new ListCell<String>() {
            @Override
            protected void updateItem(String item, boolean empty) {
                super.updateItem(item, empty);
                getStyleClass().removeAll("celda-app-base", "celda-trabajo-seleccionada");
                if (empty || item == null) {
                    setText(null);
                    setStyle("-fx-background-color: transparent;");
                } else {
                    setText(item);
                    if (isSelected()) {
                        getStyleClass().add("celda-trabajo-seleccionada");
                    } else {
                        getStyleClass().add("celda-app-base");
                    }
                }
            }
        });

        listaUI.setOnMouseClicked(event -> {
            if (event.getClickCount() == 2 && !listaUI.getSelectionModel().isEmpty()) {
                appSeleccionadaModal = listaUI.getSelectionModel().getSelectedItem();
                popupStage.close();
            }
        });

        FilteredList<String> listaFiltrada = new FilteredList<>(FXCollections.observableArrayList(listaOrdenada), p -> true);
        txtBuscar.textProperty().addListener((obs, viejo, nuevo) -> {
            listaFiltrada.setPredicate(app -> {
                if (nuevo == null || nuevo.isEmpty()) return true;
                return app.toLowerCase().contains(nuevo.toLowerCase());
            });
        });
        listaUI.setItems(listaFiltrada);

        Button btnGuardar = new Button("SAVE & APPLY RULE");
        btnGuardar.setMaxWidth(Double.MAX_VALUE);
        btnGuardar.setPrefHeight(38);
        btnGuardar.getStyleClass().add("modal-btn-guardar");
        
        btnGuardar.setOnAction(e -> {
            appSeleccionadaModal = listaUI.getSelectionModel().getSelectedItem();
            popupStage.close();
        });

        rootModal.getChildren().addAll(cabecera, cajaBusqueda, listaUI, btnGuardar);

        Scene scene = new Scene(rootModal);
        scene.setFill(Color.TRANSPARENT);
        
        // Inyección de estilos CSS
        try {
            String cssPath = getClass().getResource("/styles/appblocker.css").toExternalForm();
            scene.getStylesheets().add(cssPath);
        } catch (Exception ex) {
            try {
                String cssFallback = getClass().getResource("appblocker.css").toExternalForm();
                scene.getStylesheets().add(cssFallback);
            } catch (Exception e) {
                if (rootPane != null && !rootPane.getStylesheets().isEmpty()) {
                    scene.getStylesheets().add(rootPane.getStylesheets().get(0));
                }
            }
        }

        popupStage.setScene(scene);
        popupStage.showAndWait();

        if (appSeleccionadaModal != null && !appSeleccionadaModal.isEmpty()) {
            if (!listaOcio.getItems().contains(appSeleccionadaModal)) {
                listaOcio.getItems().add(appSeleccionadaModal);
                
                try {
                    killerService.cerrarProceso(appSeleccionadaModal); 
                } catch (UnsupportedOperationException uoe) {
                    System.out.println("[WARNING] ProcessKillerAdapter aún no está implementado. El proceso no se cerrará.");
                }
                
                try {
                    categoriaService.guardarCategoria(appSeleccionadaModal, CAT_OCIO);
                } catch (Exception ex) {
                    System.out.println("[ERROR] Fallo al guardar categoría en PostgreSQL.");
                }
            }
        }
    }

    @FXML
    private void handlePasarAOcio() {
        String selected = listaTrabajo.getSelectionModel().getSelectedItem();
        if (selected != null) {
            listaTrabajo.getItems().remove(selected);
            listaOcio.getItems().add(selected);
            try {
                killerService.cerrarProceso(selected);
            } catch (UnsupportedOperationException uoe) {
                System.out.println("[WARNING] ProcessKillerAdapter no implementado.");
            }
            categoriaService.guardarCategoria(selected, CAT_OCIO); 
        }
    }

    @FXML
    private void handlePasarATrabajo() {
        String selected = listaOcio.getSelectionModel().getSelectedItem();
        if (selected != null) {
            listaOcio.getItems().remove(selected);
            listaTrabajo.getItems().add(selected);
            categoriaService.guardarCategoria(selected, CAT_TRABAJO); 
        }
    }
}