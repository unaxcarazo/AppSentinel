package org.appsentinel.infrastructure.adapter.in;

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
                // FIX: Propagamos el PID que JNA extrajo de la ventana activa
                return Optional.of(new DatosActividad(v.nombreProceso(), v.tituloVentana(), v.pid()));
            }
            LOGGER.log(Level.FINE, "[MONITOR] JNA no detectó ventana, probando fallback Java");
        }

        Optional<JavaProcessResolverAdapter.ProcesoInferido> java = javaAdapter.consultarProcesoPrincipal();
        // FIX: Fallback Java sin PID preciso (cross-platform). -1 indica "desconocido".
        return java.map(p -> new DatosActividad(p.nombreProceso(), p.tituloVentana(), -1));
    }

    private void notificarDominio(DatosActividad datos) {
        LOGGER.log(Level.FINE, "[MONITOR] Notificando dominio: {0} (PID: {1})", 
            new Object[]{datos.nombreProceso, datos.pid});
        monitorPort.reportarActividadSistema(datos.nombreProceso, datos.tituloVentana, datos.pid);
    }

    // FIX: Record ampliado con PID para no perder la identidad única del proceso en foco
    private record DatosActividad(String nombreProceso, String tituloVentana, int pid) {}
}