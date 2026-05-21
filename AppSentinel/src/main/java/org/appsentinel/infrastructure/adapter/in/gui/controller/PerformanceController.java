/*
 * Click nbfs://nbhost/SystemFileSystem/Templates/Licenses/license-default.txt to change this license
 * Click nbfs://nbhost/SystemFileSystem/Templates/javafx/FXMLController.java to edit this template
 */
package org.appsentinel.infrastructure.adapter.in.gui.controller;

import java.net.URL;
import java.util.ResourceBundle;
import java.util.Timer;
import java.util.TimerTask;
import java.util.List;
import javafx.application.Platform;
import javafx.beans.property.SimpleDoubleProperty;
import javafx.beans.property.SimpleLongProperty;
import javafx.beans.property.SimpleStringProperty;
import javafx.collections.FXCollections;
import javafx.fxml.FXML;
import javafx.fxml.Initializable;
import javafx.scene.chart.AreaChart;
import javafx.scene.chart.XYChart;
import javafx.scene.control.Label;
import javafx.scene.control.ProgressBar;
import javafx.scene.control.TableColumn;
import javafx.scene.control.TableView;
import javafx.scene.control.TextField;
import javafx.scene.layout.HBox;

// ⚠️ ASEGÚRATE DE QUE ESTAS RUTAS SEAN LAS DE TU PROYECTO:
import org.appsentinel.domain.model.ProcesoInfo;
import org.appsentinel.domain.model.SystemMetrics;
import org.appsentinel.domain.port.out.RendimientoSistemaPort;
import org.appsentinel.infrastructure.bootstrap.AppContext;

public class PerformanceController implements Initializable, Controllable {

    @FXML private Label lblCpuLoad;
    @FXML private Label lblCpuFreq;
    @FXML private Label lblCpuCores;
    @FXML private ProgressBar progressCpu;

    @FXML private Label lblRamUsage;
    @FXML private Label lblRamSub;
    @FXML private Label lblRamTotal;
    @FXML private ProgressBar progressRam;

    @FXML private Label lblNetDown;
    @FXML private Label lblNetUp;
    @FXML private AreaChart<Number, Number> netChart;

    @FXML private TextField txtSearchProcess;

    @FXML private TableView<ProcesoInfo> tableProcesses;
    @FXML private TableColumn<ProcesoInfo, String> colName;
    @FXML private TableColumn<ProcesoInfo, String> colCategory;
    @FXML private TableColumn<ProcesoInfo, Double> colCpu;
    @FXML private TableColumn<ProcesoInfo, Double> colRam;
    @FXML private TableColumn<ProcesoInfo, Long> colTime;

    private RendimientoSistemaPort rendimientoService;
    private Timer timer;
    private XYChart.Series<Number, Number> rxSeries;
    private XYChart.Series<Number, Number> txSeries;
    private int xTick = 0;

    @Override
    public void init(AppContext ctx) {
        // ⚠️ Si da error aquí, revisa en AppContext cómo se llama el método (ej: ctx.getPerformanceService())
        this.rendimientoService = ctx.rendimiento(); 
        Platform.runLater(this::startRepeatingUpdate);
    }

    @Override
    public void initialize(URL url, ResourceBundle rb) {
        setupTable();
        setupChart();
    }

    private void setupChart() {
        rxSeries = new XYChart.Series<>();
        rxSeries.setName("Descarga (RX)");
        
        txSeries = new XYChart.Series<>();
        txSeries.setName("Subida (TX)");
        
        netChart.getData().addAll(rxSeries, txSeries);
    }

    private void setupTable() {
        colName.setCellValueFactory(cd -> new SimpleStringProperty(cd.getValue().nombre()));
        colCategory.setCellValueFactory(cd -> new SimpleStringProperty(cd.getValue().status()));
        colCpu.setCellValueFactory(cd -> new SimpleDoubleProperty(cd.getValue().cpuUsage()).asObject());
        colRam.setCellValueFactory(cd -> new SimpleDoubleProperty(cd.getValue().memoryUsage()).asObject());
        colTime.setCellValueFactory(cd -> new SimpleLongProperty(cd.getValue().upTime()).asObject());

        // Asignación limpia usando las nuevas clases estáticas (Adiós errores de Override)
        colTime.setCellFactory(col -> new TiempoActivoCell());
        colCategory.setCellFactory(col -> new CategoriaBadgeCell());
        colCpu.setCellFactory(col -> new ProgressBarCell("micro-bar-cyan"));
        colRam.setCellFactory(col -> new ProgressBarCell("micro-bar-orange"));
        
        txtSearchProcess.textProperty().addListener((obs, oldVal, newVal) -> {
            if (rendimientoService != null) {
                updateTableData(rendimientoService.);
            }
        });
    }

    private void startRepeatingUpdate() {
        timer = new Timer(true);
        timer.scheduleAtFixedRate(new TimerTask() {
            @Override
            public void run() {
                if (rendimientoService != null) {
                    SystemMetrics metrics = rendimientoService.;
                    if (metrics != null) {
                        Platform.runLater(() -> updateUI(metrics));
                    }
                }
            }
        }, 0, 2000);
    }

