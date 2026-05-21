package org.appsentinel.domain.port.out;

import org.appsentinel.domain.model.Registro;
import org.appsentinel.domain.model.ResumenActividad;

import java.util.List;
import java.util.Map;

/**
 * Puerto de salida para persistencia de registros de actividad.
 *
 * ARQUITECTURA HEXAGONAL — Separación Escritura/Lectura:
 * <p>
 * Las escrituras ({@link #guardar}, {@link #guardarBatch}) son <em>fire-and-forget</em>
 * non-blocking cuando se implementan vía {@code BufferedRegistroRepositoryAdapter}.
 * El hilo del escáner nunca espera a PostgreSQL.
 * </p>
 * <p>
 * Las lecturas ({@link #obtenerTodosHoy}, {@link #obtenerPorCategoria},
 * {@link #obtenerResumenPorApp}) van directo al adaptador crudo (PostgreSQL),
 * sin interferir con el buffer de escritura. La UI consulta datos frescos
 * sin bloquearse con las escrituras del escáner.
 * </p>
 *
 * @see org.appsentinel.infrastructure.adapter.out.PostgreSQLRepositoryAdapter
 * @see org.appsentinel.infrastructure.adapter.out.BufferedRegistroRepositoryAdapter
 */
public interface RegistroRepositoryPort {

    /**
     * Persiste un registro individual de actividad.
     *
     * <p><strong>Non-blocking:</strong> Cuando se implementa vía buffer,
     * este método encola en memoria y retorna inmediatamente.</p>
     *
     * @param registro Entidad de dominio a persistir. No debe ser {@code null}.
     */
    void guardar(Registro registro);

    /**
     * Batch insert optimizado.
     *
     * <p>Default delega a {@link #guardar} individual. Los adaptadores de
     * infraestructura pueden overridear con {@code executeBatch()} de JDBC
     * para reducir round-trips de red.</p>
     *
     * @param registros Lista de registros a persistir. {@code null} es no-op.
     */
    default void guardarBatch(List<Registro> registros) {
        if (registros != null) {
            registros.forEach(this::guardar);
        }
    }

    /**
     * Recupera todos los registros del usuario para la fecha actual.
     *
     * <p>Ordenados por {@code fecha_registro} descendente (más reciente primero).</p>
     *
     * @param usuario Identificador del usuario del sistema operativo.
     * @return Lista de registros, vacía si no hay datos.
     */
    List<Registro> obtenerTodosHoy(String usuario);

    /**
     * Filtra registros por categoría para la fecha actual.
     *
     * <p>Ordenados por {@code duracion_seg} descendente (más tiempo primero).</p>
     *
     * @param usuario   Identificador del usuario.
     * @param categoria Categoría del dominio ({@link org.appsentinel.domain.model.Categoria}).
     * @return Lista de registros filtrados, vacía si no hay coincidencias.
     */
    List<Registro> obtenerPorCategoria(String usuario, String categoria);

    /**
     * Resumen agrupado por aplicación — <strong>FIX anti-saturación de UI</strong>.
     *
     * <p>Cada app o página web aparece <strong>UNA SOLA VEZ</strong> con su tiempo
     * total acumulado. Elimina duplicados de la vista cuando el usuario alterna
     * entre ventanas (ej: Discord → NetBeans → Discord no genera 3 filas, solo 1
     * fila "discord" con tiempo sumado).</p>
     *
     * <p>Implementación SQL: {@code GROUP BY nombre_actividad, categoria} +
     * {@code SUM(duracion_seg)} + {@code MAX(fecha_registro)}.</p>
     *
     * @param usuario Identificador del usuario.
     * @return Map indexado por {@code nombre_actividad} → {@link ResumenActividad}.
     *         Vacío si no hay registros hoy. Orden implícito por tiempo total DESC.
     */
    Map<String, ResumenActividad> obtenerResumenPorApp(String usuario);
}