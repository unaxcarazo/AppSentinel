package org.appsentinel.infrastructure.adapter.out;

import java.util.Comparator;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * JavaProcessResolverAdapter: Adaptador de SALIDA — Fallback multiplataforma.
 *
 * Consulta activamente al sistema operativo via ProcessHandle (Java 9+ nativo)
 * para detectar el proceso de usuario con mayor consumo DIFERENCIAL de CPU.
 *
 * FIX B.2 (Precisión Algorítmica):
 * ANTES: Usaba totalCpuDuration() (acumulado desde el inicio del proceso).
 *        explorer.exe (abierto desde el boot) siempre ganaba sobre apps nuevas.
 * DESPUÉS: Toma snapshot de CPU, espera 500ms no-bloqueante, toma segundo snapshot,
 *          calcula delta. El proceso con mayor delta es el más activo AHORA.
 *
 * Es multiplataforma (Windows, Linux, Mac) pero menos preciso que
 * WindowsJnaNativeAdapter, ya que no detecta la ventana activa real,
 * solo infiere el p proceso principal por uso de CPU diferencial.
 */
public class JavaProcessResolverAdapter {

    private static final Logger LOGGER = Logger.getLogger(JavaProcessResolverAdapter.class.getName());

    /**
     * FIX B.2: Cache de snapshots de CPU por PID para cálculo diferencial.
     * Almacena el totalCpuDuration en ms capturado en el ciclo anterior.
     * Se limpia automáticamente de PIDs muertos.
     */
    private final ConcurrentHashMap<Integer, Long> cpuSnapshots = new ConcurrentHashMap<>();

    /**
     * FIX B.2: Intervalo de muestreo entre snapshots en milisegundos.
     * 500ms es el mínimo viable para obtener un delta significativo
     * sin bloquear el hilo del escáner demasiado tiempo.
     */
    private static final long INTERVALO_MUESTREO_MS = 500L;

    /**
     * DTO de infraestructura independiente para mantener la compatibilidad multiplataforma.
     */
    public record ProcesoInferido(String nombreProceso, String tituloVentana, int pid) {}

    /**
     * Consulta al OS cuál es el proceso de usuario con mayor CPU DIFERENCIAL.
     *
     * FIX B.2: Algoritmo de dos fases:
     *   1. Capturar snapshot inicial de totalCpuDuration para todos los procesos candidatos.
     *   2. Esperar INTERVALO_MUESTREO_MS (no-bloqueante para este hilo, pero el caller debe tolerar la latencia).
     *   3. Capturar snapshot final y calcular delta = final - inicial.
     *   4. Seleccionar el proceso con mayor delta positivo.
     *
     * @return Optional con los datos del proceso más activo en el último medio segundo,
     *         o empty si no encuentra candidato.
     */
    public Optional<ProcesoInferido> consultarProcesoPrincipal() {
        try {
            // FASE 1: Snapshot inicial
            Map<Integer, Long> snapshotInicial = capturarSnapshotCpu();
            if (snapshotInicial.isEmpty()) {
                LOGGER.log(Level.FINE, "[SCAN] No hay procesos de usuario candidatos");
                return Optional.empty();
            }

            // FASE 2: Espera no-bloqueante para que el SO acumule ticks de CPU
            // NOTA: El caller (ProcessWindowMonitorAdapter) debe tolerar este sleep.
            // En producción, este fallback se ejecuta solo cuando JNA falla,
            // por lo que la latencia adicional es aceptable.
            Thread.sleep(INTERVALO_MUESTREO_MS);

            // FASE 3: Snapshot final + cálculo de deltas
            Map<Integer, Long> deltas = calcularDeltas(snapshotInicial);
            if (deltas.isEmpty()) {
                LOGGER.log(Level.FINE, "[SCAN] Ningún proceso mostró actividad de CPU en {0}ms", INTERVALO_MUESTREO_MS);
                return Optional.empty();
            }

            // FASE 4: Seleccionar el PID con mayor delta
            Optional<Map.Entry<Integer, Long>> ganador = deltas.entrySet().stream()
                .max(Comparator.comparingLong(Map.Entry::getValue));

            if (ganador.isEmpty()) {
                return Optional.empty();
            }

            int pidGanador = ganador.get().getKey();
            long deltaMs = ganador.get().getValue();

            Optional<ProcessHandle> phOpt = ProcessHandle.of(pidGanador);
            if (phOpt.isEmpty() || !phOpt.get().isAlive()) {
                LOGGER.log(Level.FINE, "[SCAN] PID ganador {0} murió durante el muestreo", pidGanador);
                return Optional.empty();
            }

            ProcessHandle ph = phOpt.get();
            String nombre = ph.info().command()
                .map(this::extraerNombre)
                .orElse("desconocido");
            String titulo = ph.info().commandLine().orElse(nombre);

            LOGGER.log(Level.FINE,
                "[SCAN] Proceso principal por delta CPU: {0} (PID: {1}, delta: {2}ms en {3}ms)",
                new Object[]{nombre, pidGanador, deltaMs, INTERVALO_MUESTREO_MS});

            // Actualizar cache global para el próximo ciclo
            actualizarCacheGlobal(snapshotInicial, deltas);

            return Optional.of(new ProcesoInferido(nombre, titulo, pidGanador));

        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            LOGGER.log(Level.WARNING, "[SCAN] Interrumpido durante muestreo de CPU");
            return Optional.empty();
        } catch (Exception e) {
            LOGGER.log(Level.SEVERE, "[ERROR] Fallo consultando procesos via ProcessHandle", e);
            return Optional.empty();
        }
    }

