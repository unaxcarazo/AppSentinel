/*
package org.appsentinel.infrastructure.adapter.in.gui.controller;

import javafx.animation.FadeTransition;
import javafx.animation.Timeline;
import javafx.application.Platform;
import org.appsentinel.infrastructure.bootstrap.AppContext;
import javafx.fxml.FXML;
import javafx.fxml.Initializable;
import javafx.scene.control.*;
import javafx.scene.layout.HBox;
import javafx.scene.layout.VBox;
import javafx.scene.paint.Color;
import javafx.scene.paint.CycleMethod;
import javafx.scene.paint.RadialGradient;
import javafx.scene.paint.Stop;
import javafx.scene.shape.Rectangle;
import javafx.util.Duration;
import org.appsentinel.domain.port.in.BlockingUseCase;
import org.appsentinel.infrastructure.adapter.in.gui.controller.Controllable; // IMPORTACIÓN CORREGIDA (Línea 18)

import java.net.URL;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.ResourceBundle;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

public class DeepFocusController implements Initializable, Controllable {

    @FXML
    private Rectangle gradientBg;
    @FXML
    private Button btnModeChrono, btnModeSchedule;
    @FXML
    private VBox panelChrono, panelSchedule;
    @FXML
    private Label lblTimer;
    @FXML
    private HBox boxAddTime;
    @FXML
    private DatePicker datePicker;
    @FXML
    private ComboBox<String> comboHH, comboMM;
    @FXML
    private Button btnStart, btnStop;

    private int countdownSeconds = 0;
    private ScheduledExecutorService timerService;
    private BlockingUseCase blockingUseCase;

    private RadialGradient chronometerGradient;
    private RadialGradient scheduleGradient;

    @Override
    public void init(AppContext ctx) {
        this.blockingUseCase = ctx.detector();
    }

    @Override
    public void initialize(URL url, ResourceBundle rb) {
        setupBackground();
        setupTimeSelectors();
        showChrono(); // Default mode
    }

    private void setupBackground() {
        chronometerGradient = new RadialGradient(0, 0, 0.5, 0.5, 0.8, true, CycleMethod.NO_CYCLE,
                new Stop(0, Color.web("#FF3D00", 0.3)),
                new Stop(1, Color.TRANSPARENT));

        scheduleGradient = new RadialGradient(0, 0, 0.5, 0.5, 0.8, true, CycleMethod.NO_CYCLE,
                new Stop(0, Color.web("#00E5FF", 0.2)),
                new Stop(1, Color.TRANSPARENT));

        if (gradientBg != null) {
            gradientBg.setFill(chronometerGradient);
            FadeTransition ft = new FadeTransition(Duration.seconds(3.5), gradientBg);
            ft.setFromValue(0.3);
            ft.setToValue(0.8);
            ft.setCycleCount(Timeline.INDEFINITE);
            ft.setAutoReverse(true);
            ft.play();
        }
    }

    private void setupTimeSelectors() {
        if (comboHH == null || comboMM == null || datePicker == null) return;
        
        for (int i = 0; i < 24; i++) {
            comboHH.getItems().add(String.format("%02d", i));
        }
        for (int i = 0; i < 60; i++) {
            comboMM.getItems().add(String.format("%02d", i));
        }

        comboHH.getSelectionModel().select(String.format("%02d", LocalTime.now().getHour()));
        comboMM.getSelectionModel().select(String.format("%02d", LocalTime.now().getMinute()));
        datePicker.setValue(LocalDate.now());
    }

    @FXML
    private void showChrono() {
        // CORRECCIÓN DE SEGURIDAD (Línea 50)
        if (btnModeChrono != null) btnModeChrono.getStyleClass().add("df-mode-btn-orange");
        if (btnModeSchedule != null) btnModeSchedule.getStyleClass().remove("df-mode-btn-cyan");
        if (gradientBg != null) gradientBg.setFill(chronometerGradient);
        if (panelChrono != null) {
            panelChrono.setVisible(true);
            panelChrono.setManaged(true);
        }
        if (panelSchedule != null) {
            panelSchedule.setVisible(false);
            panelSchedule.setManaged(false);
        }
    }

    @FXML
    private void showSchedule() {
        // CORRECCIÓN DE SEGURIDAD (Línea 57)
        if (btnModeSchedule != null) btnModeSchedule.getStyleClass().add("df-mode-btn-cyan");
        if (btnModeChrono != null) btnModeChrono.getStyleClass().remove("df-mode-btn-orange");
        if (gradientBg != null) gradientBg.setFill(scheduleGradient);
        if (panelChrono != null) {
            panelChrono.setVisible(false);
            panelChrono.setManaged(false);
        }
        if (panelSchedule != null) {
            panelSchedule.setVisible(true);
            panelSchedule.setManaged(true);
        }
    }

    @FXML
    private void add5Min() {
        addTime(5 * 60);
    }

    @FXML
    private void add15Min() {
        addTime(15 * 60);
    }

    @FXML
    private void add30Min() {
        addTime(30 * 60);
    }

    @FXML
    private void resetTimer() {
        countdownSeconds = 0;
        updateTimerDisplay();
    }

    private void addTime(int seconds) {
        countdownSeconds += seconds;
        updateTimerDisplay();
    }

    private void updateTimerDisplay() {
        int h = countdownSeconds / 3600;
        int m = (countdownSeconds % 3600) / 60;
        int s = countdownSeconds % 60;
        Platform.runLater(() -> {
            if (lblTimer != null) {
                lblTimer.setText(String.format("%02d:%02d:%02d", h, m, s));
            }
        });
    }

    @FXML
    private void startFocus() {
        if (countdownSeconds <= 0) {
            return;
        }
        if (timerService != null && !timerService.isShutdown()) {
            return;
        }

        if (boxAddTime != null) {
            boxAddTime.setVisible(false);
            boxAddTime.setManaged(false);
        }

        timerService = Executors.newSingleThreadScheduledExecutor();
        timerService.scheduleAtFixedRate(() -> {
            countdownSeconds--;
            updateTimerDisplay();
            if (countdownSeconds <= 0) {
                stopFocus();
            }
        }, 1, 1, TimeUnit.SECONDS);
    }

    @FXML
    private void stopFocus() {
        if (timerService != null) {
            timerService.shutdownNow();
        }
        Platform.runLater(() -> {
            if (boxAddTime != null) {
                boxAddTime.setVisible(true);
                boxAddTime.setManaged(true);
            }
        });
    }

    @FXML
    private void initiateLockdown() {
        if (datePicker == null || comboHH == null || comboMM == null) return;
        
        LocalDate targetDate = datePicker.getValue();
        int h = Integer.parseInt(comboHH.getValue());
        int m = Integer.parseInt(comboMM.getValue());
        LocalDateTime targetTime = targetDate.atTime(h, m);
        LocalDateTime now = LocalDateTime.now();

        long diffSeconds = java.time.Duration.between(now, targetTime).getSeconds();
        if (diffSeconds > 0) {
            countdownSeconds = (int) diffSeconds;
            updateTimerDisplay();
            showChrono();
            startFocus();
        } else {
            Alert a = new Alert(Alert.AlertType.ERROR, "Target time must be in the future!");
            a.show();
        }
    }

    @FXML
    private void handleEmergencyExit() {
        Alert alert = new Alert(Alert.AlertType.CONFIRMATION, "Abort Deep Focus?");
        alert.showAndWait().ifPresent(res -> {
            if (res == ButtonType.OK) {
                stopFocus();
            }
        });
    }
}*/