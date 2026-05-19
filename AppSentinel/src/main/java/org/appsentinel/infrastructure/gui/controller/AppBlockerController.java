/*
 * Click nbfs://nbhost/SystemFileSystem/Templates/Licenses/license-default.txt to change this license
 * Click nbfs://nbhost/SystemFileSystem/Templates/javafx/FXMLController.java to edit this template
 */
package org.appsentinel.infrastructure.gui.controller;

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

// Importaciones de la arquitectura hexagonal de AppSentinel
import org.appsentinel.domain.port.out.KillerPort;
import org.appsentinel.domain.port.out.CategoriaRepositoryPort;
import org.appsentinel.infrastructure.config.AppContext;

/**
 * FXML Controller class para la sección App Blocker. Gestiona de forma reactiva
 * la interfaz de usuario comunicándose con el Dominio.
 *
 * @author DAW1
 */
public class AppBlockerController implements Initializable, Controllable {

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

    @Override
    public void initialize(URL url, ResourceBundle rb) {
        // Inicialización puramente estética en el arranque de la vista
        System.out.println("[GUI] AppBlocker inicializado visualmente.");
    }

    /**
     * REGLA OBLIGATORIA: Método de entrada que recibe el grafo de dependencias.
     * Vincula el controlador con la capa del dominio de la aplicación.
     */
    @Override
    public void init(AppContext ctx) {
        System.out.println("[CONTROLLER] Configurando AppBlocker con el contexto global...");

        // Inyección de los servicios reales extraídos del record de configuración global
        this.killerService = ctx.killer();
        this.categoriaService = ctx.categorias();

        // REGLA OBLIGATORIA: Carga delegada al hilo prioritario de JavaFX Application Thread
        Platform.runLater(() -> {
            cargarDatosIniciales();
        });
    }

    /**
     * Recupera las listas del almacén de persistencia real y actualiza la
     * vista.
     */
    private void cargarDatosIniciales() {
        System.out.println("[DOMINIO] Preparando la carga real de datos...");

        // Obtenemos los conjuntos de datos persistidos en la base de datos según su rol
        java.util.List<String> appsTrabajo = categoriaService.obtenerAppsPorCategoria("Trabajo");
        java.util.List<String> appsOcio = categoriaService.obtenerAppsPorCategoria("Ocio");

        // Enlazamos las colecciones devueltas con las ListViews correspondientes de JavaFX
        listaTrabajo.setItems(FXCollections.observableArrayList(appsTrabajo));
        listaOcio.setItems(FXCollections.observableArrayList(appsOcio));

        System.out.println("[GUI] Listas sincronizadas correctamente con la base de datos.");
    }

    /**
     * Acción para registrar y bloquear inmediatamente una nueva aplicación.
     */
    @FXML
    private void handleAnadirApp() {
        String appName = txtNuevaApp.getText().trim();

        if (!appName.isEmpty()) {
            // Actualización de la lista visual
            listaOcio.getItems().add(appName);

            // Envío de la orden de interrupción al sistema operativo mediante el Killer
            killerService.cerrarProceso(appName,0);

            // Guardado persistente del cambio de categoría en la base de datos
            categoriaService.guardarCategoria(appName, "Ocio");

            txtNuevaApp.clear();
            System.out.println("[ACTION] App '" + appName + "' añadida a Ocio, proceso abortado y guardado persistido.");
        }
    }

    /**
     * Transfiere una aplicación de la lista permitida (Trabajo) a la bloqueada
     * (Ocio).
     */
    @FXML
    private void handlePasarAOcio() {
        String selected = listaTrabajo.getSelectionModel().getSelectedItem();
        if (selected != null) {
            // Movimiento visual entre componentes
            listaTrabajo.getItems().remove(selected);
            listaOcio.getItems().add(selected);

            // Forzar el cierre inmediato del proceso recién penalizado
            killerService.cerrarProceso(selected,0);

            // Sincronizar de manera permanente la nueva categoría en el repositorio
            categoriaService.guardarCategoria(selected, "Ocio");
            System.out.println("[ACTION] '" + selected + "' reclasificada como Ocio y bloqueada.");
        }
    }

    /**
     * Revoca el bloqueo de una aplicación transfiriéndola a la lista blanca
     * (Trabajo).
     */
    @FXML
    private void handlePasarATrabajo() {
        String selected = listaOcio.getSelectionModel().getSelectedItem();
        if (selected != null) {
            // Movimiento visual entre componentes
            listaOcio.getItems().remove(selected);
            listaTrabajo.getItems().add(selected);

            // Sincronizar el indulto de la aplicación en el repositorio de base de datos
            // AQUÍ SE CORRIGIÓ EL ERROR: 'selected' bien escrito juntos sin espacios
            categoriaService.guardarCategoria(selected, "Trabajo");
            System.out.println("[ACTION] '" + selected + "' indultada y reclasificada como Trabajo.");
        }
    }
}
