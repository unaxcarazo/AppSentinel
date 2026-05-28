package org.appsentinel.infrastructure.adapter.in.gui.controller;

import java.awt.Desktop;
import java.io.File;
import java.io.IOException;
import java.net.URL;
import java.time.LocalDate;
import java.util.List;
import java.util.ResourceBundle;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;
import javafx.application.Platform;
import javafx.fxml.FXML;
import javafx.geometry.Pos;
import javafx.scene.chart.BarChart;
import javafx.scene.chart.XYChart;
import javafx.scene.control.Label;
import javafx.scene.control.ProgressBar;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import org.appsentinel.domain.model.Registro;
import org.appsentinel.domain.port.out.RegistroRepositoryPort;
import javafx.fxml.Initializable;
import javafx.scene.control.Button;
import javafx.scene.control.ScrollPane;
import org.appsentinel.infrastructure.bootstrap.AppContext;

/**
 * DashboardController: Gestiona el panel principal de métricas de
 * productividad. Rango del eje X optimizado dinámicamente según la hora de
 * inicio de actividad.
 */
public class DashboardController implements Initializable, Controllable {

    private static final double LIMITE_PRODUCTIVO_SEG = 5 * 3600; // 5 horas en segundos
    private static final double LIMITE_DISTRACCION_SEG = 1 * 3600; // 1 hora en segundos

    private volatile long ultimaActualizacionUi = 0;
    private static final long MIN_MS_ENTRE_ACTUALIZACIONES = 2000; // 2 segundos

    private ScheduledExecutorService uiScheduler;
    private RegistroRepositoryPort repository;
    private String usuarioActual;

    private LocalDate fechaConsultada = LocalDate.now();

    @FXML
    private Region scoreFill;
    @FXML
    private VBox vboxTopWork;
    @FXML
    private VBox vboxTopDistractions;
    @FXML
    private ProgressBar progressWorkCard;
    @FXML
    private ProgressBar progressDistractionCard;
    @FXML
    private Label lblTotalHours;
    @FXML
    private Label lblWorkTime;
    @FXML
    private Label lblDistractionTime;
    @FXML
    private Label lblCount;
    @FXML
    private Label lblScorePercent;
    @FXML
    private Button btnAtras;
    @FXML
    private Button btnAdelante;
    @FXML
    private Label lblFecha;
    @FXML
    private Label lblScoreMessage;
    @FXML
    private BarChart<String, Number> barChartActivity;
    @FXML
    private ScrollPane rootPane;

    public DashboardController() {
    }

    @Override
    public void initialize(URL url, ResourceBundle rb) {
        if (rootPane != null) {
            URL cssURL = this.getClass().getResource("/styles/dashboard.css");
            if (cssURL == null) {
                System.err.println("❌ ERROR CRÍTICO: ¡El archivo CSS no se encuentra!");
            } else {
                rootPane.getStylesheets().add(cssURL.toExternalForm());
            }
        }

        Platform.runLater(() -> {
            if (vboxTopWork != null && vboxTopDistractions != null) {
                if (vboxTopWork.getParent() instanceof Region && vboxTopDistractions.getParent() instanceof Region) {
                    Region tarjetaTrabajo = (Region) vboxTopWork.getParent();
                    Region tarjetaDistracciones = (Region) vboxTopDistractions.getParent();
                    if (tarjetaTrabajo.getParent() instanceof HBox) {
                        HBox filaMedia = (HBox) tarjetaTrabajo.getParent();
                        tarjetaTrabajo.prefWidthProperty().bind(filaMedia.widthProperty().divide(2.0).subtract(15));
                        tarjetaDistracciones.prefWidthProperty().bind(filaMedia.widthProperty().divide(2.0).subtract(15));
                    }
                }
            }

            if (barChartActivity != null && scoreFill != null) {
                if (barChartActivity.getParent() instanceof Region) {
                    Region tarjetaGrafica = (Region) barChartActivity.getParent();
                    tarjetaGrafica.setPrefHeight(240.0);
                    tarjetaGrafica.setMinHeight(240.0);
                    if (scoreFill.getParent() != null && scoreFill.getParent().getParent() instanceof Region) {
                        Region tarjetaScore = (Region) scoreFill.getParent().getParent();
                        tarjetaScore.setPrefHeight(240.0);
                        tarjetaScore.setMinHeight(240.0);
                    }
                }
            }
        });

        this.uiScheduler = Executors.newSingleThreadScheduledExecutor(runnable -> {
            Thread t = new Thread(runnable, "Dashboard-UI-Scheduler");
            t.setDaemon(true);
            return t;
        });

        this.uiScheduler.scheduleAtFixedRate(this::actualizarVistaSegura, 5, 5, TimeUnit.SECONDS);

        btnAtras.setOnAction(event -> {
            fechaConsultada = fechaConsultada.minusDays(1);
            actualizarPantallaPorFecha();
        });

        btnAdelante.setOnAction(event -> {
            if (fechaConsultada.isBefore(LocalDate.now())) {
                fechaConsultada = fechaConsultada.plusDays(1);
                actualizarPantallaPorFecha();
            }
        });
    }