    // -------------------------------------------------------------------------
    // FASE 1: Captura snapshot inicial
    // -------------------------------------------------------------------------

    /**
     * Captura totalCpuDuration() para todos los procesos candidatos.
     * Filtra procesos del sistema y procesos sin acceso a info.
     */
    private Map<Integer, Long> capturarSnapshotCpu() {
        Map<Integer, Long> snapshot = new ConcurrentHashMap<>();

        ProcessHandle.allProcesses()
            .filter(ProcessHandle::isAlive)
            .filter(ph -> ph.info().user().isPresent())
            .filter(ph -> !esProcesoSistema(ph))
            .forEach(ph -> {
                long cpuMs = ph.info().totalCpuDuration()
                    .orElse(java.time.Duration.ZERO)
                    .toMillis();
                snapshot.put((int) ph.pid(), cpuMs);
            });

        return snapshot;
    }

    // -------------------------------------------------------------------------
    // FASE 3: Cálculo de deltas
    // -------------------------------------------------------------------------

    /**
     * Calcula delta CPU = snapshotActual - snapshotInicial para cada PID vivo.
     * Solo retorna deltas positivos (procesos que consumieron CPU durante la espera).
     */
    private Map<Integer, Long> calcularDeltas(Map<Integer, Long> snapshotInicial) {
        Map<Integer, Long> deltas = new ConcurrentHashMap<>();

        for (Map.Entry<Integer, Long> entry : snapshotInicial.entrySet()) {
            int pid = entry.getKey();
            long cpuInicial = entry.getValue();

            Optional<ProcessHandle> phOpt = ProcessHandle.of(pid);
            if (phOpt.isEmpty() || !phOpt.get().isAlive()) continue;

            long cpuActual = phOpt.get().info().totalCpuDuration()
                .orElse(java.time.Duration.ZERO)
                .toMillis();

            long delta = cpuActual - cpuInicial;
            if (delta > 0) {
                deltas.put(pid, delta);
            }
        }

        return deltas;
    }

    // -------------------------------------------------------------------------
    // Cache global de snapshots (para limpieza de PIDs muertos)
    // -------------------------------------------------------------------------

    /**
     * Actualiza la cache global eliminando PIDs que ya no existen.
     * Esto evita que la cache crezca indefinidamente.
     */
    private void actualizarCacheGlobal(Map<Integer, Long> snapshotInicial,
                                        Map<Integer, Long> deltas) {
        // Limpiar PIDs que no aparecen en el snapshot actual (murieron)
        cpuSnapshots.keySet().removeIf(pid -> !snapshotInicial.containsKey(pid));
        // Añadir/actualizar PIDs del ciclo actual
        snapshotInicial.forEach((pid, cpu) -> cpuSnapshots.put(pid, cpu));
    }

    // -------------------------------------------------------------------------
    // Filtrado de procesos del sistema (FIX C.2 parcial)
    // -------------------------------------------------------------------------

    /**
     * Filtra procesos del sistema que no son del usuario.
     * FIX C.2: Usa ProcessHandle.info().user() en lugar de heurística pid < 100.
     *
     * Windows: NT AUTHORITY\SYSTEM, LOCAL SERVICE, NETWORK SERVICE
     * Linux: root
     * macOS: root
     */
    private boolean esProcesoSistema(ProcessHandle ph) {
        Optional<String> userOpt = ph.info().user();
        if (userOpt.isPresent()) {
            String user = userOpt.get().toLowerCase();
            // Usuarios de sistema en Windows
            if (user.contains("nt authority") ||
                user.contains("system") ||
                user.contains("local service") ||
                user.contains("network service") ||
                // Linux/macOS
                user.equals("root")) {
                return true;
            }
        }

        Optional<String> cmdOpt = ph.info().command();
        if (cmdOpt.isPresent()) {
            String cmd = cmdOpt.get().toLowerCase();
            return cmd.contains("system") || cmd.contains("kernel") || cmd.contains("init");
        }

        // Fallback conservador: pid < 100 (solo si todo lo demás falla)
        return ph.pid() < 100;
    }

    // -------------------------------------------------------------------------
    // Utilidades
    // -------------------------------------------------------------------------

    /**
     * Extrae nombre del ejecutable de una ruta completa.
     */
    private String extraerNombre(String ruta) {
        if (ruta == null || ruta.isBlank()) return "desconocido";
        String limpia = ruta.replace("\"", "").trim();
        String sep = limpia.contains("\\") ? "\\\\" : "/";
        String[] partes = limpia.split(sep);
        String nombre = partes[partes.length - 1].toLowerCase();
        int punto = nombre.lastIndexOf('.');
        return punto > 0 ? nombre.substring(0, punto) : nombre;
    }
}