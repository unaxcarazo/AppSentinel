package org.appsentinel.domain.service;

import org.appsentinel.domain.port.out.DatabaseMaintenancePort;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * MantenimientoDiarioService: Servicio de aplicación para tareas programadas.
 *
 * <p>No pertenece al dominio puro (no contiene lógica de negocio de
 * seguimiento ni bloqueo). Orquesta la limpieza diaria de la BD.</p>
 *
 * <p>Se inicia en {@code AppWiring} para funcionar en ambos modos:
 * GUI (JavaFX) y headless (tests).</p>
 */
public class MantenimientoDiarioService {

    private static final Logger LOGGER = Logger.getLogger(MantenimientoDiarioService.class.getName());

    private final ScheduledExecutorService scheduler =
        Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "Mantenimiento-Diario");
            t.setDaemon(true);
            return t;
        });

    private final DatabaseMaintenancePort maintenance;

    public MantenimientoDiarioService(DatabaseMaintenancePort maintenance) {
        this.maintenance = maintenance;
    }

    /**
     * Programa la limpieza diaria a medianoche.
     *
     * <p>Primer disparo: calcula segundos hasta 00:00 del día siguiente.
     * Período: 24 horas exactas.</p>
     */
    public void iniciar() {
        long segundosHastaMedianoche = calcularSegundosHastaMedianoche();

        scheduler.scheduleAtFixedRate(
            () -> {
                try {
                    LOGGER.log(Level.INFO, "[MANTENIMIENTO] Ejecutando limpieza diaria...");
                    maintenance.limpiarHistorialDiario();
                } catch (Exception e) {
                    LOGGER.log(Level.SEVERE, "[MANTENIMIENTO] Limpieza fallida", e);
                }
            },
            segundosHastaMedianoche,
            24L * 60 * 60,  // 24 horas en segundos
            TimeUnit.SECONDS
        );

        LOGGER.log(Level.INFO,
            "[MANTENIMIENTO] Próxima limpieza en {0}h {1}m",
            new Object[]{
                segundosHastaMedianoche / 3600,
                (segundosHastaMedianoche % 3600) / 60
            });
    }

    /**
     * Detiene el scheduler de forma ordenada.
     */
    public void detener() {
        scheduler.shutdown();
        try {
            if (!scheduler.awaitTermination(5, TimeUnit.SECONDS)) {
                scheduler.shutdownNow();
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            scheduler.shutdownNow();
        }
        LOGGER.log(Level.INFO, "[MANTENIMIENTO] Scheduler detenido.");
    }

    private long calcularSegundosHastaMedianoche() {
        LocalDateTime ahora = LocalDateTime.now();
        LocalDateTime medianoche = ahora.toLocalDate().plusDays(1).atStartOfDay();
        return Duration.between(ahora, medianoche).getSeconds();
    }
}