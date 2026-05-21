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

import org.appsentinel.domain.model.ProcesoInfo;
import org.appsentinel.domain.model.SystemMetrics;
import org.appsentinel.domain.port.out.RendimientoSistemaPort;
import org.appsentinel.infrastructure.bootstrap.AppContext;

public class PerformanceController implements Initializable, Controllable {

    @FXML
    private Label lblCpuLoad;
    @FXML
    private Label lblCpuFreq;
    @FXML
    private Label lblCpuCores;
    @FXML
    private ProgressBar progressCpu;

    @FXML
    private Label lblRamUsage;
    @FXML
    private Label lblRamSub;
    @FXML
    private Label lblRamTotal;
    @FXML
    private ProgressBar progressRam;

    @FXML
    private Label lblNetDown;
    @FXML
    private Label lblNetUp;
    @FXML
    private AreaChart<Number, Number> netChart;

    @FXML
    private TextField txtSearchProcess;

    @FXML
    private TableView<ProcesoInfo> tableProcesses;
    @FXML
    private TableColumn<ProcesoInfo, String> colName;
    @FXML
    private TableColumn<ProcesoInfo, String> colCategory;
    @FXML
    private TableColumn<ProcesoInfo, Double> colCpu;
    @FXML
    private TableColumn<ProcesoInfo, Double> colRam;
    @FXML
    private TableColumn<ProcesoInfo, Long> colTime;

    private RendimientoSistemaPort rendimientoService;
    private Timer timer;
    private XYChart.Series<Number, Number> rxSeries;
    private XYChart.Series<Number, Number> txSeries;
    private int xTick = 0;

    @Override
    public void init(AppContext ctx) {
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

        colTime.setCellFactory(col -> new TiempoActivoCell());
        colCategory.setCellFactory(col -> new CategoriaBadgeCell());
        colCpu.setCellFactory(col -> new ProgressBarCell("micro-bar-cyan"));
        colRam.setCellFactory(col -> new ProgressBarCell("micro-bar-orange"));

        txtSearchProcess.textProperty().addListener((obs, oldVal, newVal) -> {
            System.out.println("[BUSCADOR] Filtrado deshabilitado temporalmente: " + newVal);
        });
    }

    private void startRepeatingUpdate() {
        timer = new Timer(true);
        timer.scheduleAtFixedRate(new TimerTask() {
            @Override
            public void run() {
                if (rendimientoService != null) {
                    Platform.runLater(() -> updateUIDirecto());
                }
            }
        }, 0, 2000);
    }

    private void updateUIDirecto() {
        double cpuGlobal = rendimientoService.getCpuPorcentajeGlobal();
        double ramPorcentaje = rendimientoService.getRamPorcentaje();
        double ramUsadaGb = rendimientoService.getRamUsadaMb() / 1024.0;
        double ramTotalGb = rendimientoService.getRamTotalMb() / 1024.0;

        lblCpuLoad.setText(String.format("%.0f%%", cpuGlobal));
        lblCpuFreq.setText("2.5 GHz");
        lblCpuCores.setText("4 Cores");
        progressCpu.setProgress(cpuGlobal / 100.0);

        lblRamUsage.setText(String.format("%.0f%%", ramPorcentaje));
        lblRamSub.setText(String.format("%.1f GB In Use", ramUsadaGb));
        lblRamTotal.setText(String.format("%.1f GB Total", ramTotalGb));
        progressRam.setProgress(ramPorcentaje / 100.0);

        lblNetDown.setText("0.0 MB/s ⬇");
        lblNetUp.setText("0.0 MB/s ⬆");

        rxSeries.getData().add(new XYChart.Data<>(xTick, 0.0));
        txSeries.getData().add(new XYChart.Data<>(xTick, 0.0));
        if (rxSeries.getData().size() > 20) {
            rxSeries.getData().remove(0);
            txSeries.getData().remove(0);
        }
        xTick++;
    }

    public void stop() {
        if (timer != null) {
            timer.cancel();
        }
    }

    // =========================================================================
    // CLASES DE SOPORTE PARA CELDAS
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
                if (h > 0) {
                    setText(String.format("%dh %dm", h, m));
                } else if (m > 0) {
                    setText(String.format("%dm %ds", m, sec));
                } else {
                    setText(String.format("%ds", sec));
                }
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

            // 🌟 ELIMINADO EL INLINE STYLE DE AQUÍ PARA PASAR EL CONTROL TOTAL AL CSS vuestro
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

                if (p != null && p.status() != null
                        && (p.status().equalsIgnoreCase("RUNNING") || p.status().equalsIgnoreCase("WORK"))) {
                    bar.getStyleClass().add("micro-bar-cyan");
                } else {
                    bar.getStyleClass().add("micro-bar-orange");
                }

                text.setText(String.format("%.1f%%", item));
            }
        }
    }
}
