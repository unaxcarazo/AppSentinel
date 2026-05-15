package org.appsentinel.infrastructure.adapter.in;

import com.sun.jna.Native;
import com.sun.jna.platform.win32.User32;
import com.sun.jna.platform.win32.WinDef;
import com.sun.jna.ptr.IntByReference;
import org.appsentinel.domain.port.in.MonitorPort;
import org.appsentinel.infrastructure.config.AppConfig;

import java.util.Optional;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * ProcessMonitorAdapter: Sensor de entrada. 
 */
public class ProcessMonitorAdapter {

    private static final Logger LOGGER = Logger.getLogger(ProcessMonitorAdapter.class.getName());

    private final MonitorPort monitorPort;
    private final ScheduledExecutorService scheduler;
    private final int intervaloSegundos;
    private volatile boolean ejecutando = false;

    public ProcessMonitorAdapter(MonitorPort monitorPort) {
        this.monitorPort = monitorPort;
        this.intervaloSegundos = AppConfig.getEscaneoIntervaloSegundos();
        this.scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "ProcessMonitor");
            t.setDaemon(true);
            return t;
        });
    }

    public void iniciar() {
        if (ejecutando) return;
        ejecutando = true;
        LOGGER.info("Escáner de procesos activado. Intervalo: " + intervaloSegundos + "s");
        scheduler.scheduleAtFixedRate(this::escanear, 0, intervaloSegundos, TimeUnit.SECONDS);
    }

    public void detener() {
        ejecutando = false;
        scheduler.shutdown();
        LOGGER.info("Escáner de procesos detenido");
    }

    private void escanear() {
        try {
            String so = System.getProperty("os.name").toLowerCase();
            if (so.contains("win")) {
                escanearWindows();
            } else {
                escanearGenerico();
            }
        } catch (Exception e) {
            LOGGER.log(Level.WARNING, "Error en el ciclo de escaneo: {0}", e.getMessage());
        }
    }

    private void escanearWindows() {
        WinDef.HWND hwnd = User32.INSTANCE.GetForegroundWindow();
        if (hwnd == null) return;

        // Búfer expandido a 1024 para evitar truncamiento en títulos web muy largos
        char[] buffer = new char[1024];
        User32.INSTANCE.GetWindowText(hwnd, buffer, 1024);
        String titulo = Native.toString(buffer).trim();
        if (titulo.isEmpty()) return;

        IntByReference pidRef = new IntByReference();
        User32.INSTANCE.GetWindowThreadProcessId(hwnd, pidRef);
        int pid = pidRef.getValue();

        String nombreProceso = "desconocido";
        Optional<ProcessHandle> phOpt = ProcessHandle.of(pid);
        if (phOpt.isPresent()) {
            Optional<String> cmdOpt = phOpt.get().info().command();
            if (cmdOpt.isPresent()) {
                nombreProceso = extraerNombre(cmdOpt.get());
            }
        }

        // Envío de datos sanitizados hacia el puerto de entrada del dominio
        monitorPort.reportarActividadSistema(nombreProceso, titulo, pid);
        LOGGER.log(Level.FINE, "Sensor detectó actividad en: {0}", nombreProceso);
    }

    private void escanearGenerico() {
        Optional<ProcessHandle> candidato = ProcessHandle.allProcesses()
            .filter(ProcessHandle::isAlive)
            .filter(ph -> ph.info().user().isPresent())
            .filter(ph -> !esProcesoSistema(ph))
            .max((a, b) -> Long.compare(
                a.info().totalCpuDuration().orElse(java.time.Duration.ZERO).toMillis(),
                b.info().totalCpuDuration().orElse(java.time.Duration.ZERO).toMillis()
            ));

        if (candidato.isPresent()) {
            ProcessHandle ph = candidato.get();
            Optional<String> cmdOpt = ph.info().command();
            String nombre = cmdOpt.isPresent() ? extraerNombre(cmdOpt.get()) : "desconocido";
            String titulo = ph.info().commandLine().orElse(nombre);
            if (!"desconocido".equals(nombre)) {
                monitorPort.reportarActividadSistema(nombre, titulo,(int) ph.pid());
            }
        }
    }

    private String extraerNombre(String ruta) {
        if (ruta == null || ruta.isBlank()) return "desconocido";
        
        // CORRECCIÓN 2: Sanitización de comillas y carácteres de escape antes del split
        String limpia = ruta.replace("\"", "").trim();
        String sep = limpia.contains("\\") ? "\\\\" : "/";
        String[] partes = limpia.split(sep);
        
        String nombre = partes[partes.length - 1].toLowerCase();
        int punto = nombre.lastIndexOf('.');
        return punto > 0 ? nombre.substring(0, punto) : nombre;
    }

    private boolean esProcesoSistema(ProcessHandle ph) {
        Optional<String> cmdOpt = ph.info().command();
        if (cmdOpt.isPresent()) {
            String cmd = cmdOpt.get().toLowerCase();
            return cmd.contains("system") || cmd.contains("kernel") || cmd.contains("init");
        }
        return ph.pid() < 100;
    }
}
