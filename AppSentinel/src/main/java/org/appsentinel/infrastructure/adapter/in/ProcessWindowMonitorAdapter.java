package org.appsentinel.infrastructure.adapter.in;

import java.time.Instant;
import java.util.Optional;
import org.appsentinel.domain.port.in.MonitorPort;
import org.appsentinel.infrastructure.adapter.out.JavaProcessResolverAdapter;
import org.appsentinel.infrastructure.adapter.out.WindowsJnaNativeAdapter;
import org.appsentinel.infrastructure.config.AppConfig;

import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * ProcessWindowMonitorAdapter: Adaptador de ENTRADA para detección de ventanas activas.
 *
 * FIX 2.1 (Pureza Hexagonal):
 * El adaptador resuelve el startInstant del proceso via ProcessHandle/JNA
 * ANTES de notificar al dominio. TimeTrackingService ya no consulta
 * java.lang.ProcessHandle directamente.
 *
 * Flujo:
 *   1. JNA detecta ventana activa (Windows) o JavaProcessResolver (fallback).
 *   2. Se resuelve startInstant = ProcessHandle.of(pid).info().startInstant().
 *   3. Se notifica al dominio: monitorPort.reportarActividadSistema(..., startInstant).
 */
public class ProcessWindowMonitorAdapter {

    private static final Logger LOGGER = Logger.getLogger(ProcessWindowMonitorAdapter.class.getName());

    private final MonitorPort monitorPort;
    private final WindowsJnaNativeAdapter jnaAdapter;
    private final JavaProcessResolverAdapter javaAdapter;
    private final ScheduledExecutorService scheduler;
    private final int intervaloSegundos;
    private volatile boolean ejecutando = false;

    public ProcessWindowMonitorAdapter(MonitorPort monitorPort) {
        this(monitorPort, new WindowsJnaNativeAdapter(), new JavaProcessResolverAdapter());
    }

    public ProcessWindowMonitorAdapter(MonitorPort monitorPort,
                                        WindowsJnaNativeAdapter jnaAdapter,
                                        JavaProcessResolverAdapter javaAdapter) {
        this.monitorPort = monitorPort;
        this.jnaAdapter = jnaAdapter;
        this.javaAdapter = javaAdapter;
        this.intervaloSegundos = AppConfig.getEscaneoIntervaloSegundos();
        this.scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "ProcessWindowMonitor");
            t.setDaemon(true);
            return t;
        });
    }

    public void iniciar() {
        if (ejecutando) return;
        ejecutando = true;
        LOGGER.log(Level.INFO, "[MONITOR] Escáner activado. Intervalo: {0}s", intervaloSegundos);
        scheduler.scheduleAtFixedRate(this::escanear, 0, intervaloSegundos, TimeUnit.SECONDS);
    }

    public void detener() {
        ejecutando = false;
        scheduler.shutdown();
        LOGGER.log(Level.INFO, "[MONITOR] Escáner detenido");
    }

    private void escanear() {
        try {
            consultarOs().ifPresentOrElse(
                this::notificarDominio,
                () -> LOGGER.log(Level.FINE, "[MONITOR] Sin actividad detectada en este ciclo")
            );
        } catch (Exception e) {
            LOGGER.log(Level.SEVERE, "[ERROR] Fallo en ciclo de escaneo", e);
        }
    }

    private Optional<DatosActividad> consultarOs() {
        String so = System.getProperty("os.name").toLowerCase();

        if (so.contains("win")) {
            Optional<WindowsJnaNativeAdapter.VentanaDetectada> jna = jnaAdapter.consultarVentanaActiva();
            if (jna.isPresent()) {
                WindowsJnaNativeAdapter.VentanaDetectada v = jna.get();
                return Optional.of(new DatosActividad(v.nombreProceso(), v.tituloVentana(), v.pid()));
            }
            LOGGER.log(Level.FINE, "[MONITOR] JNA no detectó ventana, probando fallback Java");
        }

        Optional<JavaProcessResolverAdapter.ProcesoInferido> java = javaAdapter.consultarProcesoPrincipal();
        return java.map(p -> new DatosActividad(p.nombreProceso(), p.tituloVentana(), p.pid()));
    }

    /**
     * FIX 2.1: Resuelve startInstant en infraestructura antes de notificar al dominio.
     * El dominio (TimeTrackingService) ya no consulta ProcessHandle.
     */
    private void notificarDominio(DatosActividad datos) {
        Instant startInstant = null;
        if (datos.pid > 0) {
            startInstant = ProcessHandle.of(datos.pid)
                .flatMap(ph -> ph.info().startInstant())
                .orElse(null);
        }

        LOGGER.log(Level.FINE, "[MONITOR] Notificando dominio: {0} (PID: {1}, startInstant: {2})",
            new Object[]{datos.nombreProceso, datos.pid, startInstant});

        monitorPort.reportarActividadSistema(
            datos.nombreProceso,
            datos.tituloVentana,
            datos.pid,
            startInstant
        );
    }

    private record DatosActividad(String nombreProceso, String tituloVentana, int pid) {}
}