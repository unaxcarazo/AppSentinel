package org.appsentinel.domain.port.out;

/**
 * RendimientoSistemaPort: Puerto de SALIDA para métricas de hardware en tiempo real.
 *
 * El dominio (y la capa de presentación a través de AppContext) consulta
 * este puerto sin saber si los datos vienen de OSHI, JMX o cualquier otra
 * librería de monitoreo del sistema operativo.
 *
 * Todas las métricas son instantáneas (snapshot del momento de la llamada),
 * no valores acumulados desde el inicio del proceso.
 */
public interface RendimientoSistemaPort {

    /*
     * Porcentaje de uso global de CPU del sistema entre 0.0 y 100.0.
     * Calculado entre dos muestras de ticks para reflejar el uso actual,
     * no el histórico.
     */
    double getCpuPorcentajeGlobal();

    /*
     * Porcentaje de uso de CPU de un proceso concreto entre 0.0 y 100.0.
     * Calculado con getProcessCpuLoadBetweenTicks() de OSHI.
     * Retorna -1.0 si el PID no existe o no es accesible.
     *
     * @param pid Identificador del proceso del sistema operativo.
     */
    double getCpuPorcentajePorProceso(int pid);

    /*
     * Memoria RAM actualmente usada por el sistema en megabytes.
     */
    long getRamUsadaMb();

    /*
     * Memoria RAM total instalada en el sistema en megabytes.
     */
    long getRamTotalMb();

    /*
     * Porcentaje de RAM usada entre 0.0 y 100.0.
     * Calculado como (usada / total) * 100.
     */
    double getRamPorcentaje();
}