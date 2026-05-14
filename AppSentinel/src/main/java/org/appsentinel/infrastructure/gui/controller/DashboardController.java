package org.appsentinel.infrastructure.gui.controller;

import java.awt.Desktop;
import java.io.File;
import java.io.IOException;
import java.net.URL;
import java.util.List;
import java.util.ResourceBundle;
import javafx.fxml.FXML;
import javafx.fxml.Initializable;
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
import org.appsentinel.infrastructure.adapter.out.PostgreSQLRepositoryAdapter;

public class DashboardController implements Initializable {

    @FXML
    private VBox vboxTopWork;

    @FXML
    private VBox vboxTopDistractions;

    @FXML
    private VBox vboxActivityLog;

    @FXML
    private VBox vboxBlockingLog;

    private RegistroRepositoryPort repository = new PostgreSQLRepositoryAdapter();

    // Declaración de elementos FXML
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

    @Override
    public void initialize(URL url, ResourceBundle rb) {

        String usuarioActual = System.getProperty("DAW1");

        // 1. Cargar datos reales desde la base de datos
        List<Registro> topTrabajo = repository.obtenerTopTrabajo(usuarioActual, 5);
        List<Registro> topDistracciones = repository.obtenerTopDistracciones(usuarioActual, 5);
        List<Registro> actividad = repository.obtenerActividadHoy(usuarioActual);
        List<Registro> bloqueos = repository.obtenerBloqueosHoy(usuarioActual);

        // 2. "Pintar" los datos en la interfaz
        actualizarCards(actividad);
        actualizarRankingTrabajo(topTrabajo);
        actualizarRankingDistracciones(topDistracciones);
        actualizarLogActividad(actividad);
        actualizarLogBloqueos(bloqueos);

        // 3. Configurar el gráfico (Aquí puedes dejar los datos de prueba o crear una serie real)
        actualizarGraficoReal(actividad);
        System.out.println("Dashboard inicializado con datos reales de: " + usuarioActual);

        /* 1. Asignar valores de prueba a las tarjetas
        lblTotalHours.setText("08h 14m");
        lblWorkTime.setText("06h 25m");
        lblDistractionTime.setText("01h 49m");
        lblCount.setText("14");
        lblScorePercent.setText("78%");
        
        // 2. Configurar el gráfico multicolor
        XYChart.Series<String, Number> serieTrabajo = new XYChart.Series<>();
        serieTrabajo.setName("Trabajo");
        serieTrabajo.getData().add(new XYChart.Data<>("09:00", 45));
        serieTrabajo.getData().add(new XYChart.Data<>("10:00", 50));
        serieTrabajo.getData().add(new XYChart.Data<>("11:00", 30));

        XYChart.Series<String, Number> serieDistraccion = new XYChart.Series<>();
        serieDistraccion.setName("Distracción");
        serieDistraccion.getData().add(new XYChart.Data<>("09:00", 10));
        serieDistraccion.getData().add(new XYChart.Data<>("10:00", 15));
        serieDistraccion.getData().add(new XYChart.Data<>("11:00", 25));
        
        
        // Añadir ambas series para que el CSS coloree cian y naranja
        barChartActivity.getData().addAll(serieTrabajo, serieDistraccion);
        */
        System.out.println("Dashboard inicializado con patrón de colores Cyber-Dark.");
    }

    @FXML
    private void handleDownloadReport() {
        System.out.println("Generando y abriendo informe: DelayLog.html");

        try {
            // Definimos el archivo (estará en la carpeta raíz de tu proyecto)
            File htmlFile = new File("DelayLog.html");

            // Comprobamos si existe antes de intentar abrirlo
            if (htmlFile.exists()) {
                // Usa la API de escritorio de Java para abrir el navegador predeterminado
                Desktop.getDesktop().browse(htmlFile.toURI());
            } else {
                // Si no existe, imprimimos un aviso en la consola de NetBeans
                System.err.println("Error: El archivo DelayLog.html no existe. El adaptador debe generarlo primero.");
            }
        } catch (IOException e) {
            System.err.println("Error al intentar abrir el archivo: " + e.getMessage());
        }
    }

    private void actualizarRankingTrabajo(List<Registro> topApps) {
        vboxTopWork.getChildren().clear(); // Asegúrate de tener @FXML private VBox vboxTopWork;

        int pos = 1;
        for (Registro app : topApps) {
            HBox row = new HBox();
            row.getStyleClass().add("ranking-row");
            row.setAlignment(Pos.CENTER_LEFT);
            row.setSpacing(15);

            Label lblPos = new Label("#" + pos);
            lblPos.getStyleClass().add("ranking-pos");

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

            ProgressBar bar = new ProgressBar((double) app.getDuracionSeg() / 14400); // Ejemplo sobre 4h
            bar.setMaxWidth(Double.MAX_VALUE);
            bar.getStyleClass().add("progress-work-thin"); // COLOR CIAN

            infoContainer.getChildren().addAll(topInfo, bar);
            row.getChildren().addAll(lblPos, infoContainer);

            vboxTopWork.getChildren().add(row);
            pos++;
        }
    }

