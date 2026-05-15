package org.appsentinel.infrastructure.adapter.out;

import org.appsentinel.domain.port.out.BrowserCommandPort;
import org.appsentinel.domain.port.out.KillerPort;
import java.util.List;
import java.util.Set;
import java.util.logging.Level;
import java.util.logging.Logger;

public class ProcessKillerAdapter implements KillerPort {

    private static final Logger LOGGER = Logger.getLogger(ProcessKillerAdapter.class.getName());

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
        if (nombreProceso == null || nombreProceso.isBlank()) return false;

        String busqueda = nombreProceso.toLowerCase().replace(".exe", "").trim();
        if (PROCESOS_PROTEGIDOS.contains(busqueda)) {
            LOGGER.log(Level.WARNING, "[KILL] Bloqueado intento de cerrar proceso protegido: {0}", nombreProceso);
            return false;
        }

        LOGGER.log(Level.INFO, "[KILL] Solicitando cierre para: {0}", nombreProceso);

        // CORRECCIÓN SINTÁCTICA: Corrección del operador genérico duplicado '<<'
        List<ProcessHandle> candidatos = ProcessHandle.allProcesses()
            .filter(ProcessHandle::isAlive)
            .filter(ph -> ph.info().command().isPresent())
            .filter(ph -> busqueda.equals(extraerNombre(ph.info().command().get())))
            .toList();

        int cerrados = 0;
        for (ProcessHandle ph : candidatos) {
            if (cerrarProcesoIndividual(ph)) cerrados++;
        }

        if (cerrados == 0) {
            LOGGER.log(Level.WARNING, "[KILL] No se encontró proceso activo para: {0}", nombreProceso);
        }
        return cerrados > 0;
    }

    private boolean cerrarProcesoIndividual(ProcessHandle ph) {
        try {
            boolean cerrado = ph.destroy();
            // Optimización: Si el cierre cordial falla o agota el tiempo, se destruye forzosamente de inmediato
            if (cerrado && esperarCierre(ph, 1)) { 
                LOGGER.log(Level.FINE, "[KILL] Cerrado graceful PID {0}", ph.pid());
                return true;
            }
            cerrado = ph.destroyForcibly();
            if (cerrado) {
                LOGGER.log(Level.INFO, "[KILL] Cerrado forzoso PID {0}", ph.pid());
                return true;
            }
            return false;
        } catch (SecurityException e) {
            LOGGER.log(Level.WARNING, "[KILL] Permisos insuficientes para PID {0}", ph.pid());
            return false;
        } catch (Exception e) {
            LOGGER.log(Level.SEVERE, "[KILL] Error inesperado cerrando PID {0}", ph.pid());
            return false;
        }
    }

    @Override
    public void cerrarPestañaNavegador(int tabId) {
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
