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
import javax.swing.plaf.synth.Region;
import org.appsentinel.domain.model.Registro;
import org.appsentinel.domain.port.out.RegistroRepositoryPort;
//import org.appsentinel.infrastructure.adapter.out.PostgreSQLRepositoryAdapter;

public class DashboardController implements Initializable {

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

    // Comenta la línea de PostgreSQL y usa la de Fake
    // private RegistroRepositoryPort repository = new PostgreSQLRepositoryAdapter();
    private RegistroRepositoryPort repository;

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

    public void init(RegistroRepositoryPort repositorioInyectado) {
        // 1. Guardamos el repositorio vivo que nos envía el MainController desde el AppWiring
        this.repository = repositorioInyectado;

        String usuarioActual = System.getProperty("user.name"); // 'user.name' es el estándar de Java para sacar el usuario del S.O.
        if (usuarioActual == null) {
            usuarioActual = "DAW1";
        }

        // 2. Ejecutamos la carga de datos usando el repositorio inyectado
        List<Registro> topTrabajo = repository.obtenerTopTrabajo(usuarioActual, 5);
        List<Registro> topDistracciones = repository.obtenerTopDistracciones(usuarioActual, 5);
        List<Registro> actividad = repository.obtenerActividadHoy(usuarioActual);
        List<Registro> bloqueos = repository.obtenerBloqueosHoy(usuarioActual);

        // 3. "Pintamos" los datos reales en la interfaz
        actualizarCards(actividad);
        actualizarRankingTrabajo(topTrabajo);
        actualizarRankingDistracciones(topDistracciones);
        actualizarLogActividad(actividad);
        actualizarLogBloqueos(bloqueos);

        // 4. Configurar el gráfico multicolor
        actualizarGraficoReal(actividad);
        System.out.println("🚀 Dashboard cargado con éxito usando el repositorio: "
                + "" + repository.getClass().getSimpleName());
    }

    @Override
    public void initialize(URL url, ResourceBundle rb) {
        System.out.println("Dashboard inicializado con patrón de colores Cyber-Dark.");
    }

