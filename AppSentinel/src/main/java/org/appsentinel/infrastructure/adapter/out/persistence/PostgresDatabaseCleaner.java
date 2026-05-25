package org.appsentinel.infrastructure.adapter.out.persistence;

import org.appsentinel.domain.port.out.DatabaseMaintenancePort;

import java.sql.Connection;
import java.sql.Statement;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * PostgresDatabaseCleaner: Adaptador de SALIDA para mantenimiento de BD.
 *
 * Implementa {@link DatabaseMaintenancePort} usando PostgreSQL nativo.
 * Responsabilidad única: ejecutar TRUNCATE sobre registros_actividad.
 */
public class PostgresDatabaseCleaner implements DatabaseMaintenancePort {

    private static final Logger LOGGER = Logger.getLogger(PostgresDatabaseCleaner.class.getName());

    @Override
    public void limpiarHistorialDiario() {
        String sql = "TRUNCATE TABLE registros_actividad;";

        try (Connection conn = DatabaseConnection.getConnection();
             Statement stmt = conn.createStatement()) {

            stmt.execute(sql);
            LOGGER.log(Level.INFO, "[PERSISTENCIA] Tabla registros_actividad vaciada.");

        } catch (Exception e) {
            LOGGER.log(Level.SEVERE, "[ERROR] Fallo al limpiar historial diario", e);
        }
    }
}