    @Override
    public void init(AppContext ctx) {
        this.repository = ctx.repositorio();
        this.usuarioActual = System.getProperty("user.name");
        if (this.usuarioActual == null) {
            this.usuarioActual = "DAW1";
        }
        recargarDatosDesdeBd();
    }

    @FXML
    private void handleDownloadReport() {
        try {
            File htmlFile = new File("DelayLog.html");
            if (htmlFile.exists()) {
                Desktop.getDesktop().browse(htmlFile.toURI());
            }
        } catch (IOException e) {
            System.err.println("Error al abrir reporte: " + e.getMessage());
        }
    }

    private String sanitizarTextoLargo(String texto, int maxCaracteres) {
        if (texto == null) {
            return "";
        }
        return texto.length() <= maxCaracteres ? texto : texto.substring(0, maxCaracteres) + "...";
    }

    private void actualizarRankingTrabajo(List<Registro> topWorkApps) {
        vboxTopWork.getChildren().clear();
        if (topWorkApps == null || topWorkApps.isEmpty()) {
            Label lblNoData = new Label("No hay registros aún");
            lblNoData.getStyleClass().add("log-subtext");
            vboxTopWork.getChildren().add(lblNoData);
            return;
        }

        int pos = 1;
        for (Registro app : topWorkApps) {
            double progreso = (double) app.getDuracionSeg() / LIMITE_PRODUCTIVO_SEG;
            if (progreso > 1.0) {
                progreso = 1.0;
            }

            ProgressBar bar = new ProgressBar(progreso);
            bar.setMaxWidth(Double.MAX_VALUE);
            bar.setMinWidth(100);
            HBox.setHgrow(bar, Priority.ALWAYS);
            bar.getStyleClass().add("progress-work-thin");

            HBox row = new HBox();
            row.getStyleClass().add("ranking-row");
            row.setAlignment(Pos.CENTER_LEFT);
            row.setSpacing(15);

            VBox infoContainer = new VBox();
            infoContainer.setSpacing(5);
            HBox.setHgrow(infoContainer, Priority.ALWAYS);

            HBox topInfo = new HBox();
            topInfo.setAlignment(Pos.CENTER_LEFT);

            Label name = new Label(sanitizarTextoLargo(app.getNombreActividad(), 40));
            name.getStyleClass().add("app-name");

            Region spacer = new Region();
            HBox.setHgrow(spacer, Priority.ALWAYS);

            Label time = new Label(formatearTiempo(app.getDuracionSeg()));
            time.getStyleClass().add("app-time");

            topInfo.getChildren().addAll(name, spacer, time);
            infoContainer.getChildren().addAll(topInfo, bar);

            Label lblPos = new Label("#" + pos);
            lblPos.getStyleClass().add("ranking-pos");

            row.getChildren().addAll(lblPos, infoContainer);
            vboxTopWork.getChildren().add(row);
            pos++;
        }
    }

