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
import org.appsentinel.domain.port.out.CategoriaRepositoryPort;

import org.appsentinel.domain.port.out.RendimientoSistemaPort;
import org.appsentinel.infrastructure.bootstrap.AppContext;
import oshi.SystemInfo;
import oshi.software.os.OSProcess;
import oshi.software.os.OperatingSystem;

public class PerformanceController implements Initializable, Controllable {

    @FXML
    private TableView<MonitoredAppRow> tablaProcesos;

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

    // 🚀 CORREGIDO: Las columnas ahora consumen estrictamente el tipo MonitoredAppRow
    @FXML
    private TableColumn<MonitoredAppRow, String> colName;
    @FXML
    private TableColumn<MonitoredAppRow, String> colCategory;
    @FXML
    private TableColumn<MonitoredAppRow, Double> colCpu;
    @FXML
    private TableColumn<MonitoredAppRow, Double> colRam;
    @FXML
    private TableColumn<MonitoredAppRow, String> colTime; // 🚀 Cambiado a String para pintar el formato HH:mm:ss limpio

    private RendimientoSistemaPort rendimientoService;
    private CategoriaRepositoryPort categoriasService;
    private OperatingSystem osNativo;
    private Timer timer;
    private XYChart.Series<Number, Number> rxSeries;
    private XYChart.Series<Number, Number> txSeries;
    private int xTick = 0;

    private List<oshi.hardware.NetworkIF> interfacesRed;
    private long totalBytesRxAnterior = 0;
    private long totalBytesTxAnterior = 0;
    private long timestampAnterior = 0;

