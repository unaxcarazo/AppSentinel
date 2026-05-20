package org.appsentinel.infrastructure.gui.controller;

import java.awt.Desktop;
import java.io.File;
import java.io.IOException;
import java.net.URL;
import java.util.List;
import java.util.ResourceBundle;
import javafx.fxml.FXML;
import javafx.geometry.Pos;
import javafx.scene.chart.BarChart;
import javafx.scene.chart.XYChart;
import javafx.scene.control.Label;
import javafx.scene.control.ProgressBar;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import org.appsentinel.domain.model.Registro;
import org.appsentinel.domain.port.out.RegistroRepositoryPort;
import org.appsentinel.infrastructure.config.AppContext;
import javafx.fxml.Initializable;
import javafx.scene.control.ScrollPane;

/**
 * DashboardController: Gestiona el panel principal de métricas de
 * productividad. Integrado polimórficamente mediante la interfaz Controllable
 * del ecosistema del grupo.
 */
public class DashboardController implements Initializable, Controllable {

    private static final double LIMITE_TRABAJO_SEG = 5 * 3600; // 5 horas en segundos
    private static final double LIMITE_DISTRACCION_SEG = 1 * 3600; // 1 hora en segundos

    @FXML
    private Region scoreFill;
    @FXML
    private VBox vboxTopWork;
    @FXML
    private VBox vboxTopDistractions;
    @FXML
    private VBox vboxActivityLog;
    @FXML
    private VBox vboxBlockingLog;

    private RegistroRepositoryPort repository;

    // Elementos FXML de la interfaz de usuario
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
    private BarChart<String, Number> barChartActivity;

    @FXML
    private ScrollPane rootPane; // Asegúrate de que este ID coincida con el fx:id de tu FXML raíz

    @Override
    public void initialize(URL url, ResourceBundle rb) {
        if (rootPane != null) {
            // 1. Intentamos buscar el archivo en el Classpath
            URL cssURL = this.getClass().getResource("/styles/dashboard.css"); // <-- Asegúrate de que se llame así tu archivo real

            if (cssURL == null) {
                System.err.println("❌ ERROR CRÍTICO: ¡El archivo CSS no se encuentra en 'src/main/resources/styles/'!");
                System.err.println("Comprueba que el nombre sea idéntico (ej: 'dashboard.css' o 'styles.css')");
            } else {
                System.out.println("✅ Archivo CSS encontrado con éxito en: " + cssURL.toExternalForm());
                rootPane.getStylesheets().add(cssURL.toExternalForm());
            }
        } else {
            System.err.println("❌ ERROR: 'rootPane' es null. El fx:id no está bien puesto en el FXML.");
        }
    }

    /**
     * Constructor vacío nativo requerido por el FXMLLoader de JavaFX.
     */
    public DashboardController() {
        // La inyección de dependencias se difiere al método init() contratado por la arquitectura
    }

    /**
     * CONTRATO DE ARQUITECTURA: Inicialización polimórfica inyectando el
     * AppContext.
     */
    @Override
    public void init(AppContext ctx) {
        // 1. Extraemos el puerto de infraestructura de manera segura desde el grafo del grupo
        this.repository = ctx.repositorio();

        // 2. Extraemos de forma robusta el usuario del entorno operativo actual
        String usuarioActual = System.getProperty("user.name");
        if (usuarioActual == null) {
            usuarioActual = "DAW1";
        }

        // 3. Ejecutamos de forma asíncrona/segura la carga de datos limpios de PostgreSQL
        List<Registro> topTrabajo = repository.obtenerTopTrabajo(usuarioActual, 5);
        List<Registro> topDistracciones = repository.obtenerTopDistracciones(usuarioActual, 5);
        List<Registro> actividad = repository.obtenerActividadHoy(usuarioActual);
        List<Registro> bloqueos = repository.obtenerBloqueosHoy(usuarioActual);

        // 4. Renderizamos los datos reales de forma segura usando los métodos del controlador
        actualizarCards(actividad);
        actualizarRankingTrabajo(topTrabajo);
        actualizarRankingDistracciones(topDistracciones);
        actualizarLogActividad(actividad);
        actualizarLogBloqueos(bloqueos);

        // 5. Dibujamos el gráfico de barras reactivo
        actualizarGraficoReal(actividad);

        System.out.println("🚀 Dashboard cargado con éxito en el ecosistema usando AppContext.");
    }

    // =========================================================================
    // ACCIONES DE LA INTERFAZ DE USUARIO (@FXML)
    // =========================================================================
    @FXML
    private void handleDownloadReport() {
        System.out.println("Generando y abriendo informe: DelayLog.html");
        try {
            File htmlFile = new File("DelayLog.html");
            if (htmlFile.exists()) {
                Desktop.getDesktop().browse(htmlFile.toURI());
            } else {
                System.err.println("Error: El archivo DelayLog.html no existe. El adaptador debe generarlo primero.");
            }
        } catch (IOException e) {
            System.err.println("Error al intentar abrir el archivo: " + e.getMessage());
        }
    }

