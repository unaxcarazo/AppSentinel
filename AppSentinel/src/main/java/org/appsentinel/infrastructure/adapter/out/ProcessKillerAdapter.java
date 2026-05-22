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
 * FIX WIN-01: Protección por PID propio y padre IDE, no por nombre genérico "java"/"javaw".
 * - Elimina "java", "javaw", "idea", "netbeans" de la lista de protegidos.
 * - Protege SOLO: procesos críticos del SO + AppSentinel mismo + IDE que lanzó la JVM.
 * - Permite cerrar apps Java legítimas clasificadas como distracción (Minecraft, etc.).
 *
 * FIX BUG-2: Protección de constructor contra fallos de ProcessHandle.
 * - resolverPidPadreIde() envuelto en try-catch genérico.
 * - Si ProcessHandle.parent() o info().command() fallan (SecurityException,
 *   UnsupportedOperationException), el constructor sobrevive y el cierre
 *   de distracciones sigue funcionando (sin protección de IDE, que es aceptable).
 * - Log de warning para diagnóstico en entornos restrictivos.
 *
 * PROTECCIÓN A.1 — Anti-PID-Recycling:
 * Windows reutiliza PIDs agresivamente. Entre el escaneo (10s) y la orden de
 * bloqueo (que puede demorar minutos en modo estricto con gracia), otro proceso
 * legítimo podría heredar el PID de la distracción.
 */
public class ProcessKillerAdapter implements KillerPort {

    private static final Logger LOGGER = Logger.getLogger(ProcessKillerAdapter.class.getName());

    // FIX WIN-01: Solo procesos críticos del SO. "java", "javaw", "idea", "netbeans" ELIMINADOS.
    private static final Set<String> PROCESOS_SISTEMA_CRITICOS = Set.of(
        "csrss", "smss", "lsass", "services", "svchost", "winlogon",
        "wininit", "system", "registry", "memory compression",
        "explorer", "taskmgr"
    );

    private static final long GRACEFUL_TIMEOUT_SECONDS = 5;

    private final BrowserCommandPort browserCommand;
    private final long pidPropio;
    private final Optional<Long> pidPadreIde;

    public ProcessKillerAdapter(BrowserCommandPort browserCommand) {
        this.browserCommand = browserCommand;
        this.pidPropio = ProcessHandle.current().pid();
        this.pidPadreIde = resolverPidPadreIde();
    }

    /**
     * FIX WIN-01: Resuelve si AppSentinel fue lanzado desde un IDE.
     * Protege el IDE para que no se cierre accidentalmente al matar una distracción.
     *
     * FIX BUG-2: try-catch genérico que captura cualquier excepción de ProcessHandle.
     * En Windows sin permisos elevados, ProcessHandle.parent() y info().command()
     * pueden lanzar SecurityException. Si falla, loggear warning y retornar empty
     * para que el constructor sobreviva y el cierre de distracciones funcione.
     */
    private Optional<Long> resolverPidPadreIde() {
        try {
            Set<String> ides = Set.of("idea", "studio", "code", "netbeans", "eclipse");
            Optional<ProcessHandle> actual = ProcessHandle.current().parent();

            while (actual.isPresent()) {
                ProcessHandle ph = actual.get();
                // Evitamos fallos si el comando no es accesible por permisos del SO
                String nombre = ph.info().command()
                    .map(this::extraerNombre)
                    .orElse("desconocido");

                if (ides.contains(nombre)) {
                    LOGGER.log(Level.INFO, "[KILL] IDE detectado en cadena de lanzamiento: {0} (PID: {1})",
                        new Object[]{nombre, ph.pid()});
                    return Optional.of(ph.pid());
                }

                if (ph.pid() <= 0) break;
                actual = ph.parent();
            }
        } catch (Exception e) {
            LOGGER.log(Level.WARNING,
                "[KILL] No se pudo resolver cadena de padres (permisos insuficientes o no soportado): {0}",
                e.getMessage());
        }
        return Optional.empty();
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

        // 2a. Procesos del sistema críticos (explorer, csrss, etc.)
        if (PROCESOS_SISTEMA_CRITICOS.contains(nombreReal)) {
            LOGGER.log(Level.SEVERE, "[KILL] BLOQUEADO intento sobre proceso de sistema: PID {0} ({1})",
                new Object[]{pid, nombreReal});
            return false;
        }

        // FIX WIN-01: 2b. Protección por PID propio y padre IDE
        long pidObjetivo = ph.pid();
        if (pidObjetivo == pidPropio) {
            LOGGER.log(Level.SEVERE, "[KILL] BLOQUEADO intento de auto-suicidio (PID propio: {0})", pidPropio);
            return false;
        }
        if (pidPadreIde.isPresent() && pidObjetivo == pidPadreIde.get()) {
            LOGGER.log(Level.SEVERE, "[KILL] BLOQUEADO intento sobre IDE padre: PID {0}", pidPadreIde.get());
            return false;
        }

        // 3. VALIDACIÓN A.1 — Anti-PID-Recycling
        Optional<Instant> startInstantActualOpt = ph.info().startInstant();
        if (startInstantEscaneado != null && startInstantActualOpt.isPresent()) {
            Instant startInstantActual = startInstantActualOpt.get();
            if (!startInstantEscaneado.equals(startInstantActual)) {
                LOGGER.log(Level.SEVERE,
                    "[KILL] ABORTADO — PID {0} fue RECICLADO. " +
                    "Escaneado: {1} (start={2}), Actual: {3} (start={4}).",
                    new Object[]{pid, nombreProceso, startInstantEscaneado, nombreReal, startInstantActual});
                return false;
            }
        } else if (startInstantEscaneado == null) {
            LOGGER.log(Level.WARNING,
                "[KILL] startInstant del escáner no disponible para PID {0}. " +
                "Continuando con validación por nombre únicamente.", pid);
        }

        LOGGER.log(Level.INFO, "[KILL] Ejecutando cierre quirúrgico sobre PID {0} ({1})",
            new Object[]{pid, nombreReal});

        // 4. DESTRUCCIÓN FÍSICA
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
        if (partes.length == 0) return "desconocido";

        String nombre = partes[partes.length - 1].toLowerCase();
        int punto = nombre.lastIndexOf('.');
        return punto > 0 ? nombre.substring(0, punto) : nombre;
    }
}