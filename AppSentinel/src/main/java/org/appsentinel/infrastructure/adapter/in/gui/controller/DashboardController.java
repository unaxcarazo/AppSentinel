/*
 * Click nbfs://nbhost/SystemFileSystem/Templates/Licenses/license-default.txt to change this license
 * Click nbfs://nbhost/SystemFileSystem/Templates/javafx/FXMLController.java to edit this template
 */
package org.appsentinel.infrastructure.adapter.in.gui.controller;

import java.awt.Desktop;
import java.io.File;
import java.io.IOException;
import java.net.URL;
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
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import org.appsentinel.domain.model.Registro;
import org.appsentinel.domain.port.out.RegistroRepositoryPort;
import javafx.fxml.Initializable;
import javafx.scene.control.ScrollPane;
import org.appsentinel.infrastructure.bootstrap.AppContext;

/**
 * DashboardController: Gestiona el panel principal de métricas de
 * productividad. Integrado polimórficamente mediante la interfaz Controllable
 * del ecosistema del grupo.
 */
public class DashboardController implements Initializable, Controllable {

    private static final double LIMITE_TRABAJO_SEG = 5 * 3600; // 5 horas en segundos
    private static final double LIMITE_DISTRACCION_SEG = 1 * 3600; // 1 hora en segundos

    private volatile long ultimaActualizacionUi = 0;
    private static final long MIN_MS_ENTRE_ACTUALIZACIONES = 2000; // 2 segundos

    private ScheduledExecutorService uiScheduler;
    private RegistroRepositoryPort repository;
    private String usuarioActual;

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

