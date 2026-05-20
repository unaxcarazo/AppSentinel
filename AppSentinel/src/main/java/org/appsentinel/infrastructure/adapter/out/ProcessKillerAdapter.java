package org.appsentinel.infrastructure.adapter.out;

import org.appsentinel.domain.port.out.BrowserCommandPort;
import org.appsentinel.domain.port.out.KillerPort;

import java.time.Instant;
import java.util.Optional;
import java.util.Set;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * ProcessKillerAdapter: Adaptador de SALIDA para cierre quirúrgico de procesos.
 *
 * FIX A.1 (Anti-PID-Recycling):
 * Windows reutiliza PIDs agresivamente. Entre el escaneo (10s) y la orden de
 * bloqueo (que puede demorar minutos en modo estricto con gracia), otro proceso
 * legítimo podría heredar el PID de la distracción.
 *
 * PROTECCIÓN IMPLEMENTADA:
 * 1. Validación de nombre: ph.info().command() vs nombre reportado (existente).
 * 2. Validación de startInstant: el escáner captura el Instant de inicio del
 *    proceso en el momento de detección. Al matar, se compara contra el
 *    startInstant ACTUAL del PID. Si difieren, el PID fue reciclado → abortar.
 *
 * La comparación es matemática (Instant.equals), no por milisegundos.
 */
public class ProcessKillerAdapter implements KillerPort {

    private static final Logger LOGGER = Logger.getLogger(ProcessKillerAdapter.class.getName());

    private static final Set<String> PROCESOS_PROTEGIDOS = Set.of(
        "csrss", "smss", "lsass", "services", "svchost", "winlogon",
        "wininit", "system", "registry", "memory compression",
        "explorer", "taskmgr", "java", "javaw", "idea", "netbeans"
    );

    private static final long GRACEFUL_TIMEOUT_SECONDS = 5;

    private final BrowserCommandPort browserCommand;

    public ProcessKillerAdapter(BrowserCommandPort browserCommand) {
        this.browserCommand = browserCommand;
    }

    @Override
    public boolean cerrarProceso(String nombreProceso, int pid, Instant startInstantEscaneado) {
        if (pid <= 0) {
            LOGGER.log(Level.WARNING, "[KILL] PID inválido recibido ({0}) para: {1}",
                new Object[]{pid, nombreProceso});
            return false;
        }

        // 1. RESOLUCIÓN DIRECTA POR PID
        Optional<ProcessHandle> handleOpt = ProcessHandle.of(pid);
        if (handleOpt.isEmpty()) {
            LOGGER.log(Level.WARNING, "[KILL] No existe proceso con PID {0}", pid);
            return false;
        }

        ProcessHandle ph = handleOpt.get();
        if (!ph.isAlive()) {
            LOGGER.log(Level.INFO, "[KILL] PID {0} ya no está vivo", pid);
            return false;
        }

        // 2. VALIDACIÓN DE SEGURIDAD: nombre REAL del handle
        String nombreReal = ph.info().command().map(this::extraerNombre).orElse("desconocido");
        if (PROCESOS_PROTEGIDOS.contains(nombreReal)) {
            LOGGER.log(Level.SEVERE, "[KILL] BLOQUEADO intento sobre proceso protegido: PID {0} ({1})",
                new Object[]{pid, nombreReal});
            return false;
        }

        // 3. VALIDACIÓN A.1 — Anti-PID-Recycling:
        //    Comparar startInstant del escáner vs startInstant actual del PID.
        //    Si difieren, el PID fue reciclado por otro proceso.
        Optional<Instant> startInstantActualOpt = ph.info().startInstant();
        if (startInstantEscaneado != null && startInstantActualOpt.isPresent()) {
            Instant startInstantActual = startInstantActualOpt.get();
            if (!startInstantEscaneado.equals(startInstantActual)) {
                LOGGER.log(Level.SEVERE,
                    "[KILL] ABORTADO — PID {0} fue RECICLADO. " +
                    "Escaneado: {1} (start={2}), Actual: {3} (start={4}). " +
                    "Otro proceso heredó este PID.",
                    new Object[]{pid, nombreProceso, startInstantEscaneado, nombreReal, startInstantActual});
                return false;
            }
        } else if (startInstantEscaneado == null) {
            LOGGER.log(Level.WARNING,
                "[KILL] startInstant del escáner no disponible para PID {0}. " +
                "Continuando con validación de nombre únicamente (riesgo de reciclaje).",
                pid);
        }

        // 4. LOG DE COHERENCIA (discrepancia nombre/pid sin reciclaje)
        String busqueda = normalizarNombre(nombreProceso);
        if (!busqueda.equals(nombreReal)) {
            LOGGER.log(Level.INFO,
                "[KILL] Discrepancia nombre/pid: reportado={0}, real={1}. Mataremos por PID.",
                new Object[]{nombreProceso, nombreReal});
        }

        LOGGER.log(Level.INFO, "[KILL] Ejecutando cierre quirúrgico sobre PID {0} ({1})",
            new Object[]{pid, nombreReal});

        // 5. DESTRUCCIÓN FÍSICA
        return destruirProceso(ph);
    }

    private boolean destruirProceso(ProcessHandle ph) {
        long pid = ph.pid();
        try {
            boolean cerrado = ph.destroy();
            if (cerrado && esperarCierre(ph, GRACEFUL_TIMEOUT_SECONDS)) {
                LOGGER.log(Level.FINE, "[KILL] PID {0} cerrado gracefulmente", pid);
                return true;
            }

            cerrado = ph.destroyForcibly();
            if (cerrado) {
                LOGGER.log(Level.INFO, "[KILL] PID {0} cerrado forzosamente", pid);
                return true;
            }

            LOGGER.log(Level.SEVERE, "[KILL] Fallo absoluto al cerrar PID {0}", pid);
            return false;

        } catch (SecurityException e) {
            LOGGER.log(Level.WARNING, "[KILL] Permisos insuficientes para PID {0}", pid);
            return false;
        } catch (Exception e) {
            LOGGER.log(Level.SEVERE, "[KILL] Error inesperado cerrando PID {0}: {1}",
                new Object[]{pid, e.getMessage()});
            return false;
        }
    }

    @Override
    public void cerrarPestañaNavegador(int tabId) {
        if (browserCommand == null) {
            LOGGER.log(Level.SEVERE, "[KILL] BrowserCommandPort no configurado");
            return;
        }
        browserCommand.cerrarPestana(tabId);
    }

    private boolean esperarCierre(ProcessHandle ph, long segundos) {
        try {
            return ph.onExit().get(segundos, java.util.concurrent.TimeUnit.SECONDS) != null;
        } catch (Exception e) {
            return false;
        }
    }

    private String extraerNombre(String ruta) {
        if (ruta == null || ruta.isBlank()) return "desconocido";
        String limpia = ruta.replace("\"", "").trim();
        String sep = limpia.contains("\\") ? "\\" : "/";
        String[] partes = limpia.split(sep);
        String nombre = partes[partes.length - 1].toLowerCase();
        int punto = nombre.lastIndexOf('.');
        return punto > 0 ? nombre.substring(0, punto) : nombre;
    }

    private String normalizarNombre(String nombre) {
        if (nombre == null || nombre.isBlank()) return "";
        return nombre.toLowerCase().replace(".exe", "").trim();
    }
}