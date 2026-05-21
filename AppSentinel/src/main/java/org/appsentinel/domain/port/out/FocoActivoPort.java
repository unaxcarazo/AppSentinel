// ============================================
// org.appsentinel.domain.port.out.FocoActivoPort
// ============================================
package org.appsentinel.domain.port.out;

/**
 * Puerto de SALIDA del dominio para consultar el foco activo del usuario.
 *
 * ARQUITECTURA HEXAGONAL:
 * - Es un puerto de SALIDA (driven): la UI lo consume, el dominio lo implementa.
 * - No es un puerto de entrada: la UI no ordena nada, solo consulta estado.
 * - Los métodos son lecturas idempotentes sin efectos secundarios.
 *
 * FIX 2.2: Movido de domain.port.in a domain.port.out.
 * El dominio (TimeTrackingService) implementa este puerto para exponer
 * hacia la capa de presentación la actividad que tiene el foco del usuario
 * en este momento, sin revelar tipos internos ni estructuras de datos
 * del servicio.
 *
 * VALORES DE RETORNO:
 * - getPidFocoActivo(): PID del proceso de escritorio, o -1 si el foco es
 *   una web (tabId) o no hay foco detectado.
 * - getNombreFocoActivo(): nombre del proceso o dominio web, o null si no hay foco.
 * - esFocoWeb(): true si el foco activo proviene del navegador (WEB|).
 */
public interface FocoActivoPort {

    /**
     * PID del proceso con foco activo.
     *
     * @return PID del proceso de escritorio, -1 si es web o no hay foco.
     */
    int getPidFocoActivo();

    /**
     * Nombre del proceso o dominio web con foco activo.
     *
     * @return nombre legible (ej: "chrome", "youtube.com"), o null si no hay foco.
     */
    String getNombreFocoActivo();

    /**
     * Indica si el foco activo corresponde a una pestaña del navegador.
     *
     * @return true si el foco es WEB|, false si es SYS| o no hay foco.
     */
    boolean esFocoWeb();
}