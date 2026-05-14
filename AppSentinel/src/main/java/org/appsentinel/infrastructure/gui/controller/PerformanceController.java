/*
 * Click nbfs://nbhost/SystemFileSystem/Templates/Licenses/license-default.txt to change this license
 * Click nbfs://nbhost/SystemFileSystem/Templates/javafx/FXMLController.java to edit this template
 */
package org.appsentinel.infrastructure.gui.controller;

import java.net.URL;
import java.util.ResourceBundle;
import javafx.fxml.Initializable;

import javafx.fxml.FXML;
import javafx.scene.chart.AreaChart;
import javafx.scene.control.Label;
import javafx.scene.control.ProgressBar;
import javafx.scene.control.TableColumn;
import javafx.scene.control.TableView;
import org.appsentinel.domain.service.TimeTrackingService;

/**
 * FXML Controller class
 *
 * @author DAW1
 */
public class PerformanceController implements Initializable {

    @FXML private AreaChart<Number, Number> netChart;
    @FXML private TableView<?> table;
    
    @FXML private Label lblCpuPerc;
    @FXML private Label lblCpuSpeed;
    @FXML private Label lblCpuCores;
    @FXML private ProgressBar pbCpu;
    
    @FXML private Label lblRamPerc;
    @FXML private Label lblRamUsed;
    @FXML private Label lblRamTotal;
    @FXML private ProgressBar pbRam;
    
    @FXML private Label lblNetDown;
    @FXML private Label lblNetUp;

    @FXML private TableColumn<?, ?> colAppName;
    @FXML private TableColumn<?, ?> colType;
    @FXML private TableColumn<?, ?> colCpu;
    @FXML private TableColumn<?, ?> colMem;
    @FXML private TableColumn<?, ?> colTime;

    private TimeTrackingService tracking;

    public void init(TimeTrackingService tracking) {
        this.tracking = tracking;
    }

    /**
     * Initializes the controller class.
     */
    @Override
    public void initialize(URL url, ResourceBundle rb) {
        // Visual setup only
    }    
    
}
