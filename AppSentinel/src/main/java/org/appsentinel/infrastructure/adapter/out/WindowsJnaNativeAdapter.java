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
 * NOTA: Este adaptador es específico de Windows. Para otros sistemas
 * operativos, usar JavaProcessResolverAdapter (ProcessHandle puro).
 */
public class WindowsJnaNativeAdapter {

    private static final Logger LOGGER = Logger.getLogger(WindowsJnaNativeAdapter.class.getName());

    public Optional<VentanaDetectada> consultarVentanaActiva() {
        try {
            WinDef.HWND hwnd = User32.INSTANCE.GetForegroundWindow();
            if (hwnd == null) {
                // Uso explícito del método .log() con nivel FINE
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

            String nombreProceso = resolverNombreProceso(pid);

            // Sintaxis parametrizada explícita que lee la variable LOGGER
            LOGGER.log(Level.FINE, "[SCAN] Foco detectado: {0} (PID: {1})", new Object[]{nombreProceso, pid});

            return Optional.of(new VentanaDetectada(nombreProceso, titulo, pid));

        } catch (UnsatisfiedLinkError e) {
            LOGGER.log(Level.SEVERE, "[FATAL] JNA no pudo cargar DLL nativa de Windows", e);
            return Optional.empty();
        } catch (Exception e) {
            LOGGER.log(Level.SEVERE, "[ERROR] Fallo inesperado en API nativa Windows", e);
            return Optional.empty();
        }
    }

    private String resolverNombreProceso(int pid) {
        return ProcessHandle.of(pid)
            .flatMap(ph -> ph.info().command())
            .map(this::extraerNombre)
            .orElse("desconocido");
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

    public record VentanaDetectada(String nombreProceso, String tituloVentana, int pid) {}
}