    private void actualizarRankingDistracciones(List<Registro> topApps) {
        vboxTopDistractions.getChildren().clear(); // vboxTopDistractions es el fx:id de tu VBox en FXML

        int pos = 1;
        for (Registro app : topApps) {
            // Creamos la estructura HBox que diseñamos ayer
            HBox row = new HBox();
            row.getStyleClass().add("ranking-row");
            row.setAlignment(Pos.CENTER_LEFT);
            row.setSpacing(15);

            Label lblPos = new Label("#" + pos);
            lblPos.getStyleClass().add("ranking-pos");

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

            ProgressBar bar = new ProgressBar((double) app.getDuracionSeg() / 3600); // Ejemplo sobre 1h
            bar.setMaxWidth(Double.MAX_VALUE);
            bar.getStyleClass().add("progress-distraction-thin");

            infoContainer.getChildren().addAll(topInfo, bar);
            row.getChildren().addAll(lblPos, infoContainer);

            vboxTopDistractions.getChildren().add(row);
            pos++;
        }
    }

    private void actualizarLogActividad(List<Registro> registros) {
        vboxActivityLog.getChildren().clear();

        for (Registro r : registros) {
            HBox row = new HBox();
            row.getStyleClass().add("log-row");
            row.setAlignment(Pos.CENTER_LEFT);
            row.setSpacing(15);

            // Determinamos el icono y color según categoría
            String emoji = "TRABAJO".equalsIgnoreCase(r.getCategoria()) ? "💻" : "🌐";
            String estiloIcono = "TRABAJO".equalsIgnoreCase(r.getCategoria()) ? "icon-box-cian" : "icon-box-naranja";

            StackPane iconBox = new StackPane(new Label(emoji));
            iconBox.getStyleClass().addAll("icon-box", estiloIcono);

            VBox textData = new VBox();
            Label appName = new Label(r.getNombreActividad());
            appName.getStyleClass().add("log-app-title");

            // Mostramos la hora de inicio del registro
            Label detail = new Label("Started: " + r.getFechaRegistro().toLocalTime().toString().substring(0, 5));
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

        for (Registro b : bloqueos) {
            HBox row = new HBox();
            row.getStyleClass().add("log-row");
            row.setAlignment(Pos.CENTER_LEFT);
            row.setSpacing(15);

            // Icono escudo
            StackPane iconBox = new StackPane(new Label("🛡"));
            iconBox.getStyleClass().addAll("icon-box", "icon-box-naranja");

            VBox textData = new VBox();
            Label appName = new Label(b.getNombreActividad());
            appName.getStyleClass().add("log-app-title");
            Label detail = new Label("Blocked attempt at " + b.getFechaRegistro().toLocalTime());
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

    /**
     * Calcula los totales y actualiza las tarjetas superiores (KPIs).
     */
    private void actualizarCards(List<Registro> registros) {
        long totalSegundosTrabajo = registros.stream()
                .filter(r -> "TRABAJO".equalsIgnoreCase(r.getCategoria()))
                .mapToLong(Registro::getDuracionSeg).sum();

        long totalSegundosDistraccion = registros.stream()
                .filter(r -> "DISTRACCION".equalsIgnoreCase(r.getCategoria()))
                .mapToLong(Registro::getDuracionSeg).sum();

        long totalSegundos = totalSegundosTrabajo + totalSegundosDistraccion;

        // Actualizamos los Labels con los datos reales
        lblWorkTime.setText(formatearTiempo(totalSegundosTrabajo));
        lblDistractionTime.setText(formatearTiempo(totalSegundosDistraccion));
        lblTotalHours.setText(formatearTiempo(totalSegundos));
        lblCount.setText(String.valueOf(registros.size()));

        // El Score de productividad (ejemplo simple: % de trabajo sobre el total)
        if (totalSegundos > 0) {
            int score = (int) ((totalSegundosTrabajo * 100) / totalSegundos);
            lblScorePercent.setText(score + "%");
        }
    }

    /**
     * Convierte segundos en formato legible "00h 00m".
     */
    private String formatearTiempo(long segundosTotal) {
        long horas = segundosTotal / 3600;
        long minutos = (segundosTotal % 3600) / 60;
        return String.format("%02dh %02dm", horas, minutos);
    }

    private void actualizarGraficoReal(List<Registro> registros) {
        barChartActivity.getData().clear();

        XYChart.Series<String, Number> seriesWork = new XYChart.Series<>();
        seriesWork.setName("Trabajo");

        XYChart.Series<String, Number> seriesDist = new XYChart.Series<>();
        seriesDist.setName("Distracción");

        // Agrupamos datos de forma simplificada por hora de inicio
        for (Registro r : registros) {
            String hora = r.getFechaRegistro().toLocalTime().toString().substring(0, 2) + ":00";
            double minutos = r.getDuracionSeg() / 60.0;

            if ("TRABAJO".equalsIgnoreCase(r.getCategoria())) {
                seriesWork.getData().add(new XYChart.Data<>(hora, minutos));
            } else {
                seriesDist.getData().add(new XYChart.Data<>(hora, minutos));
            }
        }

        barChartActivity.getData().addAll(seriesWork, seriesDist);
    }

}
