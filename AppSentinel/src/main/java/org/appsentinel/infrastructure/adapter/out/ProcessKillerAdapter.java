package org.appsentinel.infrastructure.adapter.out;

import org.appsentinel.domain.port.out.BrowserCommandPort;
import org.appsentinel.domain.port.out.KillerPort;
import java.util.List;
import java.util.Set;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * ProcessKillerAdapter: Adaptador de SALIDA.
 * 
 * Cierra procesos de escritorio via ProcessHandle (multiplataforma).
 * Protege procesos críticos del sistema y la propia JVM.
 * 
 * Analogía: como PostgreSQLRepositoryAdapter guarda datos,
 * este adaptador "guarda" (aplica) cambios en el sistema operativo.
 */
public class ProcessKillerAdapter implements KillerPort {

    private static final Logger LOGGER = Logger.getLogger(ProcessKillerAdapter.class.getName());

    // Procesos que NUNCA se deben cerrar
    private static final Set<String> PROCESOS_PROTEGIDOS = Set.of(
        "csrss", "smss", "lsass", "services", "svchost", "winlogon",
        "wininit", "system", "registry", "memory compression",
        "explorer", "taskmgr", "java", "javaw", "idea", "netbeans"
    );

    private final BrowserCommandPort browserCommand;

    public ProcessKillerAdapter(BrowserCommandPort browserCommand) {
        this.browserCommand = browserCommand;
    }

    @Override
    public boolean cerrarProceso(String nombreProceso) {
        if (nombreProceso == null || nombreProceso.isBlank()) {
            LOGGER.log(Level.WARNING, "[KILL] Nombre de proceso nulo o vacio");
            return false;
        }

        String busqueda = nombreProceso.toLowerCase().replace(".exe", "").trim();

        // Proteccion: nunca cerrar procesos del sistema
        if (PROCESOS_PROTEGIDOS.contains(busqueda)) {
            LOGGER.log(Level.WARNING, "[KILL] Bloqueado intento de cerrar proceso protegido: {0}", nombreProceso);
            return false;
        }

        LOGGER.log(Level.INFO, "[KILL] Solicitando cierre para: {0}", nombreProceso);

        // Recolectar candidatos primero (sin efectos secundarios en stream)
        List<ProcessHandle> candidatos = ProcessHandle.allProcesses()
            .filter(ProcessHandle::isAlive)
            .filter(ph -> ph.info().command().isPresent())
            .filter(ph -> {
                String nombreExe = extraerNombre(ph.info().command().get());
                return nombreExe.contains(busqueda) || busqueda.contains(nombreExe);
            })
            .toList();

        // Cerrar fuera del stream (efectos secundarios explicitos)
        int cerrados = 0;
        for (ProcessHandle ph : candidatos) {
            if (cerrarProcesoIndividual(ph)) {
                cerrados++;
            }
        }

        if (cerrados == 0) {
            LOGGER.log(Level.WARNING, "[KILL] No se encontro proceso activo para: {0}", nombreProceso);
        }

        return cerrados > 0;
    }

    /**
     * Cierra un proceso individual con graceful -> forzoso.
     */
    private boolean cerrarProcesoIndividual(ProcessHandle ph) {
        try {
            // Paso 1: Cierre ordenado (graceful)
            boolean cerrado = ph.destroy();
            if (cerrado && esperarCierre(ph, 2)) {
                LOGGER.log(Level.FINE, "[KILL] Cerrado graceful PID {0}", ph.pid());
                return true;
            }

            // Paso 2: Escalado a cierre forzoso
            cerrado = ph.destroyForcibly();
            if (cerrado) {
                LOGGER.log(Level.INFO, "[KILL] Cerrado forzoso PID {0}", ph.pid());
                return true;
            }

            LOGGER.log(Level.WARNING, "[KILL] No se pudo cerrar PID {0}", ph.pid());
            return false;

        } catch (SecurityException e) {
            LOGGER.log(Level.WARNING, "[KILL] Permisos insuficientes para PID {0} (requiere Administrador)", ph.pid());
            return false;
        } catch (Exception e) {
            LOGGER.log(Level.SEVERE, "[KILL] Error inesperado cerrando PID {0}", ph.pid());
            return false;
        }
    }

    @Override
    public void cerrarPestanaNavegador(int tabId) {
        if (browserCommand == null) {
            LOGGER.log(Level.SEVERE, "[KILL] BrowserCommandPort no configurado");
            return;
        }
        browserCommand.cerrarPestaña(tabId);
    }

    private boolean esperarCierre(ProcessHandle ph, int segundos) {
        try {
            return ph.onExit().get(segundos, java.util.concurrent.TimeUnit.SECONDS) != null;
        } catch (Exception e) {
            return false;
        }
    }

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