    private void actualizarRankingDistracciones(List<Registro> topDistractionApps) {
        vboxTopDistractions.getChildren().clear();
        if (topDistractionApps == null || topDistractionApps.isEmpty()) {
            Label lblNoData = new Label("No hay registros aún");
            lblNoData.getStyleClass().add("log-subtext");
            vboxTopDistractions.getChildren().add(lblNoData);
            return;
        }

        int pos = 1;
        for (Registro app : topDistractionApps) {
            double progreso = (double) app.getDuracionSeg() / LIMITE_DISTRACCION_SEG;
            boolean excedido = progreso >= 1.0;
            if (progreso > 1.0) {
                progreso = 1.0;
            }

            ProgressBar bar = new ProgressBar(progreso);
            bar.setMaxWidth(Double.MAX_VALUE);
            HBox.setHgrow(bar, Priority.ALWAYS);

            if (excedido) {
                bar.getStyleClass().add("progress-bar-danger");
            } else {
                bar.getStyleClass().add("progress-distraction-thin");
            }

            HBox row = new HBox();
            row.getStyleClass().add("ranking-row");
            row.setAlignment(Pos.CENTER_LEFT);
            row.setSpacing(15);

            VBox infoContainer = new VBox();
            infoContainer.setSpacing(5);
            HBox.setHgrow(infoContainer, Priority.ALWAYS);

            HBox topInfo = new HBox();
            topInfo.setAlignment(Pos.CENTER_LEFT);

            Label name = new Label(sanitizarTextoLargo(app.getNombreActividad(), 40));
            name.getStyleClass().add("app-name");

            Region spacer = new Region();
            HBox.setHgrow(spacer, Priority.ALWAYS);

            Label time = new Label(formatearTiempo(app.getDuracionSeg()));
            time.getStyleClass().add("app-time");

            topInfo.getChildren().addAll(name, spacer, time);
            infoContainer.getChildren().addAll(topInfo, bar);

            Label lblPos = new Label("#" + pos);
            lblPos.getStyleClass().add("ranking-pos");

            row.getChildren().addAll(lblPos, infoContainer);
            vboxTopDistractions.getChildren().add(row);
            pos++;
        }
    }

    private void actualizarCards(List<Registro> registros) {
        if (registros == null || registros.isEmpty()) {
            lblWorkTime.setText("00h 00m");
            lblDistractionTime.setText("00h 00m");
            lblTotalHours.setText("00h 00m");
            lblCount.setText("0");
            lblScorePercent.setText("0%");
            if (lblScoreMessage != null) {
                lblScoreMessage.setText("");
            }
            if (progressWorkCard != null) {
                progressWorkCard.setProgress(0.0);
            }
            if (progressDistractionCard != null) {
                progressDistractionCard.setProgress(0.0);
            }
            return;
        }

        long totalSegundosProductivo = 0;
        long totalSegundosDistraccion = 0;

        for (Registro r : registros) {
            if (r == null) {
                continue;
            }
            String cat = r.getCategoria() != null ? r.getCategoria().trim().toUpperCase() : "";

            if ("PRODUCTIVO".equals(cat)) {
                totalSegundosProductivo += r.getDuracionSeg();
            } else if ("DISTRACCION".equals(cat)) {
                totalSegundosDistraccion += r.getDuracionSeg();
            }
        }

        long totalSegundos = totalSegundosProductivo + totalSegundosDistraccion;

        lblWorkTime.setText(formatearTiempo(totalSegundosProductivo));
        lblDistractionTime.setText(formatearTiempo(totalSegundosDistraccion));
        lblTotalHours.setText(formatearTiempo(totalSegundos));
        lblCount.setText(String.valueOf(registros.size()));

        if (progressWorkCard != null) {
            double progTrabajo = (double) totalSegundosProductivo / LIMITE_PRODUCTIVO_SEG;
            progressWorkCard.setProgress(Math.max(0.0, Math.min(1.0, progTrabajo)));
        }
        if (progressDistractionCard != null) {
            double progDistraccion = (double) totalSegundosDistraccion / LIMITE_DISTRACCION_SEG;
            progressDistractionCard.setProgress(Math.max(0.0, Math.min(1.0, progDistraccion)));
        }
    }

