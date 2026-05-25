package org.appsentinel.domain.port.out;

import org.appsentinel.domain.model.Registro;
import org.appsentinel.domain.model.ResumenActividad;

import java.util.List;
import java.util.Map;

/**
 * Puerto de salida para persistencia de registros de actividad.
 *
 * ARQUITECTURA HEXAGONAL — Interfaz pura sin lógica de implementación.
 *
 * <p>Responsabilidad: definir el contrato que el dominio espera de la
 * infraestructura de persistencia. Cero lógica, cero defaults con
 * comportamiento, cero acoplamiento a tecnología.</p>
 *
 * <p>Los métodos batch ({@link #guardarBatch}, {@link #guardarOActualizarBatch})
 * son contratos explícitos. Cada adaptador concreto (PostgreSQL, H2, mock)
 * implementa la estrategia de batch óptima para su tecnología:</p>
 * <ul>
 *   <li>PostgreSQL: {@code executeBatch()} con {@code PreparedStatement}</li>
 *   <li>H2: {@code executeBatch()} nativo</li>
 *   <li>Mock/Test: acumulación en lista en memoria</li>
 * </ul>
 *
 * <p>La decisión de usar batch vs individual pertenece al adaptador,
 * no al dominio. El dominio solo dice "aquí hay datos, persistelos".</p>
 *
 * @see org.appsentinel.infrastructure.adapter.out.PostgreSQLRepositoryAdapter
 */
public interface RegistroRepositoryPort {

    /**
     * Persiste un registro individual de actividad.
     *
     * @param registro Entidad de dominio a persistir. No debe ser {@code null}.
     */
    void guardar(Registro registro);

    /**
     * Batch insert optimizado.
     *
     * <p>El adaptador concreto decide la estrategia:
     * {@code executeBatch()} de JDBC, loop de inserts, u otra.</p>
     *
     * @param registros Lista de registros a persistir. {@code null} es no-op.
     */
    void guardarBatch(List<Registro> registros);

    /**
     * Acumula duración en un registro existente o inserta uno nuevo.
     *
     * <p>UPSERT: Si ya existe un registro para (usuario, app, categoría, fecha)
     * hoy, suma {@code duracionSeg} al valor existente. Si no, inserta nuevo.</p>
     *
     * <p>Garantiza una sola fila por app por día en la BD.</p>
     *
     * @param registro Registro con el delta de duración a acumular.
     */
    void guardarOActualizar(Registro registro);

    /**
     * Batch upsert optimizado.
     *
     * <p>El adaptador concreto implementa la estrategia de batch upsert
     * más eficiente para su tecnología de BD.</p>
     *
     * @param registros Lista de registros con deltas de duración. {@code null} es no-op.
     */
    void guardarOActualizarBatch(List<Registro> registros);

    /**
     * Recupera todos los registros del usuario para la fecha actual.
     *
     * <p>Con upsert activo, cada app aparece una sola vez (una fila por app).</p>
     *
     * @param usuario Identificador del usuario del sistema operativo.
     * @return Lista de registros, vacía si no hay datos.
     */
    List<Registro> obtenerTodosHoy(String usuario);

    /**
     * Filtra registros por categoría para la fecha actual.
     *
     * @param usuario   Identificador del usuario.
     * @param categoria Categoría del dominio ({@link org.appsentinel.domain.model.Categoria}).
     * @return Lista de registros filtrados, vacía si no hay coincidencias.
     */
    List<Registro> obtenerPorCategoria(String usuario, String categoria);

    /**
     * Resumen agrupado por aplicación.
     *
     * <p>Cada app aparece UNA SOLA VEZ con su tiempo total acumulado.</p>
     *
     * <p>NOTA: Con upsert activo, este método podría simplificarse a SELECT directo
     * sin GROUP BY, ya que la BD ya tiene una sola fila por app. Se mantiene
     * GROUP BY para compatibilidad con datos históricos pre-upsert.</p>
     *
     * @param usuario Identificador del usuario.
     * @return Map indexado por {@code nombre_actividad} → {@link ResumenActividad}.
     */
    Map<String, ResumenActividad> obtenerResumenPorApp(String usuario);
}