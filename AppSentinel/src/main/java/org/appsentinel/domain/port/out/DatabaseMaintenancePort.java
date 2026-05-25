package org.appsentinel.domain.port.out;

/**
 * Puerto de salida para operaciones de mantenimiento de la base de datos.
 *
 * ARQUITECTURA HEXAGONAL: El dominio solicita limpieza del historial;
 * la infraestructura ejecuta el comando SQL concreto.
 *
 * <p>No contiene lógica de temporización (eso es responsabilidad de un
 * servicio de aplicación). Solo define la operación atómica.</p>
 */
public interface DatabaseMaintenancePort {

    /**
     * Elimina todos los registros de actividad del día.
     *
     * <p>Operación destructiva e irreversible. El llamador debe asegurar
     * que el informe diario ya fue generado antes de invocar.</p>
     */
    void limpiarHistorialDiario();
}