    @Override
    public void init(AppContext ctx) {
        this.rendimientoService = ctx.rendimiento();
        this.categoriasService = ctx.categorias();
        try {
            this.osNativo = new SystemInfo().getOperatingSystem();

            this.interfacesRed = new SystemInfo().getHardware().getNetworkIFs();
            for (oshi.hardware.NetworkIF net : interfacesRed) {
                net.updateAttributes();
                this.totalBytesRxAnterior += net.getBytesRecv();
                this.totalBytesTxAnterior += net.getBytesSent();
            }
            this.timestampAnterior = System.currentTimeMillis();

        } catch (Exception e) {
            System.err.println("[UI] No se pudo inicializar el acceso nativo a OS en el controlador: " + e.getMessage());
        }
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
        // 🚀 CORREGIDO: Mapeo explícito a las propiedades internas de MonitoredAppRow
        colName.setCellValueFactory(cd -> new SimpleStringProperty(cd.getValue().getName()));
        colCategory.setCellValueFactory(cd -> new SimpleStringProperty(cd.getValue().getType()));

        colCpu.setCellValueFactory(cd -> new SimpleDoubleProperty(cd.getValue().getCpuUsage()).asObject());
        colRam.setCellValueFactory(cd -> new SimpleDoubleProperty(cd.getValue().getMemoryUsage()).asObject());
        colTime.setCellValueFactory(cd -> new SimpleStringProperty(cd.getValue().getActiveTime()));

        // Asignación de factorías visuales personalizadas pasando el control dinámico del color
        colCategory.setCellFactory(col -> new CategoriaBadgeCell());
        colCpu.setCellFactory(col -> new ProgressBarCell());
        colRam.setCellFactory(col -> new ProgressBarCell());

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
        // === 1. Métricas Globales ===
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

        // 2 
        // === 2. Carga Filtrada desde Base de Datos a la Tabla ===
        if (osNativo != null && tablaProcesos != null && categoriasService != null) {
            try {
                List<String> appsProductivas = categoriasService.obtenerAppsPorCategoria("PRODUCTIVO");
                List<String> appsDistraccion = categoriasService.obtenerAppsPorCategoria("DISTRACCION");

                java.util.Set<String> setProductivas = new java.util.HashSet<>();
                if (appsProductivas != null) {
                    for (String app : appsProductivas) {
                        setProductivas.add(app.toLowerCase().replace(".exe", "").trim());
                    }
                }

                java.util.Set<String> setDistraccion = new java.util.HashSet<>();
                if (appsDistraccion != null) {
                    for (String app : appsDistraccion) {
                        setDistraccion.add(app.toLowerCase().replace(".exe", "").trim());
                    }
                }

                List<OSProcess> todosLosProcesos = osNativo.getProcesses(
                        OperatingSystem.ProcessFiltering.VALID_PROCESS,
                        OperatingSystem.ProcessSorting.CPU_DESC,
                        100
                );

                // 🚀 Mapa para agrupar y unificar procesos duplicados por su nombre formateado
                java.util.Map<String, MonitoredAppRow> mapaUnificado = new java.util.LinkedHashMap<>();

                for (OSProcess proc : todosLosProcesos) {
                    String nombreProcesoRaw = proc.getName();
                    if (nombreProcesoRaw == null) {
                        continue;
                    }

                    String nombreProcesoLimpio = nombreProcesoRaw.toLowerCase().replace(".exe", "").trim();

                    boolean esTrabajo = setProductivas.contains(nombreProcesoLimpio);
                    boolean esDistraccion = setDistraccion.contains(nombreProcesoLimpio);

                    if (esTrabajo || esDistraccion) {
                        String tipoUI = esTrabajo ? "WORK" : "DISTRACTION";

                        // Formateamos el nombre respetando mayúsculas/minúsculas originales limpiando el .exe
                        String nombreMostrar = nombreProcesoRaw;
                        if (nombreMostrar.toLowerCase().endsWith(".exe")) {
                            nombreMostrar = nombreMostrar.substring(0, nombreMostrar.length() - 4);
                        }
                        // Aseguramos que empiece con mayúscula (ej: chrome -> Chrome)
                        if (!nombreMostrar.isEmpty()) {
                            nombreMostrar = nombreMostrar.substring(0, 1).toUpperCase() + nombreMostrar.substring(1);
                        }

                        // Cálculo de métricas del proceso actual
                        double cpuProc = proc.getProcessCpuLoadCumulative() * 100.0;
                        if (Double.isNaN(cpuProc)) {
                            cpuProc = 0.0;
                        }

                        double memProc = (proc.getResidentSetSize() / (1024.0 * 1024.0 * 1024.0)) / ramTotalGb * 100.0;
                        long uptimeSegundosActual = proc.getUpTime() / 1000;

                        if (mapaUnificado.containsKey(nombreMostrar)) {
                            // 🔄 Si la app ya existe, recuperamos los datos acumulados usando tus GETTERS reales
                            MonitoredAppRow filaExisting = mapaUnificado.get(nombreMostrar);

                            double cpuAcumulada = filaExisting.getCpuUsage() + cpuProc;
                            double memAcumulada = filaExisting.getMemoryUsage() + memProc;

                            // Comparamos el tiempo activo para mantener el mayor
                            long segundosExistentes = parsearTiempoASegundos(filaExisting.getActiveTime());
                            String tiempoGanador = filaExisting.getActiveTime();

                            if (uptimeSegundosActual > segundosExistentes) {
                                tiempoGanador = formatearTiempo(uptimeSegundosActual);
                            }

                            // ✨ Reemplazamos en el mapa usando tu constructor inmutable
                            MonitoredAppRow filaActualizada = new MonitoredAppRow(
                                    nombreMostrar,
                                    tipoUI,
                                    cpuAcumulada,
                                    memAcumulada,
                                    tiempoGanador
                            );

                            mapaUnificado.put(nombreMostrar, filaActualizada);

                        } else {
                            // ✨ Si es la primera vez que vemos la app, la registramos normalmente
                            String tiempoFormateado = formatearTiempo(uptimeSegundosActual);
                            MonitoredAppRow nuevaFila = new MonitoredAppRow(nombreMostrar, tipoUI, cpuProc, memProc, tiempoFormateado);
                            mapaUnificado.put(nombreMostrar, nuevaFila);
                        }
                    }
                }

                // Enviamos la lista limpia y sin duplicados directos a la tabla de JavaFX
                tablaProcesos.setItems(FXCollections.observableArrayList(mapaUnificado.values()));

            } catch (Exception e) {
                System.err.println("[UI] Error al unificar procesos de la Base de Datos: " + e.getMessage());
            }
        }

        // === 3. Red I/O ===
        long totalBytesRxActual = 0;
        long totalBytesTxActual = 0;

        if (interfacesRed != null) {
            for (oshi.hardware.NetworkIF net : interfacesRed) {
                net.updateAttributes();
                totalBytesRxActual += net.getBytesRecv();
                totalBytesTxActual += net.getBytesSent();
            }
        }

        long timestampActual = System.currentTimeMillis();
        double deltaTiempoSegundos = (timestampActual - timestampAnterior) / 1000.0;
        if (deltaTiempoSegundos <= 0) {
            deltaTiempoSegundos = 2.0;
        }

        double velocidadDescargaMB = ((totalBytesRxActual - totalBytesRxAnterior) / deltaTiempoSegundos) / (1024.0 * 1024.0);
        double velocidadSubidaMB = ((totalBytesTxActual - totalBytesTxAnterior) / deltaTiempoSegundos) / (1024.0 * 1024.0);

        if (velocidadDescargaMB < 0) {
            velocidadDescargaMB = 0.0;
        }
        if (velocidadSubidaMB < 0) {
            velocidadSubidaMB = 0.0;
        }

        totalBytesRxAnterior = totalBytesRxActual;
        totalBytesTxAnterior = totalBytesTxActual;
        timestampAnterior = timestampActual;

        lblNetDown.setText(String.format("%.1f MB/s ⬇", velocidadDescargaMB));
        lblNetUp.setText(String.format("%.1f MB/s ⬆", velocidadSubidaMB));

        rxSeries.getData().add(new XYChart.Data<>(xTick, velocidadDescargaMB));
        txSeries.getData().add(new XYChart.Data<>(xTick, velocidadSubidaMB));

        if (rxSeries.getData().size() > 20) {
            rxSeries.getData().remove(0);
            txSeries.getData().remove(0);
        }
        xTick++;
    }

