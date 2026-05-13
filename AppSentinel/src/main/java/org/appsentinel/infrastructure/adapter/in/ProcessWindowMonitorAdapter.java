package org.appsentinel.infrastructure.adapter.in;

import org.appsentinel.domain.port.in.MonitorPort;
import org.appsentinel.infrastructure.adapter.out.JavaProcessResolverAdapter;
import org.appsentinel.infrastructure.adapter.out.WindowsJnaNativeAdapter;
import org.appsentinel.infrastructure.config.AppConfig;

import java.util.Optional; // CORRECCIÓN 1: Importación de Optional añadida
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * ProcessWindowMonitorAdapter: Adaptador de ENTRADA.
 * 
 * Es el único componente del sistema que despierta por iniciativa propia
 * (temporizador cada 10 segundos). Emite el estímulo hacia el dominio:
 * "He detectado esta actividad en el sistema operativo".
 * 
 * Para obtener los datos, TIRA de adaptadores de SALIDA que consultan al OS:
 * - WindowsJnaNativeAdapter (Windows, preciso, via JNA)
 * - JavaProcessResolverAdapter (multiplataforma, fallback via ProcessHandle)
 * 
 * El dominio (MonitorPort / TimeTrackingService) no sabe cómo se detectó,
 * solo recibe el resultado normalizado.
 */
public class ProcessWindowMonitorAdapter {

    private static final Logger LOGGER = Logger.getLogger(ProcessWindowMonitorAdapter.class.getName());

    private final MonitorPort monitorPort;
    private final WindowsJnaNativeAdapter jnaAdapter;
    private final JavaProcessResolverAdapter javaAdapter;
    private final ScheduledExecutorService scheduler;
    private final int intervaloSegundos;
    private volatile boolean ejecutando = false;

    /*
     * Constructor estándar: crea los adaptadores de salida internamente.
     */
    public ProcessWindowMonitorAdapter(MonitorPort monitorPort) {
        this(monitorPort, new WindowsJnaNativeAdapter(), new JavaProcessResolverAdapter());
    }

    /*
     * Constructor para tests: permite inyectar mocks de los adaptadores de salida.
     */
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

    /**
     * Arranca el ciclo de escaneo periódico.
     */
    public void iniciar() {
        if (ejecutando) return;
        ejecutando = true;
        LOGGER.log(Level.INFO, "[MONITOR] Escáner activado. Intervalo: {0}s", intervaloSegundos);
        scheduler.scheduleAtFixedRate(this::escanear, 0, intervaloSegundos, TimeUnit.SECONDS);
    }

    /**
     * Detiene el ciclo de escaneo.
     */
    public void detener() {
        ejecutando = false;
        scheduler.shutdown();
        LOGGER.log(Level.INFO, "[MONITOR] Escáner detenido");
    }

    /**
     * Ciclo de escaneo: consulta al OS y empuja al dominio.
     */
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

    /*
     * Estrategia de consulta al OS:
     * 1. Intentar JNA (Windows, preciso, ventana real activa)
     * 2. Fallback a ProcessHandle (multiplataforma, por CPU)  
     */
    private Optional<DatosActividad> consultarOs() {
        String so = System.getProperty("os.name").toLowerCase();

        if (so.contains("win")) {
            Optional<WindowsJnaNativeAdapter.VentanaDetectada> jna = jnaAdapter.consultarVentanaActiva();
            if (jna.isPresent()) {
                WindowsJnaNativeAdapter.VentanaDetectada v = jna.get();
                return Optional.of(new DatosActividad(v.nombreProceso(), v.tituloVentana()));
            }
            LOGGER.log(Level.FINE, "[MONITOR] JNA no detectó ventana, probando fallback Java");
        }

        // Consumir correctamente el record ProcesoInferido de la clase JavaProcessResolverAdapter
        Optional<JavaProcessResolverAdapter.ProcesoInferido> java = javaAdapter.consultarProcesoPrincipal();
        return java.map(p -> new DatosActividad(p.nombreProceso(), p.tituloVentana()));
    }

    /**
     * Normaliza y envía al dominio via puerto hexagonal.
     */
    private void notificarDominio(DatosActividad datos) {
        LOGGER.log(Level.FINE, "[MONITOR] Notificando dominio: {0}", datos.nombreProceso);
        monitorPort.reportarActividadSistema(datos.nombreProceso, datos.tituloVentana);
    }

    /**
     * DTO interno para normalizar entre los dos adaptadores de salida.
     */
    private record DatosActividad(String nombreProceso, String tituloVentana) {}
}
