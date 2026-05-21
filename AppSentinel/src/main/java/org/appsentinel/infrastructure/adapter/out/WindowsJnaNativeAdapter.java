package org.appsentinel.infrastructure.adapter.out;

import com.sun.jna.Native;
import com.sun.jna.platform.win32.User32;
import com.sun.jna.platform.win32.WinDef;
import com.sun.jna.ptr.IntByReference;

import java.util.Optional;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * WindowsJnaNativeAdapter: Adaptador de SALIDA.
 *
 * Consulta activamente al sistema operativo Windows via JNA para obtener
 * la ventana de primer plano (foreground window) y su proceso asociado.
 *
 * FIX 2.4 (Filtrado de procesos del sistema):
 * Aplica la misma lógica de filtrado que JavaProcessResolverAdapter.esProcesoSistema()
 * para evitar que ventanas de procesos SYSTEM (dwm.exe, csrss.exe, etc.)
 * se propaguen al dominio como actividad válida del usuario.
 *
 * Criterios de filtrado:
 *   - info().user() contiene "nt authority", "system", "local service", "network service"
 *   - info().command() contiene "system", "kernel", "init"
 *   - Fallback conservador: pid < 100 (solo si todo lo demás falla)
 *
 * NOTA: Este adaptador es específico de Windows. Para otros sistemas
 * operativos, usar JavaProcessResolverAdapter (ProcessHandle puro).
 */
public class WindowsJnaNativeAdapter {

    private static final Logger LOGGER = Logger.getLogger(WindowsJnaNativeAdapter.class.getName());

    public Optional<VentanaDetectada> consultarVentanaActiva() {
        try {
            WinDef.HWND hwnd = User32.INSTANCE.GetForegroundWindow();
            if (hwnd == null) {
                LOGGER.log(Level.FINE, "[SCAN] OS reporta: sin ventana activa");
                return Optional.empty();
            }

            char[] buffer = new char[1024];
            User32.INSTANCE.GetWindowText(hwnd, buffer, 1024);
            String titulo = Native.toString(buffer).trim();
            if (titulo.isEmpty()) {
                LOGGER.log(Level.FINE, "[SCAN] Ventana activa sin título legible");
                return Optional.empty();
            }

            IntByReference pidRef = new IntByReference();
            User32.INSTANCE.GetWindowThreadProcessId(hwnd, pidRef);
            int pid = pidRef.getValue();

            // FIX 2.4: Resolver ProcessHandle una sola vez y reutilizar
            Optional<ProcessHandle> phOpt = ProcessHandle.of(pid);

            // Validar que el proceso no sea del sistema antes de reportar
            if (phOpt.isPresent() && esProcesoSistema(phOpt.get())) {
                String nombreSistema = phOpt
                    .flatMap(ph -> ph.info().command())
                    .map(this::extraerNombre)
                    .orElse("desconocido");
                LOGGER.log(Level.FINE,
                    "[SCAN] Ventana de proceso del sistema ignorada: {0} (PID: {1})",
                    new Object[]{nombreSistema, pid});
                return Optional.empty();
            }

            // Reutilizar phOpt para resolver nombre — evita segundo ProcessHandle.of(pid)
            String nombreProceso = phOpt
                .flatMap(ph -> ph.info().command())
                .map(this::extraerNombre)
                .orElse("desconocido");

            LOGGER.log(Level.FINE, "[SCAN] Foco detectado: {0} (PID: {1})",
                new Object[]{nombreProceso, pid});

            return Optional.of(new VentanaDetectada(nombreProceso, titulo, pid));

        } catch (UnsatisfiedLinkError e) {
            LOGGER.log(Level.SEVERE, "[FATAL] JNA no pudo cargar DLL nativa de Windows", e);
            return Optional.empty();
        } catch (Exception e) {
            LOGGER.log(Level.SEVERE, "[ERROR] Fallo inesperado en API nativa Windows", e);
            return Optional.empty();
        }
    }

    /**
     * FIX 2.4: Filtra procesos del sistema que no son del usuario.
     * Replica la lógica de JavaProcessResolverAdapter.esProcesoSistema()
     * para mantener consistencia entre ambos adaptadores.
     *
     * Windows: NT AUTHORITY\SYSTEM, LOCAL SERVICE, NETWORK SERVICE
     * Linux: root
     * macOS: root
     */
    private boolean esProcesoSistema(ProcessHandle ph) {
        Optional<String> userOpt = ph.info().user();
        if (userOpt.isPresent()) {
            String user = userOpt.get().toLowerCase();
            if (user.contains("nt authority") ||
                user.contains("system") ||
                user.contains("local service") ||
                user.contains("network service") ||
                user.equals("root")) {
                return true;
            }
        }

        Optional<String> cmdOpt = ph.info().command();
        if (cmdOpt.isPresent()) {
            String cmd = cmdOpt.get().toLowerCase();
            return cmd.contains("system") || cmd.contains("kernel") || cmd.contains("init");
        }

        // Fallback conservador: pid < 100 (solo si todo lo demás falla)
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

    public record VentanaDetectada(String nombreProceso, String tituloVentana, int pid) {}
}