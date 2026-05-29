package org.appsentinel.domain.service;

import org.appsentinel.domain.port.out.DatabaseMaintenancePort;

import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * MantenimientoDiarioService: Orquestador de tareas de mantenimiento.
 *
 * Responsabilidad: decidir CUÁNDO ejecutar la limpieza (política de aplicación).
 * La ejecución concreta se delega a DatabaseMaintenancePort.
 *
 * FIX: Eliminado scheduler de 24h. La app de escritorio no corre 24/7.
 * La limpieza se ejecuta una vez al arranque.
 */
public class MantenimientoDiarioService {

    private static final Logger LOGGER = Logger.getLogger(MantenimientoDiarioService.class.getName());

    public MantenimientoDiarioService(DatabaseMaintenancePort maintenance) {
        LOGGER.log(Level.INFO, "[MANTENIMIENTO] Ejecutando limpieza de arranque...");
        try {
            maintenance.limpiarHistorialDiario();
            LOGGER.log(Level.INFO, "[MANTENIMIENTO] Limpieza completada.");
        } catch (Exception e) {
            LOGGER.log(Level.SEVERE, "[MANTENIMIENTO] Limpieza fallida", e);
        }
    }

    /**
     * No-op: no hay hilos ni schedulers que detener.
     * Se mantiene para compatibilidad con AppWiring.detenerTodo().
     */
    public void detener() {
        // Sin recursos que liberar
    }
}