    // =========================================================================
    // MÉTODOS DE RENDERIZADO VISUAL Y LÓGICA DE NEGOCIO INTERNA
    // =========================================================================
    private void actualizarRankingTrabajo(List<Registro> topWorkApps) {
        if (topWorkApps == null || topWorkApps.isEmpty()) {
            vboxTopWork.getChildren().clear();
            vboxTopWork.getChildren().add(new Label("No hay registros aún"));
            return;
        }
        vboxTopWork.getChildren().clear();

        int pos = 1;
        for (Registro app : topWorkApps) {
            double progreso = (double) app.getDuracionSeg() / LIMITE_TRABAJO_SEG;
            if (progreso > 1.0) {
                progreso = 1.0;
            }

            ProgressBar bar = new ProgressBar(progreso);
            bar.setMaxWidth(Double.MAX_VALUE);
            bar.setMinWidth(150);
            HBox.setHgrow(bar, Priority.ALWAYS);

            bar.getStyleClass().removeAll("progress-work-thin", "progress-distraction-thin", "progress-bar-danger");
            bar.getStyleClass().add("progress-work-thin");

            HBox row = new HBox();
            row.getStyleClass().add("ranking-row");
            row.setAlignment(Pos.CENTER_LEFT);
            row.setSpacing(15);

            VBox infoContainer = new VBox();
            infoContainer.setSpacing(5);
            HBox.setHgrow(infoContainer, Priority.ALWAYS);

            HBox topInfo = new HBox();
            Label name = new Label(app.getNombreActividad());
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
        if (topDistractionApps == null || topDistractionApps.isEmpty()) {
            vboxTopDistractions.getChildren().clear();
            vboxTopDistractions.getChildren().add(new Label("No hay registros aún"));
            return;
        }
        vboxTopDistractions.getChildren().clear();

        int pos = 1;
        for (Registro app : topDistractionApps) {
            double progreso = (double) app.getDuracionSeg() / LIMITE_DISTRACCION_SEG;
            boolean excedido = progreso >= 1.0;
            if (progreso > 1.0) {
                progreso = 1.0;
            }

            ProgressBar bar = new ProgressBar(progreso);
            bar.setMaxWidth(Double.MAX_VALUE);
            bar.setMinWidth(150);
            HBox.setHgrow(bar, Priority.ALWAYS);

            bar.getStyleClass().removeAll("progress-bar-danger", "progress-distraction-thin");

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
            Label name = new Label(app.getNombreActividad());
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

    private void actualizarLogActividad(List<Registro> registros) {
        vboxActivityLog.getChildren().clear();
        if (registros == null || registros.isEmpty()) {
            return;
        }

        for (Registro r : registros) {
            // Blindaje extra: asegurar que el objeto y sus campos críticos existan
            if (r == null || r.getFechaRegistro() == null || r.getNombreActividad() == null) {
                continue;
            }

            HBox row = new HBox();
            row.getStyleClass().add("log-row");
            row.setAlignment(Pos.CENTER_LEFT);
            row.setSpacing(15);

            String categoria = r.getCategoria() != null ? r.getCategoria() : "NEUTRAL";
            String emoji = "TRABAJO".equalsIgnoreCase(categoria) ? "💻" : "🌐";
            String estiloIcono = "TRABAJO".equalsIgnoreCase(categoria) ? "icon-box-cian" : "icon-box-naranja";

            StackPane iconBox = new StackPane(new Label(emoji));
            iconBox.getStyleClass().addAll("icon-box", estiloIcono);

            VBox textData = new VBox();
            Label appName = new Label(r.getNombreActividad());
            appName.getStyleClass().add("log-app-title");

            // Formateo ultra seguro de la hora
            String horaFormateada = "00:00";
            if (r.getFechaRegistro().toLocalTime() != null) {
                String rawTime = r.getFechaRegistro().toLocalTime().toString();
                if (rawTime != null && rawTime.length() >= 5) {
                    horaFormateada = rawTime.substring(0, 5);
                }
            }

            Label detail = new Label("Started: " + horaFormateada);
            detail.getStyleClass().add("log-subtext");
            textData.getChildren().addAll(appName, detail);

            Region spacer = new Region();
            HBox.setHgrow(spacer, Priority.ALWAYS);

            Label duration = new Label(formatearTiempo(r.getDuracionSeg()));
            duration.getStyleClass().add("log-duration");

            row.getChildren().addAll(iconBox, textData, spacer, duration);
            vboxActivityLog.getChildren().add(row);
        }
    }

    private void actualizarLogBloqueos(List<Registro> bloqueos) {
        vboxBlockingLog.getChildren().clear();
        if (bloqueos == null || bloqueos.isEmpty()) {
            return;
        }

        for (Registro b : bloqueos) {
            if (b == null || b.getFechaRegistro() == null || b.getNombreActividad() == null) {
                continue;
            }

            HBox row = new HBox();
            row.getStyleClass().add("log-row");
            row.setAlignment(Pos.CENTER_LEFT);
            row.setSpacing(15);

            StackPane iconBox = new StackPane(new Label("🛡"));
            iconBox.getStyleClass().addAll("icon-box", "icon-box-naranja");

            VBox textData = new VBox();
            Label appName = new Label(b.getNombreActividad());
            appName.getStyleClass().add("log-app-title");

            // Formateo ultra seguro previniendo síncopes en substring
            String horaBloqueo = "00:00";
            if (b.getFechaRegistro().toLocalTime() != null) {
                String rawTime = b.getFechaRegistro().toLocalTime().toString();
                if (rawTime != null && rawTime.length() >= 5) {
                    horaBloqueo = rawTime.substring(0, 5);
                }
            }

            Label detail = new Label("Blocked attempt at " + horaBloqueo);
            detail.getStyleClass().add("log-subtext");
            textData.getChildren().addAll(appName, detail);

            Region spacer = new Region();
            HBox.setHgrow(spacer, Priority.ALWAYS);

            Label badge = new Label("BLOCKED");
            badge.getStyleClass().add("badge-blocked");

            row.getChildren().addAll(iconBox, textData, spacer, badge);
            vboxBlockingLog.getChildren().add(row);
        }
    }

    private void actualizarCards(List<Registro> registros) {
        if (registros == null || registros.isEmpty()) {
            lblWorkTime.setText("00h 00m");
            lblDistractionTime.setText("00h 00m");
            lblTotalHours.setText("00h 00m");
            lblCount.setText("0");
            lblScorePercent.setText("0%");
            return;
        }

        long totalSegundosTrabajo = 0;
        long totalSegundosDistraccion = 0;

        for (Registro r : registros) {
            if (r == null || r.getCategoria() == null) {
                continue;
            }
            if ("TRABAJO".equalsIgnoreCase(r.getCategoria())) {
                totalSegundosTrabajo += r.getDuracionSeg();
            } else if ("DISTRACCION".equalsIgnoreCase(r.getCategoria())) {
                totalSegundosDistraccion += r.getDuracionSeg();
            }
        }

        long totalSegundos = totalSegundosTrabajo + totalSegundosDistraccion;

        lblWorkTime.setText(formatearTiempo(totalSegundosTrabajo));
        lblDistractionTime.setText(formatearTiempo(totalSegundosDistraccion));
        lblTotalHours.setText(formatearTiempo(totalSegundos));
        lblCount.setText(String.valueOf(registros.size()));

        if (totalSegundos > 0) {
            double scoreFraccion = (double) totalSegundosTrabajo / totalSegundos;
            updateProductivityScore(scoreFraccion);
        } else {
            updateProductivityScore(0.0);
        }
    }

    private String formatearTiempo(long segundosTotal) {
        long horas = segundosTotal / 3600;
        long minutos = (segundosTotal % 3600) / 60;
        return String.format("%02dh %02dm", horas, minutos);
    }

    private void actualizarGraficoReal(List<Registro> registros) {
        barChartActivity.getData().clear();
        if (registros == null || registros.isEmpty()) {
            return;
        }

        XYChart.Series<String, Number> seriesWork = new XYChart.Series<>();
        seriesWork.setName("Trabajo");

        XYChart.Series<String, Number> seriesDist = new XYChart.Series<>();
        seriesDist.setName("Distracción");

        java.util.Map<String, Double> acumuladoTrabajo = new java.util.LinkedHashMap<>();
        java.util.Map<String, Double> acumuladoDistraccion = new java.util.LinkedHashMap<>();

        for (Registro r : registros) {
            if (r == null || r.getFechaRegistro() == null || r.getCategoria() == null || r.getFechaRegistro().toLocalTime() == null) {
                continue;
            }

            String rawTime = r.getFechaRegistro().toLocalTime().toString();
            if (rawTime == null || rawTime.length() < 2) {
                continue;
            }

            String hora = rawTime.substring(0, 2) + ":00";
            double minutos = r.getDuracionSeg() / 60.0;

            if ("TRABAJO".equalsIgnoreCase(r.getCategoria())) {
                acumuladoTrabajo.put(hora, acumuladoTrabajo.getOrDefault(hora, 0.0) + minutos);
            } else {
                acumuladoDistraccion.put(hora, acumuladoDistraccion.getOrDefault(hora, 0.0) + minutos);
            }
        }

        acumuladoTrabajo.forEach((hora, mins) -> seriesWork.getData().add(new XYChart.Data<>(hora, mins)));
        acumuladoDistraccion.forEach((hora, mins) -> seriesDist.getData().add(new XYChart.Data<>(hora, mins)));

        if (!seriesWork.getData().isEmpty()) {
            barChartActivity.getData().add(seriesWork);
        }
        if (!seriesDist.getData().isEmpty()) {
            barChartActivity.getData().add(seriesDist);
        }
    }

    public void updateProductivityScore(double score) {
        double sanitizedScore = Math.max(0.0, Math.min(1.0, score));
        lblScorePercent.setText((int) (sanitizedScore * 100) + "%");
        double maxHeight = 160.0;
        scoreFill.setPrefHeight(maxHeight * sanitizedScore);
    }
}
