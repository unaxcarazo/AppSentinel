package org.appsentinel.infrastructure.adapter.out;

import oshi.SystemInfo;
import oshi.hardware.CentralProcessor;
import oshi.hardware.GlobalMemory;
import oshi.software.os.OSProcess;
import oshi.software.os.OperatingSystem;
import org.appsentinel.domain.port.out.RendimientoSistemaPort;

import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * OshiRendimientoAdapter: Adaptador de SALIDA para métricas de hardware en tiempo real.
 *
 * Implementa RendimientoSistemaPort usando la librería OSHI (oshi-core 6.6.1).
 *
 * DIFERENCIA CLAVE CON ProcessHandle.totalCpuDuration():
 * - ProcessHandle.totalCpuDuration() devuelve CPU acumulada desde el inicio
 *   del proceso. Comparar ese valor entre procesos no refleja el uso actual.
 * - OSHI usa getProcessCpuLoadBetweenTicks() que mide la diferencia de ticks
 *   entre dos muestras separadas en el tiempo, igual que el Administrador de
 *   Tareas de Windows. Eso sí es uso de CPU en tiempo real.
 *
 * CICLO DE MUESTREO:
 * CentralProcessor requiere dos llamadas a getSystemCpuLoadBetweenTicks()
 * separadas por al menos 1 segundo para producir un resultado válido.
 * La primera llamada siempre devuelve 0.0 o un valor impreciso.
 * Por eso se guarda el estado de ticks entre llamadas (prevTicks, prevProcTicks).
 *
 * THREAD SAFETY:
 * Esta clase no es thread-safe por diseño. JavaFX llama a los métodos desde
 * el hilo de la UI (AnimationTimer o Platform.runLater). Si se necesita
 * acceso concurrente, sincronizar externamente.
 */
public class OshiRendimientoAdapter implements RendimientoSistemaPort {

    private static final Logger LOGGER = Logger.getLogger(OshiRendimientoAdapter.class.getName());

    private final CentralProcessor processor;
    private final GlobalMemory memory;
    private final OperatingSystem os;

    // Estado de ticks previos para cálculo de CPU global entre muestras
    private long[] prevTicks = new long[CentralProcessor.TickType.values().length];

    // Estado de ticks previos por proceso para cálculo de CPU por PID
    private long prevProcTicks = 0L;
    private int  prevProcPid   = -1;

    public OshiRendimientoAdapter() {
        SystemInfo si = new SystemInfo();
        this.processor = si.getHardware().getProcessor();
        this.memory    = si.getHardware().getMemory();
        this.os        = si.getOperatingSystem();

        // Primera muestra inicial para que la siguiente llamada tenga referencia
        this.prevTicks = processor.getSystemCpuLoadTicks();

        LOGGER.log(Level.INFO, "[OSHI] Adaptador de rendimiento inicializado. CPU: {0} cores",
            processor.getLogicalProcessorCount());
    }

    // -------------------------------------------------------------------------
    // CPU global
    // -------------------------------------------------------------------------

    /*
     * Devuelve el porcentaje de CPU global del sistema entre 0.0 y 100.0.
     *
     * Usa getSystemCpuLoadBetweenTicks() con los ticks guardados de la llamada
     * anterior. La primera llamada tras el constructor ya tiene referencia,
     * por lo que a partir de la segunda llamada el valor es preciso.
     */
    @Override
    public double getCpuPorcentajeGlobal() {
        try {
            double carga = processor.getSystemCpuLoadBetweenTicks(prevTicks) * 100.0;
            prevTicks = processor.getSystemCpuLoadTicks(); // guardar para la próxima llamada
            return Math.min(100.0, Math.max(0.0, carga));
        } catch (Exception e) {
            LOGGER.log(Level.WARNING, "[OSHI] Error obteniendo CPU global", e);
            return 0.0;
        }
    }

    // -------------------------------------------------------------------------
    // CPU por proceso
    // -------------------------------------------------------------------------

    /**
     * Devuelve el porcentaje de CPU de un proceso concreto entre 0.0 y 100.0.
     *
     * Si el PID es distinto al de la llamada anterior, reinicia los ticks
     * acumulados para ese proceso y devuelve 0.0 en la primera muestra
     * (sin datos previos no hay diferencia que calcular).
     *
     * @param pid PID del proceso a medir.
     * @return Porcentaje de CPU entre 0.0 y 100.0, o -1.0 si el PID no existe.
     */
    @Override
    public double getCpuPorcentajePorProceso(int pid) {
        if (pid <= 0) return -1.0;

        try {
            OSProcess proceso = os.getProcess(pid);
            if (proceso == null) {
                LOGGER.log(Level.FINE, "[OSHI] PID {0} no encontrado", pid);
                return -1.0;
            }

            // Si cambió el PID objetivo, reiniciar referencia de ticks
            if (pid != prevProcPid) {
                prevProcPid   = pid;
                prevProcTicks = proceso.getKernelTime() + proceso.getUserTime();
                return 0.0; // primera muestra sin referencia previa
            }

            long currentTicks = proceso.getKernelTime() + proceso.getUserTime();
            long deltaTicks   = currentTicks - prevProcTicks;
            prevProcTicks     = currentTicks;

            // Normalizar sobre el número de cores para que 100% = 1 core al 100%
            int cores = processor.getLogicalProcessorCount();
            double porcentaje = (deltaTicks / 1000.0) * 100.0 / cores;

            return Math.min(100.0, Math.max(0.0, porcentaje));

        } catch (Exception e) {
            LOGGER.log(Level.WARNING, "[OSHI] Error obteniendo CPU para PID {0}: {1}",
                new Object[]{pid, e.getMessage()});
            return -1.0;
        }
    }

    // -------------------------------------------------------------------------
    // RAM
    // -------------------------------------------------------------------------

    @Override
    public long getRamUsadaMb() {
        try {
            return (memory.getTotal() - memory.getAvailable()) / (1024 * 1024);
        } catch (Exception e) {
            LOGGER.log(Level.WARNING, "[OSHI] Error obteniendo RAM usada", e);
            return 0L;
        }
    }

    @Override
    public long getRamTotalMb() {
        try {
            return memory.getTotal() / (1024 * 1024);
        } catch (Exception e) {
            LOGGER.log(Level.WARNING, "[OSHI] Error obteniendo RAM total", e);
            return 0L;
        }
    }

    @Override
    public double getRamPorcentaje() {
        long total = getRamTotalMb();
        if (total == 0) return 0.0;
        return (getRamUsadaMb() / (double) total) * 100.0;
    }
}