package org.appsentinel.infrastructure.adapter.out;

import com.sun.jna.Native;
import com.sun.jna.platform.win32.User32;
import com.sun.jna.platform.win32.WinDef;
import com.sun.jna.ptr.IntByReference;
import java.util.Optional;
import oshi.SystemInfo;
import oshi.software.os.OperatingSystem;
import org.appsentinel.domain.port.in.MonitorPort;
import org.appsentinel.infrastructure.config.AppConfig;

import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * ProcessMonitorAdapter: Escaner de procesos del sistema operativo.
 * 
 * Usa JNA (Windows) o OSHI (todos los SO) para detectar la ventana activa.
 * Reporta lo detectado al MonitorPort (TimeTrackingService) cada X segundos.
 * 
 * NO implementa MonitorPort. LLAMA al MonitorPort.
 */
public class ProcessMonitorAdapter {
    
    private static final Logger LOGGER = Logger.getLogger(ProcessMonitorAdapter.class.getName());
    
    private final MonitorPort monitorPort;      // Puerto de entrada al dominio (TimeTrackingService)
    private final ScheduledExecutorService scheduler;
    private final int intervaloSegundos;
    private final SystemInfo systemInfo;
    private final OperatingSystem os;
    private volatile boolean ejecutando = false;
    
    /**
     * Constructor. Recibe el puerto por donde reportará al dominio.
     */
    public ProcessMonitorAdapter(MonitorPort monitorPort) {
        this.monitorPort = monitorPort;
        this.intervaloSegundos = AppConfig.getEscaneoIntervaloSegundos();
        this.systemInfo = new SystemInfo();
        this.os = systemInfo.getOperatingSystem();
        
        this.scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "ProcessMonitor");
            t.setDaemon(true);
            return t;
        });
    }
    
    /**
     * Arranca el escaner. Empieza a mirar el sistema cada X segundos.
     */
    public void iniciar() {
        if (ejecutando) return;
        ejecutando = true;
        
        LOGGER.info("Escáner iniciado. Intervalo: " + intervaloSegundos + " segundos");
        
        scheduler.scheduleAtFixedRate(
            this::escanear,
            0,
            intervaloSegundos,
            TimeUnit.SECONDS
        );
    }
    
    /**
     * Detiene el escaner.
     */
    public void detener() {
        ejecutando = false;
        scheduler.shutdown();
        LOGGER.info("Escáner detenido");
    }
    
    /**
     * Metodo que se ejecuta cada 10 segundos.
     */
    private void escanear() {
        try {
            String so = System.getProperty("os.name").toLowerCase();
            
            if (so.contains("win")) {
                escanearWindows();
            } else {
                escanearGenerico();
            }
            
        } catch (Exception e) {
            LOGGER.log(Level.WARNING, "Error escaneando: {0}", e.getMessage());
        }
    }
    
    /**
     * WINDOWS: JNA para ventana activa + OSHI para nombre del proceso.
     */
    private void escanearWindows() {
        WinDef.HWND hwnd = User32.INSTANCE.GetForegroundWindow();
        if (hwnd == null) return;
        
        char[] bufferTitulo = new char[512];
        User32.INSTANCE.GetWindowText(hwnd, bufferTitulo, 512);
        String tituloVentana = Native.toString(bufferTitulo).trim();
        
        if (tituloVentana.isEmpty()) return;
        
        IntByReference pidRef = new IntByReference();
        User32.INSTANCE.GetWindowThreadProcessId(hwnd, pidRef);
        int pid = pidRef.getValue();
        
        // Usamos ProcessHandle de Java en lugar de OSHI para obtener el nombre
        Optional<ProcessHandle> phOpt = ProcessHandle.of(pid);
        String nombreProceso = "desconocido";
        
        if (phOpt.isPresent()) {
            ProcessHandle ph = phOpt.get();
            Optional<String> comandoOpt = ph.info().command();
            if (comandoOpt.isPresent()) {
                nombreProceso = extraerNombre(comandoOpt.get());
            }
        }
        
        // Reportar al dominio via MonitorPort
        monitorPort.reportarActividadSistema(nombreProceso, tituloVentana);
        
        LOGGER.log(Level.FINE, "Detectado: {0} | {1}", 
            new Object[]{nombreProceso, tituloVentana});
    }
    
    /**
     * FALLBACK (Linux/Mac): ProcessHandle para listar procesos de usuario.
     */
    private void escanearGenerico() {
        String nombreProceso = "desconocido";
        String tituloVentana = "";
        
        // Buscar el proceso del usuario con mayor uso de CPU
        Optional<ProcessHandle> candidato = ProcessHandle.allProcesses()
            .filter(ph -> ph.info().user().isPresent())           // Solo procesos con usuario
            .filter(ph -> !esProcesoSistema(ph))                   // Ignorar sistema
            .max((a, b) -> Long.compare(
                a.info().totalCpuDuration().orElse(java.time.Duration.ZERO).toMillis(),
                b.info().totalCpuDuration().orElse(java.time.Duration.ZERO).toMillis()
            ));
        
        if (candidato.isPresent()) {
            ProcessHandle ph = candidato.get();
            Optional<String> comandoOpt = ph.info().command();
            nombreProceso = comandoOpt.isPresent() ? extraerNombre(comandoOpt.get()) : "desconocido";
            tituloVentana = ph.info().commandLine().orElse(nombreProceso);
        }
        
        if (!"desconocido".equals(nombreProceso)) {
            monitorPort.reportarActividadSistema(nombreProceso, tituloVentana);
        }
    }
    
    /**
     * Extrae nombre del ejecutable de una ruta completa.
     */
    private String extraerNombre(String rutaCompleta) {
        String separador = rutaCompleta.contains("\\") ? "\\" : "/";
        int ultimo = rutaCompleta.lastIndexOf(separador);
        String nombre = (ultimo >= 0) ? rutaCompleta.substring(ultimo + 1) : rutaCompleta;
        int punto = nombre.lastIndexOf('.');
        return (punto > 0) ? nombre.substring(0, punto).toLowerCase() : nombre.toLowerCase();
    }
    
    /**
     * Verifica si es proceso del sistema (para ignorarlo).
     */
    private boolean esProcesoSistema(ProcessHandle ph) {
        Optional<String> comandoOpt = ph.info().command();
        if (comandoOpt.isPresent()) {
            String cmd = comandoOpt.get().toLowerCase();
            return cmd.contains("system") || cmd.contains("kernel") || cmd.contains("init");
        }
        return ph.pid() < 100; // PIDs bajos suelen ser del sistema
    }
}