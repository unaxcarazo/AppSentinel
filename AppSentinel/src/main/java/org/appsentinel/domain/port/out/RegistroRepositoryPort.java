package org.appsentinel.domain.port.out;

import org.appsentinel.domain.model.Registro;
import java.util.List;

/**
 * Puerto de salida para persistencia.
 * 
 * DECISIÓN ARQUITECTÓNICA — Separación Escritura/Lectura:
 * - Las escrituras (guardar/guardarBatch) son "fire-and-forget" non-blocking
 *   cuando se implementan vía BufferedRegistroRepositoryAdapter.
 * - Las lecturas (obtener*) van directo a PostgreSQL, sin interferir con el buffer.
 * - La UI consulta lecturas sin bloquearse con las escrituras del escáner.
 */
public interface RegistroRepositoryPort {

    void guardar(Registro registro);

    /*
     * Batch insert optimizado. Default delega a guardar() individual.
     * Los adaptadores de infraestructura pueden overridear con executeBatch().
     */
    default void guardarBatch(List<Registro> registros) {
        if (registros != null) {
            registros.forEach(this::guardar);
        }
    }

    List<Registro> obtenerTodosHoy(String usuario);

    List<Registro> obtenerPorCategoria(String usuario, String categoria);
}