    private String formatearTiempo(long segundosTotal) {
        long horas = segundosTotal / 3600;
        long minutes = (segundosTotal % 3600) / 60;
        return String.format("%02dh %02dm", horas, minutes);
    }

    private void actualizarPantallaPorFecha() {
        if (lblFecha == null) {
            return;
        }

        if (fechaConsultada.equals(LocalDate.now())) {
            lblFecha.setText("Hoy");
        } else if (fechaConsultada.equals(LocalDate.now().minusDays(1))) {
            lblFecha.setText("Ayer");
        } else {
            java.time.format.DateTimeFormatter formatter = java.time.format.DateTimeFormatter.ofPattern("dd MMM, yyyy");
            lblFecha.setText(fechaConsultada.format(formatter));
        }

        if (repository != null && usuarioActual != null) {
            List<Registro> registrosDelDia = repository.findByUsuarioAndFecha(usuarioActual, fechaConsultada);

            List<Registro> topTrabajo = registrosDelDia.stream()
                    .filter(r -> r != null && "PRODUCTIVO".equalsIgnoreCase(r.getCategoria() != null ? r.getCategoria().trim() : ""))
                    .collect(Collectors.groupingBy(Registro::getNombreActividad, Collectors.summingLong(Registro::getDuracionSeg)))
                    .entrySet().stream()
                    .map(entry -> {
                        Registro reg = new Registro();
                        reg.setNombreActividad(entry.getKey());
                        reg.setDuracionSeg(entry.getValue());
                        reg.setCategoria("PRODUCTIVO");
                        return reg;
                    })
                    .sorted((r1, r2) -> Long.compare(r2.getDuracionSeg(), r1.getDuracionSeg()))
                    .limit(5)
                    .collect(Collectors.toList());

            List<Registro> topDistracciones = registrosDelDia.stream()
                    .filter(r -> r != null && "DISTRACCION".equalsIgnoreCase(r.getCategoria() != null ? r.getCategoria().trim() : ""))
                    .collect(Collectors.groupingBy(Registro::getNombreActividad, Collectors.summingLong(Registro::getDuracionSeg)))
                    .entrySet().stream()
                    .map(entry -> {
                        Registro reg = new Registro();
                        reg.setNombreActividad(entry.getKey());
                        reg.setDuracionSeg(entry.getValue());
                        reg.setCategoria("DISTRACCION");
                        return reg;
                    })
                    .sorted((r1, r2) -> Long.compare(r2.getDuracionSeg(), r1.getDuracionSeg()))
                    .limit(5)
                    .collect(Collectors.toList());

            actualizarCards(registrosDelDia);
            actualizarRankingTrabajo(topTrabajo);
            actualizarRankingDistracciones(topDistracciones);
            actualizarGraficoReal(registrosDelDia);
            updateProductivityScore(registrosDelDia);
        }
    }

