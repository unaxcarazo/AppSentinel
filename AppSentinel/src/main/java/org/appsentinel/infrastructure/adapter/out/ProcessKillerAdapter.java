package org.appsentinel.infrastructure.adapter.out;

import org.appsentinel.domain.port.out.BrowserCommandPort;
import org.appsentinel.domain.port.out.KillerPort;

import java.util.Optional;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * ProcessKillerAdapter: Cierra procesos de escritorio con ProcessHandle
 * y pestañas del navegador a través de BrowserCommandPort.
 *
 * No conoce WebSocketAdapter — solo conoce el puerto.
 */
public class ProcessKillerAdapter implements KillerPort {

    private static final Logger LOGGER =
            Logger.getLogger(ProcessKillerAdapter.class.getName());

    // Puerto de salida — inyectado desde AppWiring
    private final BrowserCommandPort browserCommand;

    public ProcessKillerAdapter(BrowserCommandPort browserCommand) {
        this.browserCommand = browserCommand;
    }

    // ── Cierre de procesos de escritorio ─────────────────────────

    @Override
    public boolean cerrarProceso(String nombreProceso) {
        LOGGER.log(Level.INFO, "Solicitando cierre: {0}", nombreProceso);

        boolean algunoCerrado = false;
        String busqueda = nombreProceso.toLowerCase().replace(".exe", "");

        for (ProcessHandle ph : ProcessHandle.allProcesses().toList()) {
            Optional<String> cmdOpt = ph.info().command();

            if (cmdOpt.isPresent()) {
                String nombreExe = extraerNombre(cmdOpt.get());

                if (nombreExe.contains(busqueda) || busqueda.contains(nombreExe)) {
                    boolean cerrado = ph.destroyForcibly();

                    if (cerrado) {
                        LOGGER.log(Level.INFO, "Cerrado PID {0}: {1}",
                            new Object[]{ph.pid(), nombreExe});
                        algunoCerrado = true;
                    } else {
                        LOGGER.log(Level.WARNING,
                            "No se pudo cerrar PID {0}", ph.pid());
                    }
                }
            }
        }

        if (!algunoCerrado) {
            LOGGER.log(Level.WARNING,
                "No se encontró ningún proceso con nombre: {0}", nombreProceso);
        }

        return algunoCerrado;
    }

    // ── Cierre de pestañas del navegador ─────────────────────────

    @Override
    public void cerrarPestanaNavegador(int tabId) {
        if (browserCommand == null) {
            LOGGER.log(Level.SEVERE,
                "BrowserCommandPort no configurado — no se puede cerrar pestaña");
            return;
        }
        browserCommand.cerrarPestaña(tabId);
    }

    // ── Utilidad ─────────────────────────────────────────────────

    /**
     * Extrae el nombre del ejecutable de una ruta completa.
     * "C:\Program Files\Chrome\chrome.exe" → "chrome"
     * "/usr/bin/firefox"                   → "firefox"
     */
    private String extraerNombre(String ruta) {
        String sep = ruta.contains("\\") ? "\\\\" : "/";
        String[] partes = ruta.split(sep);
        String nombre = partes[partes.length - 1].toLowerCase();

        int punto = nombre.lastIndexOf('.');
        return punto > 0 ? nombre.substring(0, punto) : nombre;
    }
}