    // NUEVA ETIQUETA PARA EL MENSAJE DEL SCORE
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
                System.err.println("❌ ERROR CRÍTICO: ¡El archivo CSS no se encuentra en 'src/main/resources/styles/'!");
            } else {
                System.out.println("✅ Archivo CSS encontrado con éxito en: " + cssURL.toExternalForm());
                rootPane.getStylesheets().add(cssURL.toExternalForm());
            }
        }

        Platform.runLater(() -> {
            // Estructura simétrica de los paneles intermedios e inferiores
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

            if (vboxActivityLog != null && vboxBlockingLog != null) {
                if (vboxActivityLog.getParent() instanceof Region && vboxBlockingLog.getParent() instanceof Region) {
                    Region tarjetaActivity = (Region) vboxActivityLog.getParent();
                    Region tarjetaBlocking = (Region) vboxBlockingLog.getParent();
                    if (tarjetaActivity.getParent() instanceof HBox) {
                        HBox filaInferior = (HBox) tarjetaActivity.getParent();
                        tarjetaActivity.prefWidthProperty().bind(filaInferior.widthProperty().divide(2.0).subtract(15));
                        tarjetaBlocking.prefWidthProperty().bind(filaInferior.widthProperty().divide(2.0).subtract(15));
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

        this.uiScheduler.scheduleAtFixedRate(() -> {
            actualizarVistaSegura();
        }, 5, 5, TimeUnit.SECONDS);
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
        if (texto.length() <= maxCaracteres) {
            return texto;
        }
        return texto.substring(0, maxCaracteres) + "...";
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
            double progreso = (double) app.getDuracionSeg() / LIMITE_TRABAJO_SEG;
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

    private void actualizarLogActividad(List<Registro> registros) {
        vboxActivityLog.getChildren().clear();
        if (registros == null || registros.isEmpty()) {
            return;
        }

        for (Registro r : registros) {
            if (r == null || r.getFechaRegistro() == null || r.getNombreActividad() == null) {
                continue;
            }

            HBox row = new HBox();
            row.getStyleClass().add("log-row");
            row.setAlignment(Pos.CENTER_LEFT);
            row.setSpacing(15);

            String categoria = r.getCategoria() != null ? r.getCategoria().trim().toUpperCase() : "";
            boolean esTrabajo = "TRABAJO".equals(categoria) || r.getNombreActividad().toLowerCase().contains("netbeans");

            String emoji = esTrabajo ? "💻" : "🌐";
            String estiloIcono = esTrabajo ? "icon-box-cian" : "icon-box-naranja";

            StackPane iconBox = new StackPane(new Label(emoji));
            iconBox.getStyleClass().addAll("icon-box", estiloIcono);

            VBox textData = new VBox();
            HBox.setHgrow(textData, Priority.ALWAYS);

            Label appName = new Label(sanitizarTextoLargo(r.getNombreActividad(), 40));
            appName.getStyleClass().add("log-app-title");

            String horaFormateada = "00:00";
            if (r.getFechaRegistro().toLocalTime() != null) {
                String rawTime = r.getFechaRegistro().toLocalTime().toString();
                if (rawTime.length() >= 5) {
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
            Label lblNoData = new Label("No blocked events today");
            lblNoData.getStyleClass().add("log-subtext");
            vboxBlockingLog.getChildren().add(lblNoData);
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
            HBox.setHgrow(textData, Priority.ALWAYS);

            Label appName = new Label(sanitizarTextoLargo(b.getNombreActividad(), 40));
            appName.getStyleClass().add("log-app-title");

            String horaBloqueo = "00:00";
            if (b.getFechaRegistro().toLocalTime() != null) {
                String rawTime = b.getFechaRegistro().toLocalTime().toString();
                if (rawTime.length() >= 5) {
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
            if (lblScoreMessage != null) {
                lblScoreMessage.setText(""); // Reseteo
            }
            if (progressWorkCard != null) {
                progressWorkCard.setProgress(0.0);
            }
            if (progressDistractionCard != null) {
                progressDistractionCard.setProgress(0.0);
            }
            return;
        }

        long totalSegundosTrabajo = 0;
        long totalSegundosDistraccion = 0;

        for (Registro r : registros) {
            if (r == null) {
                continue;
            }
            String cat = r.getCategoria() != null ? r.getCategoria().trim().toUpperCase() : "";
            String nombre = r.getNombreActividad() != null ? r.getNombreActividad().toLowerCase() : "";

            if ("TRABAJO".equals(cat) || nombre.contains("netbeans")) {
                totalSegundosTrabajo += r.getDuracionSeg();
            } else if ("DISTRACCION".equals(cat)) {
                totalSegundosDistraccion += r.getDuracionSeg();
            }
        }

        long totalSegundos = totalSegundosTrabajo + totalSegundosDistraccion;

        lblWorkTime.setText(formatearTiempo(totalSegundosTrabajo));
        lblDistractionTime.setText(formatearTiempo(totalSegundosDistraccion));
        lblTotalHours.setText(formatearTiempo(totalSegundos));

        lblCount.setText(String.valueOf(registros.size()));

        if (progressWorkCard != null) {
            double progTrabajo = (double) totalSegundosTrabajo / LIMITE_TRABAJO_SEG;
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

    // =========================================================================
    // 📊 GRÁFICAS INTACTAS
    // =========================================================================
    private void actualizarGraficoReal(List<Registro> registros) {
        if (registros == null || registros.isEmpty()) {
            Platform.runLater(() -> barChartActivity.getData().clear());
            return;
        }

        XYChart.Series<String, Number> seriesWork = new XYChart.Series<>();
        seriesWork.setName("Trabajo");

        XYChart.Series<String, Number> seriesDist = new XYChart.Series<>();
        seriesDist.setName("Distracción");

        // TreeMap garantiza que las horas se ordenen automáticamente (09:00, 10:00, 11:00...)
        java.util.Map<String, Double> acumuladoTrabajo = new java.util.TreeMap<>();
        java.util.Map<String, Double> acumuladoDistraccion = new java.util.TreeMap<>();

        double limiteDistraccionHoy = 0;
        for (Registro r : registros) {
            if (r != null && "DISTRACCION".equalsIgnoreCase(r.getCategoria())) {
                limiteDistraccionHoy += r.getDuracionSeg();
            }
        }
        double limiteMinutosDistraccion = limiteDistraccionHoy / 60.0;

        for (Registro r : registros) {
            if (r == null || r.getFechaRegistro() == null || r.getFechaRegistro().toLocalTime() == null) {
                continue;
            }

            // 🛠️ SOLUCIÓN AQUÍ: Extraemos la hora numérica y forzamos el formato estricto "HH:00"
            int horaNumerica = r.getFechaRegistro().toLocalTime().getHour();
            String horaFranja = String.format("%02d:00", horaNumerica);

            double segundos = r.getDuracionSeg();
            if (segundos <= 0) {
                segundos = 1.0;
            }
            double minutosReales = segundos / 60.0;

            String cat = r.getCategoria() != null ? r.getCategoria().trim().toUpperCase() : "";
            String nombre = r.getNombreActividad() != null ? r.getNombreActividad().toLowerCase() : "";

            if (!acumuladoTrabajo.containsKey(horaFranja)) {
                acumuladoTrabajo.put(horaFranja, 0.0);
                acumuladoDistraccion.put(horaFranja, 0.0);
            }

            // Clasificación limpia e independiente por hora
            if ("TRABAJO".equals(cat) || nombre.contains("netbeans") || nombre.contains("java")) {
                acumuladoTrabajo.put(horaFranja, acumuladoTrabajo.get(horaFranja) + minutosReales);
            } else {
                if ("DISTRACCION".equals(cat) || nombre.contains("chrome") || nombre.contains("youtube") || nombre.contains("discord")) {
                    acumuladoDistraccion.put(horaFranja, acumuladoDistraccion.get(horaFranja) + minutosReales);
                }
            }
        }

        final double maxDistraccionPermitida = Math.max(3.0, limiteMinutosDistraccion);
        acumuladoDistraccion.keySet().forEach(hora -> {
            if (acumuladoDistraccion.get(hora) > maxDistraccionPermitida) {
                acumuladoDistraccion.put(hora, maxDistraccionPermitida / Math.max(1, acumuladoDistraccion.size()));
            }
        });

        // Pasamos los datos ordenados a las series de la gráfica
        acumuladoTrabajo.forEach((hora, mins) -> seriesWork.getData().add(new XYChart.Data<>(hora, mins)));
        acumuladoDistraccion.forEach((hora, mins) -> seriesDist.getData().add(new XYChart.Data<>(hora, mins)));

        Platform.runLater(() -> {
            javafx.collections.ObservableList<XYChart.Series<String, Number>> dataLista
                    = javafx.collections.FXCollections.observableArrayList();
            dataLista.addAll(seriesWork, seriesDist);
            barChartActivity.setData(dataLista);
        });
    }

    // =========================================================================
    // 🧠 PRODUCTIVITY SCORE (CORREGIDO Y SEGURO PARA LAMBDAS)
    // =========================================================================
    public void updateProductivityScore(List<Registro> registros) {
        int porcentajeFinal = calcularPorcentajeProductividad(registros);

        Platform.runLater(() -> {
            // Actualizamos el porcentaje numérico
            lblScorePercent.setText(porcentajeFinal + "%");

            // Declaramos las variables aquí dentro para que sean 100% seguras en la UI
            String mensajeEstado;
            String colorHex;

            if (porcentajeFinal >= 80) {
                mensajeEstado = "EXCELENT WORK!";
                colorHex = "#00FF7F"; // Verde neón
            } else if (porcentajeFinal >= 60) {
                mensajeEstado = "GOOD JOB! KEEP IT UP.";
                colorHex = "#FFA500"; // Naranja
            } else if (porcentajeFinal >= 40) {
                mensajeEstado = "YOU CAN DO BETTER.";
                colorHex = "#FFA500"; // Amarillo/Naranja
            } else if (porcentajeFinal > 0) {
                mensajeEstado = "TOO MANY DISTRACTIONS!";
                colorHex = "#F00C26"; // Rojo intenso
            } else {
                mensajeEstado = "NO DATA YET.";
                colorHex = "#888888"; // Gris
            }

            // Aplicamos el texto y el estilo de forma segura
            if (lblScoreMessage != null) {
                lblScoreMessage.setText(mensajeEstado);
                lblScoreMessage.setStyle("-fx-text-fill: " + colorHex + "; -fx-font-weight: bold;");
            } else {
                System.out.println("⚠️ ATENCIÓN: lblScoreMessage es null. Revisa el fx:id en SceneBuilder.");
            }

            // Ajustar la altura de la barra azul de fondo
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

        double segundosTrabajo = 0;
        double segundosDistraccion = 0;

        for (Registro r : registros) {
            if (r == null) {
                continue;
            }

            String cat = r.getCategoria() != null ? r.getCategoria().trim().toUpperCase() : "";
            String nombre = r.getNombreActividad() != null ? r.getNombreActividad().toLowerCase() : "";

            // CLASIFICACIÓN IDÉNTICA A LA DE LAS TARJETAS (Card metrics)
            if ("TRABAJO".equals(cat) || nombre.contains("netbeans")) {
                segundosTrabajo += r.getDuracionSeg();
            } else if ("DISTRACCION".equals(cat)) {
                segundosDistraccion += r.getDuracionSeg();
            }
        }

        // 1. Pasamos a minutos exactos
        double minutosTrabajo = segundosTrabajo / 60.0;
        double minutosDistraccion = segundosDistraccion / 60.0;
        double minutosTotales = minutosTrabajo + minutosDistraccion;

        if (minutosTotales <= 0) {
            return 0;
        }

        // 2. Tu regla de 3 estricta: (work time * 100) / total hours
        double resultado = (minutosTrabajo * 100.0) / minutosTotales;

        int porcentajeFinal = (int) Math.round(resultado);

        return Math.max(0, Math.min(100, porcentajeFinal));
    }

    private void actualizarVistaSegura() {
        long ahora = System.currentTimeMillis();
        if (ahora - ultimaActualizacionUi < MIN_MS_ENTRE_ACTUALIZACIONES) {
            return;
        }
        ultimaActualizacionUi = ahora;

        Platform.runLater(this::recargarDatosDesdeBd);
    }

    private void recargarDatosDesdeBd() {
        if (repository != null && usuarioActual != null) {
            System.out.println("Cargando información optimizada desde la base de datos...");

            List<Registro> topTrabajo = repository.obtenerTopTrabajo(usuarioActual, 5);
            List<Registro> topDistracciones = repository.obtenerTopDistracciones(usuarioActual, 5);
            List<Registro> actividad = repository.obtenerActividadHoy(usuarioActual);
            List<Registro> bloqueos = repository.obtenerBloqueosHoy(usuarioActual);

            if ((topTrabajo == null || topTrabajo.isEmpty()) && actividad != null && !actividad.isEmpty()) {
                topTrabajo = actividad.stream()
                        .filter(r -> r != null && (("TRABAJO".equalsIgnoreCase(r.getCategoria() != null ? r.getCategoria().trim() : ""))
                        || (r.getNombreActividad() != null && r.getNombreActividad().toLowerCase().contains("netbeans"))))
                        .collect(Collectors.groupingBy(Registro::getNombreActividad, Collectors.summingLong(Registro::getDuracionSeg)))
                        .entrySet().stream()
                        .map(entry -> {
                            Registro reg = new Registro();
                            reg.setNombreActividad(entry.getKey());
                            reg.setDuracionSeg(entry.getValue());
                            reg.setCategoria("TRABAJO");
                            return reg;
                        })
                        .sorted((r1, r2) -> Long.compare(r2.getDuracionSeg(), r1.getDuracionSeg()))
                        .limit(5)
                        .collect(Collectors.toList());
            }

            actualizarCards(actividad);
            actualizarRankingTrabajo(topTrabajo);
            actualizarRankingDistracciones(topDistracciones);
            actualizarLogActividad(actividad);
            actualizarLogBloqueos(bloqueos);
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