    private void actualizarGraficoReal(List<Registro> registros) {
        if (registros == null || registros.isEmpty()) {
            Platform.runLater(() -> barChartActivity.getData().clear());
            return;
        }

        double[] minutosProductivoPorHora = new double[24];
        double[] minutosDistraccionPorHora = new double[24];
        double maxMinutos = 0;

        // Inicializamos los extremos para encontrar el rango de uso real
        int minHora = 24;
        int maxHora = -1;

        for (Registro r : registros) {
            if (r == null || r.getFechaRegistro() == null) {
                continue;
            }

            int hora = r.getFechaRegistro().getHour();
            if (hora < 0 || hora > 23) {
                continue;
            }

            // Guardamos dinámicamente cuál es la primera y última hora con datos
            if (hora < minHora) {
                minHora = hora;
            }
            if (hora > maxHora) {
                maxHora = hora;
            }

            double minutos = r.getDuracionSeg() / 60.0;
            if (minutos <= 0) {
                continue;
            }

            String cat = r.getCategoria() != null ? r.getCategoria().trim().toUpperCase() : "";

            if ("PRODUCTIVO".equals(cat)) {
                minutosProductivoPorHora[hora] += minutos;
            } else if ("DISTRACCION".equals(cat)) {
                minutosDistraccionPorHora[hora] += minutos;
            }
        }

        // Si no se procesaron horas válidas, limpiamos y salimos
        if (maxHora == -1 || minHora == 24) {
            Platform.runLater(() -> barChartActivity.getData().clear());
            return;
        }

        XYChart.Series<String, Number> seriesWork = new XYChart.Series<>();
        seriesWork.setName("Trabajo");

        XYChart.Series<String, Number> seriesDist = new XYChart.Series<>();
        seriesDist.setName("Distracción");

        // Construimos los datos del gráfico acotados estrictamente entre [minHora y maxHora]
        for (int h = minHora; h <= maxHora; h++) {
            double tWork = minutosProductivoPorHora[h];
            double tDist = minutosDistraccionPorHora[h];

            if (tWork > maxMinutos) {
                maxMinutos = tWork;
            }
            if (tDist > maxMinutos) {
                maxMinutos = tDist;
            }

            String horaLabel = String.format("%02d:00", h);
            seriesWork.getData().add(new XYChart.Data<>(horaLabel, tWork));
            seriesDist.getData().add(new XYChart.Data<>(horaLabel, tDist));
        }

        final double topeY = (maxMinutos > 0) ? Math.ceil(maxMinutos / 30.0) * 30.0 : 30.0;
        final int horaInicio = minHora;
        final int horaTope = maxHora;

        Platform.runLater(() -> {
            barChartActivity.setAnimated(false);
            barChartActivity.getData().clear();

            if (barChartActivity.getXAxis() instanceof javafx.scene.chart.CategoryAxis) {
                javafx.scene.chart.CategoryAxis xAxis = (javafx.scene.chart.CategoryAxis) barChartActivity.getXAxis();
                javafx.collections.ObservableList<String> categoriasDinamicas = javafx.collections.FXCollections.observableArrayList();

                // El eje X ahora solo renderiza las horas que tienen registros reales
                for (int h = horaInicio; h <= horaTope; h++) {
                    categoriasDinamicas.add(String.format("%02d:00", h));
                }
                xAxis.setCategories(categoriasDinamicas);
            }

            if (barChartActivity.getYAxis() instanceof javafx.scene.chart.NumberAxis) {
                javafx.scene.chart.NumberAxis yAxis = (javafx.scene.chart.NumberAxis) barChartActivity.getYAxis();
                yAxis.setAutoRanging(false);
                yAxis.setLowerBound(0);
                yAxis.setUpperBound(topeY);
                yAxis.setTickUnit(30);
            }

            barChartActivity.getData().addAll(seriesWork, seriesDist);
        });
    }

    public void updateProductivityScore(List<Registro> registros) {
        int porcentajeFinal = calcularPorcentajeProductividad(registros);

        Platform.runLater(() -> {
            lblScorePercent.setText(porcentajeFinal + "%");

            String mensajeEstado;
            String colorHex;

            if (porcentajeFinal >= 80) {
                mensajeEstado = "EXCELENT WORK!";
                colorHex = "#00FF7F";
            } else if (porcentajeFinal >= 60) {
                mensajeEstado = "GOOD JOB! KEEP IT UP.";
                colorHex = "#FFA500";
            } else if (porcentajeFinal >= 40) {
                mensajeEstado = "YOU CAN DO BETTER.";
                colorHex = "#FFA500";
            } else if (porcentajeFinal > 0) {
                mensajeEstado = "TOO MANY DISTRACTIONS!";
                colorHex = "#F00C26";
            } else {
                mensajeEstado = "NO DATA YET.";
                colorHex = "#888888";
            }

            if (lblScoreMessage != null) {
                lblScoreMessage.setText(mensajeEstado);
                lblScoreMessage.setStyle("-fx-text-fill: " + colorHex + "; -fx-font-weight: bold;");
            }

            double maxHeight = 160.0;
            double factorProgreso = porcentajeFinal / 100.0;
            if (scoreFill != null) {
                scoreFill.setPrefHeight(maxHeight * factorProgreso);
            }
        });
    }