    private void updateUI(SystemMetrics metrics) {
        lblCpuLoad.setText(String.format("%.0f%%", metrics.cpuTotalUsage()));
        lblCpuFreq.setText(String.format("%.1f GHz", metrics.cpuFrequencyGHz()));
        lblCpuCores.setText(String.format("%d Cores", metrics.logicalCores()));
        progressCpu.setProgress(0.5);

        lblRamUsage.setText(String.format("%.0f%%", metrics.getRamUsagePercent()));
        lblRamSub.setText(String.format("%.1f GB In Use", metrics.ramUsedGB()));
        lblRamTotal.setText(String.format("%.1f GB Total", metrics.ramTotalGB()));
        progressRam.setProgress(0.5);

        lblNetDown.setText(String.format("%.1f MB/s ⬇", metrics.downloadMBps()));
        lblNetUp.setText(String.format("%.1f MB/s ⬆", metrics.uploadMBps()));
        
        rxSeries.getData().add(new XYChart.Data<>(xTick, metrics.downloadMBps()));
        txSeries.getData().add(new XYChart.Data<>(xTick, metrics.uploadMBps()));
        if (rxSeries.getData().size() > 20) {
            rxSeries.getData().remove(0);
            txSeries.getData().remove(0);
        }
        xTick++;

        updateTableData(metrics);
    }

    private void updateTableData(SystemMetrics metrics) {
        if (metrics == null || metrics.topProcesses() == null) return;
        
        String search = txtSearchProcess.getText();
        List<ProcesoInfo> list = metrics.topProcesses();
        
        if (search != null && !search.isEmpty()) {
            String cleanSearch = search.toLowerCase().trim();
            list = list.stream()
                .filter(p -> p.nombre() != null && p.nombre().toLowerCase().contains(cleanSearch))
                .collect(java.util.stream.Collectors.toList());
        }
        tableProcesses.setItems(FXCollections.observableArrayList(list));
    }

    public void stop() {
        if (timer != null) {
            timer.cancel();
        }
    }

    // =========================================================================
    // CLASES DE SOPORTE PARA CELDAS (Evitan problemas de compilación)
    // =========================================================================

    private static class TiempoActivoCell extends javafx.scene.control.TableCell<ProcesoInfo, Long> {
        @Override
        protected void updateItem(Long item, boolean empty) {
            super.updateItem(item, empty);
            if (empty || item == null) {
                setText(null);
            } else {
                long s = item / 1000;
                long h = s / 3600;
                long m = (s % 3600) / 60;
                long sec = s % 60;
                if (h > 0) setText(String.format("%dh %dm", h, m));
                else if (m > 0) setText(String.format("%dm %ds", m, sec));
                else setText(String.format("%ds", sec));
            }
        }
    }

    private static class CategoriaBadgeCell extends javafx.scene.control.TableCell<ProcesoInfo, String> {
        @Override
        protected void updateItem(String item, boolean empty) {
            super.updateItem(item, empty);
            if (empty || item == null) {
                setGraphic(null);
                setText(null);
            } else {
                Label badge = new Label(item);
                if (item.equalsIgnoreCase("RUNNING") || item.equalsIgnoreCase("WORK")) {
                    badge.getStyleClass().add("badge-work");
                } else {
                    badge.getStyleClass().add("badge-distraction");
                }
                setGraphic(badge);
                setText(null);
            }
        }
    }

    private static class ProgressBarCell extends javafx.scene.control.TableCell<ProcesoInfo, Double> {
        private final HBox container = new HBox(8);
        private final Label text = new Label();
        private final ProgressBar bar = new ProgressBar();

        public ProgressBarCell(String barClass) {
            bar.getStyleClass().addAll("micro-bar", barClass);
            bar.setPrefWidth(80);
            text.setStyle("-fx-text-fill: white; -fx-font-size: 11px; -fx-font-weight: bold;");
            text.setPrefWidth(45);
            container.setAlignment(javafx.geometry.Pos.CENTER_LEFT);
            container.getChildren().addAll(text, bar);
        }

        @Override
        protected void updateItem(Double item, boolean empty) {
            super.updateItem(item, empty);
            if (empty || item == null) {
                setGraphic(null);
                setText(null);
            } else {
                ProcesoInfo p = getTableRow() != null ? getTableRow().getItem() : null;
                
                bar.getStyleClass().removeAll("micro-bar-cyan", "micro-bar-orange");
                
                if (p != null && p.status() != null && 
                   (p.status().equalsIgnoreCase("RUNNING") || p.status().equalsIgnoreCase("WORK"))) {
                    bar.getStyleClass().add("micro-bar-cyan");
                } else {
                    bar.getStyleClass().add("micro-bar-orange");
                }

                text.setText(String.format("%.1f%%", item));
                bar.setProgress(Math.max(0.0, Math.min(1.0, item / 100.0)));
                setGraphic(container);
                setText(null);
            }
        }
    }
}