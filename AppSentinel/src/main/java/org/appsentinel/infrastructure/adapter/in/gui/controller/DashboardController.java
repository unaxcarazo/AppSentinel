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

    private static final double LIMITE_PRODUCTIVO_SEG = 5 * 3600;
    private static final double LIMITE_DISTRACCION_SEG = 1 * 3600;

    private volatile long ultimaActualizacionUi = 0;
    private static final long MIN_MS_ENTRE_ACTUALIZACIONES = 2000;

    private ScheduledExecutorService uiScheduler;
    private RegistroRepositoryPort repo;
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
            if (scoreFill != null && scoreFill.getParent() instanceof Region) {
                Region contenedor = (Region) scoreFill.getParent();
                contenedor.heightProperty().addListener((obs, oldH, newH) -> {
                    if (newH.doubleValue() > 0) {
                        String textoActual = lblScorePercent.getText().replace("%", "").trim();
                        try {
                            int pct = Integer.parseInt(textoActual);
                            double factor = pct / 100.0;
                            scoreFill.setPrefHeight(newH.doubleValue() * factor);
                            scoreFill.setMaxHeight(newH.doubleValue() * factor);
                        } catch (NumberFormatException ignored) {
                        }
                    }
                });
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
        this.repo = ctx.repo();
        this.usuarioActual = System.getProperty("user.name");
        if (this.usuarioActual == null) {
            this.usuarioActual = "DAW1";
        }
        recargarDatosDesdeBd();
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
            double progreso = Math.min((double) app.getDuracionSeg() / LIMITE_PRODUCTIVO_SEG, 1.0);

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
            bar.getStyleClass().add(excedido ? "progress-bar-danger" : "progress-distraction-thin");

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
            } else if ("DISTRACCION".equals(cat) || "DISTRACCIÓN".equals(cat) || cat.contains("DISTRA")) {
                totalSegundosDistraccion += r.getDuracionSeg();
            }
        }

        long totalSegundos = totalSegundosProductivo + totalSegundosDistraccion;

        lblWorkTime.setText(formatearTiempo(totalSegundosProductivo));
        lblDistractionTime.setText(formatearTiempo(totalSegundosDistraccion));
        lblTotalHours.setText(formatearTiempo(totalSegundos));
        lblCount.setText(String.valueOf(registros.size()));

        if (progressWorkCard != null) {
            progressWorkCard.setProgress(Math.max(0.0, Math.min(1.0,
                    (double) totalSegundosProductivo / LIMITE_PRODUCTIVO_SEG)));
        }
        if (progressDistractionCard != null) {
            progressDistractionCard.setProgress(Math.max(0.0, Math.min(1.0,
                    (double) totalSegundosDistraccion / LIMITE_DISTRACCION_SEG)));
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
            java.time.format.DateTimeFormatter formatter
                    = java.time.format.DateTimeFormatter.ofPattern("dd MMM, yyyy");
            lblFecha.setText(fechaConsultada.format(formatter));
        }

        if (repo != null && usuarioActual != null) {
            List<Registro> registrosDelDia = repo.findByUsuarioAndFecha(usuarioActual, fechaConsultada);

            List<Registro> topTrabajo = agruparYOrdenar(registrosDelDia, "PRODUCTIVO");
            List<Registro> topDistracciones = agruparYOrdenar(registrosDelDia, "DISTRACCION");

            actualizarCards(registrosDelDia);
            actualizarRankingTrabajo(topTrabajo);
            actualizarRankingDistracciones(topDistracciones);
            actualizarGraficoReal(registrosDelDia);
            updateProductivityScore(registrosDelDia);
        }
    }

    /**
     * Agrupa registros por nombre de actividad, suma duraciones y devuelve el
     * top 5 ordenado de mayor a menor para la categoría indicada.
     */
    private List<Registro> agruparYOrdenar(List<Registro> registros, String categoria) {
        return registros.stream()
                .filter(r -> {
                    if (r == null || r.getCategoria() == null) {
                        return false;
                    }
                    String cat = r.getCategoria().trim().toUpperCase();
                    if ("PRODUCTIVO".equalsIgnoreCase(categoria)) {
                        return "PRODUCTIVO".equals(cat);
                    } else {
                        // 🚀 BLINDAJE: Tolerancia total a tildes (DISTRACCIÓN o DISTRACCION)
                        return "DISTRACCION".equals(cat) || "DISTRACCIÓN".equals(cat) || cat.contains("DISTRA");
                    }
                })
                .collect(Collectors.groupingBy(
                        Registro::getNombreActividad,
                        Collectors.summingLong(Registro::getDuracionSeg)))
                .entrySet().stream()
                .map(entry -> {
                    Registro reg = new Registro();
                    reg.setNombreActividad(entry.getKey());
                    reg.setDuracionSeg(entry.getValue());
                    reg.setCategoria(categoria);
                    return reg;
                })
                .sorted((r1, r2) -> Long.compare(r2.getDuracionSeg(), r1.getDuracionSeg()))
                .limit(5)
                .collect(Collectors.toList());
    }

    private void actualizarGraficoReal(List<Registro> registros) {
        if (registros == null || registros.isEmpty()) {
            Platform.runLater(() -> barChartActivity.getData().clear());
            return;
        }

        double[] minutosProductivoPorHora = new double[24];
        double[] minutosDistraccionPorHora = new double[24];
        double maxMinutos = 0;

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

            if (hora < minHora) {
                minHora = hora;
            }
            if (hora > maxHora) {
                maxHora = hora;
            }

            double minutes = r.getDuracionSeg() / 60.0;
            if (minutes <= 0) {
                continue;
            }

            // 1. LIMPIEZA DE TEXTO UNIFICADA (Evita fallos de mayúsculas/tildes)
            String cat = r.getCategoria() != null ? r.getCategoria().trim().toUpperCase()
                    .replace("Á", "A").replace("É", "E")
                    .replace("Í", "I").replace("Ó", "O")
                    .replace("Ú", "U") : "";

            // 2. FILTRADO SEGURO (Asegúrate de usar los mismos nombres que en tus tarjetas de arriba)
            if ("PRODUCTIVO".equals(cat) || "TRABAJO".equals(cat)) {
                minutosProductivoPorHora[hora] += minutes;
            } else if ("DISTRACCION".equals(cat) || cat.contains("DISTRA")) {
                minutosDistraccionPorHora[hora] += minutes;
            }
        }

        if (maxHora == -1 || minHora == 24) {
            Platform.runLater(() -> barChartActivity.getData().clear());
            return;
        }

        // 3. CONTROL DE HORA ACTUAL (¡Ojo! Solo aplicable si los registros son de HOY)
        // Si implementas histórico de días, añade una condición aquí para comprobar si es hoy.
        int horaActual = java.time.LocalTime.now().getHour();
        if (maxHora < horaActual) {
            maxHora = horaActual;
        }

        XYChart.Series<String, Number> seriesWork = new XYChart.Series<>();
        seriesWork.setName("Trabajo");

        XYChart.Series<String, Number> seriesDist = new XYChart.Series<>();
        seriesDist.setName("Distracción");

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

        Platform.runLater(() -> {
            barChartActivity.setAnimated(false);
            barChartActivity.getData().clear();

            if (barChartActivity.getXAxis() instanceof javafx.scene.chart.CategoryAxis) {
                ((javafx.scene.chart.CategoryAxis) barChartActivity.getXAxis()).setAutoRanging(true);
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

            if (lblScoreMessage != null) {
                lblScoreMessage.getStyleClass().removeAll(
                        "score-msg-excellent", "score-msg-good",
                        "score-msg-warning", "score-msg-danger", "score-msg-nodata");

                if (porcentajeFinal >= 80) {
                    lblScoreMessage.setText("EXCELENT WORK!");
                    lblScoreMessage.getStyleClass().add("score-msg-excellent");
                } else if (porcentajeFinal >= 60) {
                    lblScoreMessage.setText("GOOD JOB! KEEP IT UP.");
                    lblScoreMessage.getStyleClass().add("score-msg-good");
                } else if (porcentajeFinal >= 40) {
                    lblScoreMessage.setText("YOU CAN DO BETTER.");
                    lblScoreMessage.getStyleClass().add("score-msg-warning");
                } else if (porcentajeFinal > 0) {
                    lblScoreMessage.setText("TOO MANY DISTRACTIONS!");
                    lblScoreMessage.getStyleClass().add("score-msg-danger");
                } else {
                    lblScoreMessage.setText("NO DATA YET.");
                    lblScoreMessage.getStyleClass().add("score-msg-nodata");
                }
            }

            double factorProgreso = porcentajeFinal / 100.0;
            if (scoreFill != null && scoreFill.getParent() instanceof Region) {
                Region contenedor = (Region) scoreFill.getParent();
                double alturaReal = contenedor.getHeight() > 0 ? contenedor.getHeight() : 160.0;
                scoreFill.setPrefHeight(alturaReal * factorProgreso);
                scoreFill.setMaxHeight(alturaReal * factorProgreso);

                scoreFill.getStyleClass().removeAll("score-fill-color", "score-fill-full");
                if (porcentajeFinal >= 98) {
                    scoreFill.getStyleClass().add("score-fill-full");
                } else {
                    scoreFill.getStyleClass().add("score-fill-color");
                }
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
            } else if ("DISTRACCION".equals(cat) || "DISTRACCIÓN".equals(cat) || cat.contains("DISTRA")) {
                segundosDistraccion += r.getDuracionSeg();
            }
        }

        double minutesTotales = (segundosProductivo + segundosDistraccion) / 60.0;
        if (minutesTotales <= 0) {
            return 0;
        }

        double resultado = (segundosProductivo / 60.0 * 100.0) / minutesTotales;
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
        ultimaActualizacionUi = System.currentTimeMillis();

        Platform.runLater(this::recargarDatosDesdeBd);
    }

    private void recargarDatosDesdeBd() {
        if (repo != null && usuarioActual != null) {
            System.out.println("Cargando información optimizada desde la base de datos...");

            List<Registro> topTrabajo = repo.obtenerTopTrabajo(usuarioActual, 5);
            List<Registro> topDistracciones = repo.obtenerTopDistracciones(usuarioActual, 5);
            List<Registro> actividad = repo.obtenerActividadHoy(usuarioActual);

            // 🚀 SOLUCIÓN EN TIEMPO REAL: Fallbacks activados para AMBAS categorías por igual
            if ((topTrabajo == null || topTrabajo.isEmpty()) && actividad != null && !actividad.isEmpty()) {
                topTrabajo = agruparYOrdenar(actividad, "PRODUCTIVO");
            }
            if ((topDistracciones == null || topDistracciones.isEmpty()) && actividad != null && !actividad.isEmpty()) {
                topDistracciones = agruparYOrdenar(actividad, "DISTRACCION");
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
