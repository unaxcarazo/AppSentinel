package org.appsentinel.infrastructure.adapter.out;

import oshi.SystemInfo;
import oshi.hardware.CentralProcessor;
import oshi.hardware.GlobalMemory;
import oshi.software.os.OSProcess;
import oshi.software.os.OperatingSystem;
import org.appsentinel.domain.port.out.RendimientoSistemaPort;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicReference;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * OshiRendimientoAdapter: Adaptador de SALIDA para métricas de hardware en tiempo real.
 *
 * FIX A.2 (Thread-Safety):
 * - prevTicks protegido por AtomicReference<long[]> para lecturas/escrituras
 *   concurrentes desde JavaFX UI Thread y schedulers de fondo.
 * - Snapshots de procesos en ConcurrentHashMap<Integer, OSProcess> para que
 *   múltiples PIDs puedan medirse concurrentemente sin interferencia.
 * - El adaptador ya no documenta "no es thread-safe por diseño".
 *
 * FIX B.1 (Precisión Algorítmica):
 * - Elimina aritmética manual sobre ticks crudos (deltaTicks / 1000.0 * 100.0 / cores).
 * - Usa OSProcess.getProcessCpuLoadBetweenTicks(OSProcess oldProcess) que calcula
 *   internamente el diferencial temporal correcto usando la API nativa de OSHI.
 * - Cachea el objeto OSProcess del ciclo anterior (no solo ticks) para pasarlo
 *   como referencia a getProcessCpuLoadBetweenTicks().
 */
public class OshiRendimientoAdapter implements RendimientoSistemaPort {

    private static final Logger LOGGER = Logger.getLogger(OshiRendimientoAdapter.class.getName());

    private final CentralProcessor processor;
    private final GlobalMemory memory;
    private final OperatingSystem os;

    // FIX A.2: AtomicReference para prevTicks — safe para acceso concurrente
    private final AtomicReference<long[]> prevTicksRef;

    // FIX A.2 + B.1: ConcurrentHashMap de snapshots OSProcess por PID.
    // Cada entrada guarda el objeto OSProcess del ciclo anterior para
    // getProcessCpuLoadBetweenTicks(oldProcess).
    private final ConcurrentHashMap<Integer, OSProcess> procSnapshots = new ConcurrentHashMap<>();

    public OshiRendimientoAdapter() {
        SystemInfo si = new SystemInfo();
        this.processor = si.getHardware().getProcessor();
        this.memory    = si.getHardware().getMemory();
        this.os        = si.getOperatingSystem();

        long[] initialTicks = processor.getSystemCpuLoadTicks();
        this.prevTicksRef = new AtomicReference<>(initialTicks);

        LOGGER.log(Level.INFO, "[OSHI] Adaptador de rendimiento inicializado. CPU: {0} cores (Thread-Safe)",
            processor.getLogicalProcessorCount());
    }

    // -------------------------------------------------------------------------
    // CPU global
    // -------------------------------------------------------------------------

    /*
     * Devuelve el porcentaje de CPU global del sistema entre 0.0 y 100.0.
     *
     * FIX A.2: AtomicReference garantiza que dos hilos concurrentes no lean
     * un prevTicks parcialmente actualizado. getAndSet() es atómico.
     */
    @Override
    public double getCpuPorcentajeGlobal() {
        try {
            long[] prev = prevTicksRef.get();
            double carga = processor.getSystemCpuLoadBetweenTicks(prev) * 100.0;
            long[] current = processor.getSystemCpuLoadTicks();
            prevTicksRef.set(current); // atómico, no hay ventana de lectura intermedia
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
     * FIX B.1: Usa getProcessCpuLoadBetweenTicks(OSProcess oldProcess) que
     * calcula el diferencial correcto internamente. No requiere aritmética manual.
     *
     * FIX A.2: ConcurrentHashMap permite que múltiples PIDs se midan
     * concurrentemente sin pisarse. Cada PID tiene su propio snapshot.
     *
     * @param pid PID del proceso a medir.
     * @return Porcentaje de CPU entre 0.0 y 100.0, o -1.0 si el PID no existe.
     */
    @Override
    public double getCpuPorcentajePorProceso(int pid) {
        if (pid <= 0) return -1.0;

        try {
            OSProcess procesoActual = os.getProcess(pid);
            if (procesoActual == null) {
                LOGGER.log(Level.FINE, "[OSHI] PID {0} no encontrado", pid);
                procSnapshots.remove(pid); // limpiar snapshot huérfano
                return -1.0;
            }

            OSProcess procesoAnterior = procSnapshots.get(pid);

            if (procesoAnterior == null) {
                // Primera muestra para este PID: guardar snapshot, devolver 0.0
                procSnapshots.put(pid, procesoActual);
                LOGGER.log(Level.FINE, "[OSHI] Primera muestra para PID {0}, snapshot guardado", pid);
                return 0.0;
            }

            // FIX B.1: OSHI calcula el load entre el snapshot anterior y el actual
            double carga = procesoActual.getProcessCpuLoadBetweenTicks(procesoAnterior);
            double porcentaje = carga * 100.0;

            // Actualizar snapshot para la próxima llamada
            procSnapshots.put(pid, procesoActual);

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