package org.appsentinel.infrastructure.adapter.out.persistence;

import org.appsentinel.domain.port.out.DatabaseMaintenancePort;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * PostgresDatabaseCleaner: Adaptador de SALIDA para mantenimiento de BD.
 *
 * FIX: Cambia TRUNCATE (borra TODO sin condición) por DELETE con filtro de fecha.
 * Esto hace la limpieza idempotente: se puede ejecutar en cualquier momento
 * y solo afecta datos de días anteriores, no el día en curso.
 */
public class PostgresDatabaseCleaner implements DatabaseMaintenancePort {

    private static final Logger LOGGER = Logger.getLogger(PostgresDatabaseCleaner.class.getName());

    @Override
    public void limpiarHistorialDiario() {
        // FIX: Solo borrar registros de días anteriores, no el día actual.
        // El reporte HTML del día se genera al cierre; los datos de hoy deben persistir.
        String sql = """
            DELETE FROM registros_actividad
            WHERE fecha_registro < CURRENT_DATE
            """;

        try (Connection conn = DatabaseConnection.getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql)) {

            int filas = stmt.executeUpdate();
            LOGGER.log(Level.INFO,
                "[PERSISTENCIA] Limpieza ejecutada: {0} registros de días anteriores eliminados.",
                filas);

        } catch (Exception e) {
            LOGGER.log(Level.SEVERE, "[ERROR] Fallo al limpiar historial diario", e);
        }
    }
}