    // PRUEBAS  
    private void cargarLogs() {
        vboxActivityLog.getChildren().clear(); // ¡ESTA LÍNEA ES CLAVE!
        vboxBlockingLog.getChildren().clear();
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

    private void actualizarRankingTrabajo(List<Registro> topWorkApps) {
        if (topWorkApps == null || topWorkApps.isEmpty()) {
            vboxTopWork.getChildren().add(new Label("No hay registros aún"));
            return;
        }
        vboxTopWork.getChildren().clear();

        int pos = 1;
        for (Registro app : topWorkApps) {
            // 1. Lógica de la barra (Límite 5h = 18000s)
            double progreso = (double) app.getDuracionSeg() / LIMITE_TRABAJO_SEG;
            if (progreso > 1.0) {
                progreso = 1.0;
            }

            // 2. Crear la barra y LIMPIAR estilos previos
            ProgressBar bar = new ProgressBar(progreso);
            bar.setMaxWidth(Double.MAX_VALUE);   // Permite expandirse
            bar.setMinWidth(150);                // Garantiza que no sea invisible
            HBox.setHgrow(bar, Priority.ALWAYS);  // Empuja al HBox a darle todo el ancho

            // Limpiamos cualquier clase de color previa para asegurar el CIAN
            bar.getStyleClass().removeAll("progress-work-thin", "progress-distraction-thin", "progress-bar-danger");
            bar.getStyleClass().add("progress-work-thin");

            // 3. Crear los contenedores
            HBox row = new HBox();
            row.getStyleClass().add("ranking-row");
            row.setAlignment(Pos.CENTER_LEFT);
            row.setSpacing(15);

            VBox infoContainer = new VBox();
            infoContainer.setSpacing(5);
            HBox.setHgrow(infoContainer, Priority.ALWAYS);

            // 4. Crear etiquetas (Nombre y Tiempo)
            HBox topInfo = new HBox();
            Label name = new Label(app.getNombreActividad());
            name.getStyleClass().add("app-name");

            Region spacer = new Region();
            HBox.setHgrow(spacer, Priority.ALWAYS);

            Label time = new Label(formatearTiempo(app.getDuracionSeg()));
            time.getStyleClass().add("app-time");

            topInfo.getChildren().addAll(name, spacer, time);

            // 5. Montar la jerarquía
            infoContainer.getChildren().addAll(topInfo, bar);

            Label lblPos = new Label("#" + pos);
            lblPos.getStyleClass().add("ranking-pos");

            row.getChildren().addAll(lblPos, infoContainer);

            // 6. Añadir a la interfaz
            vboxTopWork.getChildren().add(row);

            pos++;
        }
    }

    private void actualizarRankingDistracciones(List<Registro> topDistractionApps) {
        if (topDistractionApps == null || topDistractionApps.isEmpty()) {
            vboxTopWork.getChildren().add(new Label("No hay registros aún"));
            return;
        }
        vboxTopDistractions.getChildren().clear();

        int pos = 1;
        for (Registro app : topDistractionApps) {
            // 1. Lógica de progreso (1h = 3600s)
            double progreso = (double) app.getDuracionSeg() / LIMITE_DISTRACCION_SEG;

            // Determinamos el estado antes de capar a 1.0
            boolean excedido = progreso >= 1.0;
            if (progreso > 1.0) {
                progreso = 1.0;
            }

            // 2. Crear barra y LIMPIAR estilos previos para evitar errores de renderizado
            ProgressBar bar = new ProgressBar(progreso);
            bar.setMaxWidth(Double.MAX_VALUE);   // Permite expandirse
            bar.setMinWidth(150);                // Garantiza que no sea invisible
            HBox.setHgrow(bar, Priority.ALWAYS);  // Empuja al HBox a darle todo el ancho

            // Esto asegura que la barra no arrastre estilos de otras filas
            bar.getStyleClass().removeAll("progress-bar-danger", "progress-distraction-thin");

            if (excedido) {
                // Si llega al 100% o lo pasa, clase roja
                bar.getStyleClass().add("progress-bar-danger");
            } else {
                // Si es menor al 100%, clase naranja
                bar.getStyleClass().add("progress-distraction-thin");
            }

            // 3. Construcción visual de la fila
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

            // 4. Montaje de nodos
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
        if (registros == null) {
            return;
        }

        for (Registro r : registros) {
            // ESCUDO: Si falta la fecha o la actividad en la BD, saltamos la fila para no romper
            if (r.getFechaRegistro() == null || r.getNombreActividad() == null) {
                continue;
            }

            HBox row = new HBox();
            row.getStyleClass().add("log-row");
            row.setAlignment(Pos.CENTER_LEFT);
            row.setSpacing(15);

            String emoji = "TRABAJO".equalsIgnoreCase(r.getCategoria()) ? "💻" : "🌐";
            String estiloIcono = "TRABAJO".equalsIgnoreCase(r.getCategoria()) ? "icon-box-cian" : "icon-box-naranja";

            StackPane iconBox = new StackPane(new Label(emoji));
            iconBox.getStyleClass().addAll("icon-box", estiloIcono);

            VBox textData = new VBox();
            Label appName = new Label(r.getNombreActividad());
            appName.getStyleClass().add("log-app-title");

            // Formateo seguro de la hora
            String horaFormateada = r.getFechaRegistro().toLocalTime().toString();
            if (horaFormateada.length() >= 5) {
                horaFormateada = horaFormateada.substring(0, 5);
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
        if (bloqueos == null) {
            return;
        }

        for (Registro b : bloqueos) {
            // ESCUDO: Evita el NullPointerException si Postgres devuelve una fecha vacía
            if (b.getFechaRegistro() == null || b.getNombreActividad() == null) {
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

            Label detail = new Label("Blocked attempt at " + b.getFechaRegistro().toLocalTime().toString().substring(0, 5));
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
        // ESCUDO 1: Si la lista viene completamente vacía o nula, reseteamos a cero y salimos de inmediato
        if (registros == null || registros.isEmpty()) {
            lblWorkTime.setText("00h 00m");
            lblDistractionTime.setText("00h 00m");
            lblTotalHours.setText("00h 00m");
            lblCount.setText("0");
            lblScorePercent.setText("0%");
            return;
        }

        // ESCUDO 2: Filtramos y sumamos de forma clásica y segura, saltando cualquier fila corrupta
        long totalSegundosTrabajo = 0;
        long totalSegundosDistraccion = 0;

        for (Registro r : registros) {
            if (r == null || r.getCategoria() == null) {
                continue; // Si el registro o su categoría son nulos, lo ignoramos de forma segura
            }

            if ("TRABAJO".equalsIgnoreCase(r.getCategoria())) {
                totalSegundosTrabajo += r.getDuracionSeg();
            } else if ("DISTRACCION".equalsIgnoreCase(r.getCategoria())) {
                totalSegundosDistraccion += r.getDuracionSeg();
            }
        }

        long totalSegundos = totalSegundosTrabajo + totalSegundosDistraccion;

        // Actualizamos los Labels con los datos calculados de forma segura
        lblWorkTime.setText(formatearTiempo(totalSegundosTrabajo));
        lblDistractionTime.setText(formatearTiempo(totalSegundosDistraccion));
        lblTotalHours.setText(formatearTiempo(totalSegundos));
        lblCount.setText(String.valueOf(registros.size()));

        //  CÓDIGO MODIFICADO (Calcula la fracción y anima la batería)
        if (totalSegundos > 0) {
            double scoreFraccion = (double) totalSegundosTrabajo / totalSegundos; // Ej: 0.78
            updateProductivityScore(scoreFraccion); // Actualiza el texto Y sube/baja la barra cian
        } else {
            updateProductivityScore(0.0);
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
        if (registros == null || registros.isEmpty()) {
            return;
        }

        XYChart.Series<String, Number> seriesWork = new XYChart.Series<>();
        seriesWork.setName("Trabajo");

        XYChart.Series<String, Number> seriesDist = new XYChart.Series<>();
        seriesDist.setName("Distracción");

        // Mapas para agrupar y acumular minutos por cada hora ("08:00", "09:00", etc.)
        java.util.Map<String, Double> acumuladoTrabajo = new java.util.LinkedHashMap<>();
        java.util.Map<String, Double> acumuladoDistraccion = new java.util.LinkedHashMap<>();

        for (Registro r : registros) {
            if (r.getFechaRegistro() == null || r.getCategoria() == null) {
                continue;
            }

            String hora = r.getFechaRegistro().toLocalTime().toString().substring(0, 2) + ":00";
            double minutos = r.getDuracionSeg() / 60.0;

            if ("TRABAJO".equalsIgnoreCase(r.getCategoria())) {
                acumuladoTrabajo.put(hora, acumuladoTrabajo.getOrDefault(hora, 0.0) + minutos);
            } else {
                acumuladoDistraccion.put(hora, acumuladoDistraccion.getOrDefault(hora, 0.0) + minutos);
            }
        }

        // Pasamos los datos agrupados a las series de JavaFX
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
        // 1. Blindaje contra desbordamientos (mínimo 0%, máximo 100%)
        double sanitizedScore = Math.max(0.0, Math.min(1.0, score));

        // 2. Formateo y renderizado de texto instantáneo
        lblScorePercent.setText((int) (sanitizedScore * 100) + "%");

        // 3. Modificación del alto proporcional de la barra azul/cian
        double maxHeight = 160.0;
        scoreFill.setPrefHeight(maxHeight * sanitizedScore);
    }
}
