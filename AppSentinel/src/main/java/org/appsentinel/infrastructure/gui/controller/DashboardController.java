package org.appsentinel.infrastructure.gui.controller;

import java.awt.Desktop;
import java.io.File;
import java.io.IOException;
import java.net.URL;
import java.util.ResourceBundle;
import javafx.fxml.FXML;
import javafx.fxml.Initializable;
import javafx.scene.chart.BarChart;
import javafx.scene.chart.XYChart;
import javafx.scene.control.Label;

public class DashboardController implements Initializable {

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
        // 1. Asignar valores de prueba a las tarjetas
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
}
