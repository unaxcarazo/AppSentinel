package org.appsentinel.infrastructure.gui.controller;

import org.appsentinel.domain.model.Registro;
import org.appsentinel.domain.port.out.RegistroRepositoryPort;

import javafx.fxml.FXML;
import javafx.geometry.Pos;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.TextField;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.stream.Collectors;
import org.appsentinel.infrastructure.config.AppContext;

public class UsageHistoryController implements Controllable {

    private RegistroRepositoryPort registroRepository;

    public UsageHistoryController() {
        // Constructor vacío para inyección diferida
    }

    @FXML
    private TextField txtSearch;
    @FXML
    private ComboBox<String> comboFilter;
    @FXML
    private VBox vboxTableRows;

    private List<Registro> listaCompletaMaster;

    @FXML
    public void initialize() {
        comboFilter.getItems().clear();
        comboFilter.getItems().addAll("All Activities", "Work Only", "Distractions Only");
        comboFilter.setValue("All Activities");

        txtSearch.textProperty().addListener((observable, oldValue, newValue) -> aplicarFiltrosCombinados());
        comboFilter.valueProperty().addListener((observable, oldValue, newValue) -> aplicarFiltrosCombinados());
    }

    private void aplicarFiltrosCombinados() {
        if (listaCompletaMaster == null) {
            return;
        }

        String textoBusqueda = txtSearch.getText().toLowerCase().trim();
        String opcionFiltro = comboFilter.getValue();

        List<Registro> listaFiltrada = listaCompletaMaster.stream()
                .filter(item -> item.getNombreActividad().toLowerCase().contains(textoBusqueda))
                .filter(item -> {
                    if ("Work Only".equals(opcionFiltro)) {
                        return "TRABAJO".equals(item.getCategoria());
                    } else if ("Distractions Only".equals(opcionFiltro)) {
                        return "DISTRACCION".equals(item.getCategoria());
                    }
                    return true;
                })
                .collect(Collectors.toList());

        actualizarTabla(listaFiltrada);
    }

    private void actualizarTabla(List<Registro> lista) {
        vboxTableRows.getChildren().clear();

        // 🌟 LA CLAVE: Asegura que el contenedor de filas tenga acceso directo al CSS
        if (vboxTableRows.getScene() != null && !vboxTableRows.getStylesheets().contains("/styles/usagehistory.css")) {
            vboxTableRows.getStylesheets().add(getClass().getResource("/styles/usagehistory.css").toExternalForm());
        }

        if (lista == null || lista.isEmpty()) {
            Label lblEmpty = new Label("No records found matches criteria.");
            lblEmpty.getStyleClass().add("table-empty-label");
            HBox emptyRow = new HBox(lblEmpty);
            emptyRow.setAlignment(Pos.CENTER);
            emptyRow.setPadding(new javafx.geometry.Insets(30));
            vboxTableRows.getChildren().add(emptyRow);
            return;
        }

        DateTimeFormatter timeFormatter = DateTimeFormatter.ofPattern("HH:mm:ss");

        for (Registro item : lista) {
            HBox row = new HBox();
            row.getStyleClass().add("table-row-custom");
            row.setAlignment(Pos.CENTER_LEFT);

            // Bloque Nombre + Categoría
            VBox nameBlock = new VBox(4);
            nameBlock.setPrefWidth(250);
            nameBlock.setMinWidth(250);
            nameBlock.setMaxWidth(250);
            nameBlock.setAlignment(Pos.CENTER_LEFT);

            Label lblName = new Label(item.getNombreActividad());
            lblName.getStyleClass().add("app-name-label");

            Label lblCat = new Label(item.getCategoria().toUpperCase());
            if ("TRABAJO".equalsIgnoreCase(item.getCategoria())) {
                lblCat.getStyleClass().add("badge-work");
            } else {
                lblCat.getStyleClass().add("badge-distraction");
            }
            nameBlock.getChildren().addAll(lblName, lblCat);

            // Separadores elásticos
            Region spacer1 = new Region();
            HBox.setHgrow(spacer1, Priority.ALWAYS);
            Region spacer2 = new Region();
            HBox.setHgrow(spacer2, Priority.ALWAYS);
            Region spacer3 = new Region();
            HBox.setHgrow(spacer3, Priority.ALWAYS);

            // Hora de Inicio
            String horaFormateada = (item.getFechaRegistro() != null) ? item.getFechaRegistro().format(timeFormatter) : "00:00:00";
            Label lblStartTime = new Label(horaFormateada);
            lblStartTime.getStyleClass().add("table-cell-custom");
            lblStartTime.setPrefWidth(120);
            lblStartTime.setMinWidth(120);
            lblStartTime.setAlignment(Pos.CENTER);

            // Duración
            Label lblDuration = new Label(formatearTiempo(item.getDuracionSeg()));
            lblDuration.getStyleClass().add("table-cell-custom");
            lblDuration.setPrefWidth(120);
            lblDuration.setMinWidth(120);
            lblDuration.setAlignment(Pos.CENTER);

            // Estado (Aquí se asignan las clases para Verde y Rojo)
            boolean esBloqueado = "BLOCKED".equalsIgnoreCase(item.getDetalle())
                    || ("DISTRACCION".equalsIgnoreCase(item.getCategoria()) && item.getDuracionSeg() == 0);

            Label lblStatus = new Label(esBloqueado ? "BLOCKED" : "ALLOWED");
            lblStatus.setPrefWidth(100);
            lblStatus.setMinWidth(100);
            lblStatus.setAlignment(Pos.CENTER);

            // Limpiamos estilos anteriores y añadimos la clase correspondiente
            lblStatus.getStyleClass().removeAll("status-allowed", "status-blocked");
            if (esBloqueado) {
                lblStatus.getStyleClass().add("status-blocked");
            } else {
                lblStatus.getStyleClass().add("status-allowed");
            }

            row.getChildren().addAll(nameBlock, spacer1, lblStartTime, spacer2, lblDuration, spacer3, lblStatus);
            vboxTableRows.getChildren().add(row);
        }
    }

    private String formatearTiempo(long segundosTotales) {
        if (segundosTotales <= 0) {
            return "00h 00m";
        }
        long horas = segundosTotales / 3600;
        long minutes = (segundosTotales % 3600) / 60;
        return String.format("%02dh %02dm", horas, minutes);
    }

    @Override
    public void init(AppContext ctx) {
        this.registroRepository = ctx.repositorio();
        this.listaCompletaMaster = registroRepository.obtenerHistorialCompleto();
        actualizarTabla(this.listaCompletaMaster);
        System.out.println("⏳ UsageHistory cargado con éxito en el ecosistema del grupo usando AppContext.");
    }
}
