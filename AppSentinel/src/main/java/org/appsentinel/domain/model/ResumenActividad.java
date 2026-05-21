package org.appsentinel.domain.model;

import java.time.LocalDateTime;

/**
 * ResumenActividad: DTO de dominio para agregación de tiempo por actividad.
 *
 * ARQUITECTURA HEXAGONAL:
 * - Vive en {@code domain.model}, no en puertos ni adaptadores.
 * - Es inmutable (record) para garantizar consistencia en lecturas concurrentes.
 * - La capa de aplicación/UI lo consume para mostrar UNA SOLA FILA por app,
 *   eliminando duplicados cuando el usuario alterna entre ventanas.
 *
 * PROVENIENCIA:
 * - Generado por {@code RegistroRepositoryPort.obtenerResumenPorApp()} que
 *   ejecuta {@code GROUP BY nombre_actividad, categoria} en PostgreSQL.
 * - No se persiste como entidad; es una proyección agregada de {@link Registro}.
 *
 * @param nombreActividad Nombre de la app o web (ej: "discord", "Web: YouTube")
 * @param categoria       Clasificación del dominio ({@link Categoria})
 * @param tiempoTotalSeg  Suma acumulada de {@code duracionSeg} para esta app hoy
 * @param ultimaVez       Timestamp del último registro detectado (MAX)
 */
public record ResumenActividad(
    String nombreActividad,
    String categoria,
    long tiempoTotalSeg,
    LocalDateTime ultimaVez
) {
    /**
     * Formatea {@code tiempoTotalSeg} a representación legible "Xm Ys".
     * Delega la lógica de presentación al dominio para evitar
     * duplicación en múltiples adaptadores de salida (HTML, JSON, UI).
     *
     * @return Cadena formateada, ej: "23m 15s"
     */
    public String tiempoFormateado() {
        return (tiempoTotalSeg / 60) + "m " + (tiempoTotalSeg % 60) + "s";
    }
}