    /**
     * Convierte los segundos que entrega OSHI a formato de texto "HH:mm:ss"
     * para mostrarlo de forma elegante en la interfaz gráfica.
     */
    private String formatearTiempo(long segundos) {
        return String.format("%02d:%02d:%02d",
                segundos / 3600,
                (segundos % 3600) / 60,
                segundos % 60);
    }

    /**
     * Convierte un String con formato "HH:mm:ss" de vuelta a segundos totales.
     * Se utiliza para comparar los procesos duplicados y conservar el que tenga
     * mayor tiempo activo.
     */
    private long parsearTiempoASegundos(String tiempo) {
        if (tiempo == null || !tiempo.contains(":")) {
            return 0;
        }
        try {
            String[] partes = tiempo.split(":");
            long horas = Long.parseLong(partes[0]);
            long minutos = Long.parseLong(partes[1]);
            long segundos = Long.parseLong(partes[2]);
            return (horas * 3600) + (minutos * 60) + segundos;
        } catch (Exception e) {
            return 0;
        }
    }

    @Override
    public void shutdown() {
        if (timer != null) {
            timer.cancel();
        }
    }
    // =========================================================================
    // CLASES DE SOPORTE PARA CELDAS ADAPTADAS A MONITOREDAPPROW
    // =========================================================================
    // 🚀 CORREGIDO: Evalúa el tipo de App guardada en BD en vez de estados del sistema operativo

    private static class CategoriaBadgeCell extends javafx.scene.control.TableCell<MonitoredAppRow, String> {

        @Override
        protected void updateItem(String item, boolean empty) {
            super.updateItem(item, empty);
            if (empty || item == null) {
                setGraphic(null);
                setText(null);
            } else {
                Label badge = new Label(item);
                badge.getStyleClass().clear();

                if (item.equalsIgnoreCase("WORK")) {
                    badge.getStyleClass().add("badge-work");
                } else {
                    badge.getStyleClass().add("badge-distraction");
                }
                setGraphic(badge);
                setText(null);
            }
        }
    }

    // 🚀 CORREGIDO: Pinta barras Cian o Naranja adaptándose en tiempo real a la categoría de la fila
    private static class ProgressBarCell extends javafx.scene.control.TableCell<MonitoredAppRow, Double> {

        private final HBox container = new HBox(8);
        private final Label text = new Label();
        private final ProgressBar bar = new ProgressBar();

        public ProgressBarCell() {
            bar.getStyleClass().add("micro-bar");
            bar.setPrefWidth(80);
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
                MonitoredAppRow rowApp = getTableRow() != null ? getTableRow().getItem() : null;

                // Reseteamos clases de color dinámicas para evitar bugs visuales por reciclaje de celdas
                bar.getStyleClass().removeAll("micro-bar-cyan", "micro-bar-orange");

                if (rowApp != null && "WORK".equalsIgnoreCase(rowApp.getType())) {
                    bar.getStyleClass().add("micro-bar-cyan");
                } else {
                    bar.getStyleClass().add("micro-bar-orange");
                }

                double progresoFraccion = item / 100.0;
                bar.setProgress(Math.min(1.0, Math.max(0.0, progresoFraccion)));

                text.setText(String.format("%.1f%%", item));
                setGraphic(container);
            }
        }
    }

    // =========================================================================
    // DTO O MODELO DE REPRESENTACIÓN DE FILA PARA LA TABLA
    // =========================================================================
    public static class MonitoredAppRow {

        private final String name;
        private final String type;
        private final double cpuUsage;
        private final double memoryUsage;
        private final String activeTime;

        public MonitoredAppRow(String name, String type, double cpuUsage, double memoryUsage, String activeTime) {
            this.name = name;
            this.type = type;
            this.cpuUsage = cpuUsage;
            this.memoryUsage = memoryUsage;
            this.activeTime = activeTime;
        }

        public String getName() {
            return name;
        }

        public String getType() {
            return type;
        }

        public double getCpuUsage() {
            return cpuUsage;
        }

        public double getMemoryUsage() {
            return memoryUsage;
        }

        public String getActiveTime() {
            return activeTime;
        }
    }
}
