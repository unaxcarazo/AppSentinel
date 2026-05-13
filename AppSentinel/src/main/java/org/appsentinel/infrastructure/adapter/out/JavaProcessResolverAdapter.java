package org.appsentinel.infrastructure.adapter.out;

import java.util.Optional;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * JavaProcessResolverAdapter: Adaptador de SALIDA.
 * 
 * Consulta activamente al sistema operativo via ProcessHandle (Java 9+ nativo)
 * para detectar el proceso de usuario con mayor consumo acumulado de CPU.
 * 
 * Es multiplataforma (Windows, Linux, Mac) pero menos preciso que
 * WindowsJnaNativeAdapter, ya que no detecta la ventana activa real,
 * solo infiere el proceso principal por uso de CPU.
 * 
 * Uso: Fallback cuando JNA no está disponible o en sistemas no-Windows.
 */
public class JavaProcessResolverAdapter {

    private static final Logger LOGGER = Logger.getLogger(JavaProcessResolverAdapter.class.getName());

    /**
     * DTO de infraestructura independiente para mantener la compatibilidad multiplataforma.
     */
    public record ProcesoInferido(String nombreProceso, String tituloVentana, int pid) {}

    /**
     * Consulta al OS cuál es el proceso de usuario con mayor CPU acumulada.
     * @return Optional con los datos del proceso, o empty si no encuentra candidato.
     */
    public Optional<ProcesoInferido> consultarProcesoPrincipal() {
        try {
            Optional<ProcessHandle> candidato = ProcessHandle.allProcesses()
                .filter(ProcessHandle::isAlive)
                .filter(ph -> ph.info().user().isPresent())
                .filter(ph -> !esProcesoSistema(ph))
                .max((a, b) -> Long.compare(
                    cpuDurationMs(a), cpuDurationMs(b)
                ));

            if (candidato.isEmpty()) {
                LOGGER.log(Level.FINE, "[SCAN] No se encontró proceso de usuario activo");
                return Optional.empty();
            }

            ProcessHandle ph = candidato.get();
            String nombre = ph.info().command()
                .map(this::extraerNombre)
                .orElse("desconocido");
            String titulo = ph.info().commandLine().orElse(nombre);

            // Sintaxis parametrizada que sí lee correctamente la variable LOGGER
            LOGGER.log(Level.FINE, "[SCAN] Proceso principal por CPU: {0} (PID: {1})",
                new Object[]{nombre, ph.pid()});

            // Devolver el nuevo record independiente para romper el acoplamiento con Windows
            return Optional.of(new ProcesoInferido(
                nombre, titulo, (int) ph.pid()
            ));

        } catch (Exception e) {
            LOGGER.log(Level.SEVERE, "[ERROR] Fallo consultando procesos via ProcessHandle", e);
            return Optional.empty();
        }
    }

    /**
     * Obtiene duración de CPU en milisegundos para comparación.
     */
    private long cpuDurationMs(ProcessHandle ph) {
        return ph.info().totalCpuDuration()
            .orElse(java.time.Duration.ZERO)
            .toMillis();
    }

    /**
     * Filtra procesos del sistema que no son del usuario.
     */
    private boolean esProcesoSistema(ProcessHandle ph) {
        Optional<String> cmdOpt = ph.info().command();
        if (cmdOpt.isPresent()) {
            String cmd = cmdOpt.get().toLowerCase();
            return cmd.contains("system") || cmd.contains("kernel") || cmd.contains("init");
        }
        return ph.pid() < 100;
    }

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