    private int calcularPorcentajeProductividad(List<Registro> registros) {
        if (registros == null || registros.isEmpty()) {
            return 0;
        }

        double segundosProductivo = 0;
        double segundosDistraccion = 0;

        for (Registro r : registros) {
            if (r == null) {
                continue;
            }

            String cat = r.getCategoria() != null ? r.getCategoria().trim().toUpperCase() : "";

            if ("PRODUCTIVO".equals(cat)) {
                segundosProductivo += r.getDuracionSeg();
            } else if ("DISTRACCION".equals(cat)) {
                segundosDistraccion += r.getDuracionSeg();
            }
        }

        double minutesProductivo = segundosProductivo / 60.0;
        double minutesDistraccion = segundosDistraccion / 60.0;
        double minutesTotales = minutesProductivo + minutesDistraccion;

        if (minutesTotales <= 0) {
            return 0;
        }

        double resultado = (minutesProductivo * 100.0) / minutesTotales;
        return Math.max(0, Math.min(100, (int) Math.round(resultado)));
    }

    private void actualizarVistaSegura() {
        if (!fechaConsultada.equals(LocalDate.now())) {
            return;
        }

        long ahora = System.currentTimeMillis();
        if (ahora - ultimaActualizacionUi < MIN_MS_ENTRE_ACTUALIZACIONES) {
            return;
        }
        ultimaActualizacionUi = telemetryAhora();

        Platform.runLater(this::recargarDatosDesdeBd);
    }

    private long telemetryAhora() {
        return System.currentTimeMillis();
    }

    private void recargarDatosDesdeBd() {
        if (repository != null && usuarioActual != null) {
            System.out.println("Cargando información optimizada desde la base de datos...");

            List<Registro> topTrabajo = repository.obtenerTopTrabajo(usuarioActual, 5);
            List<Registro> topDistracciones = repository.obtenerTopDistracciones(usuarioActual, 5);
            List<Registro> actividad = repository.obtenerActividadHoy(usuarioActual);

            if ((topTrabajo == null || topTrabajo.isEmpty()) && actividad != null && !actividad.isEmpty()) {
                topTrabajo = actividad.stream()
                        .filter(r -> r != null && "PRODUCTIVO".equalsIgnoreCase(r.getCategoria() != null ? r.getCategoria().trim() : ""))
                        .collect(Collectors.groupingBy(Registro::getNombreActividad, Collectors.summingLong(Registro::getDuracionSeg)))
                        .entrySet().stream()
                        .map(entry -> {
                            Registro reg = new Registro();
                            reg.setNombreActividad(entry.getKey());
                            reg.setDuracionSeg(entry.getValue());
                            reg.setCategoria("PRODUCTIVO");
                            return reg;
                        })
                        .sorted((r1, r2) -> Long.compare(r2.getDuracionSeg(), r1.getDuracionSeg()))
                        .limit(5)
                        .collect(Collectors.toList());
            }

            actualizarCards(actividad);
            actualizarRankingTrabajo(topTrabajo);
            actualizarRankingDistracciones(topDistracciones);
            actualizarGraficoReal(actividad);
            updateProductivityScore(actividad);
        }
    }

    public void detenerPlanificador() {
        if (uiScheduler != null && !uiScheduler.isShutdown()) {
            uiScheduler.shutdown();
        }
    }

    @Override
    public void shutdown() {
        detenerPlanificador();
    }
}