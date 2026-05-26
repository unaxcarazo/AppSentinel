package org.appsentinel.domain.service;

import org.appsentinel.domain.model.Registro;
import org.appsentinel.domain.port.out.RegistroRepositoryPort;
import org.appsentinel.domain.port.out.ReportPort;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDate;
import java.util.List;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * ReporteDiarioService: Servicio de aplicacion para generacion de informes HTML.
 *
 * ARQUITECTURA HEXAGONAL:
 * - Vive en {@code domain.service} (capa de aplicacion, no dominio puro).
 * - Orquesta la lectura de datos reales desde PostgreSQL y la generacion del HTML.
 * - No contiene logica de negocio de seguimiento ni bloqueo; solo coordina
 *   {@code RegistroRepositoryPort} → {@code ReportPort}.
 *
 * FLUJO DE PRODUCCION:
 *   1. TimeTrackingService acumula duracion durante el dia.
 *   2. Al cerrar AppSentinel, este servicio lee todos los registros de hoy.
 *   3. Delega en HtmlReportAdapter para generar DelayLog.html con datos reales.
 *   4. El usuario abre DelayLog.html en su navegador para ver el resumen.
 *
 * FIX: Se invoca desde AppWiring.detenerTodo() DESPUES de tracking.finalizar()
 * para garantizar que todos los chunks pendientes ya esten en PostgreSQL.
 */
public class ReporteDiarioService {

    private static final Logger LOGGER = Logger.getLogger(ReporteDiarioService.class.getName());

    private final RegistroRepositoryPort repositorio;
    private final ReportPort reporteHtml;
    private final String usuario;

    // FIX: modoEstricto eliminado. HtmlReportAdapter ya conoce el modo
    // porque se construye con el en AppWiring. Recibirlo aqui tambien
    // era redundante y creaba confusion sobre quien es la fuente de verdad.

    public ReporteDiarioService(RegistroRepositoryPort repositorio,
                                ReportPort reporteHtml,
                                String usuario) {
        this.repositorio = repositorio;
        this.reporteHtml = reporteHtml;
        this.usuario     = usuario;
    }

    /**
     * Genera el informe HTML con los datos reales acumulados durante el dia.
     *
     * <p>Precondicion: TimeTrackingService.finalizar() ya hizo flush de todos
     * los chunks pendientes a PostgreSQL. Si no, el informe tendra datos incompletos.</p>
     *
     * @param rutaSalida Ruta absoluta o relativa donde guardar DelayLog.html.
     *                   El directorio padre debe existir; este metodo lo crea
     *                   si no existe para evitar NoSuchFileException silenciosa
     *                   dentro del adaptador.
     */
    public void generarInformeHoy(String rutaSalida) {
        LOGGER.log(Level.INFO,
            "[REPORTE] Generando informe diario para usuario: {0}",
            usuario);

        // Garantizar que el directorio padre exista antes de delegar al adaptador.
        // HtmlReportAdapter usa Files.writeString() que no crea directorios intermedios.
        Path path = Paths.get(rutaSalida);
        Path directorioPadre = path.getParent();
        if (directorioPadre != null) {
            try {
                Files.createDirectories(directorioPadre);
            } catch (IOException e) {
                LOGGER.log(Level.SEVERE,
                    "[REPORTE] No se pudo crear el directorio de salida: {0}",
                    directorioPadre.toAbsolutePath());
                return;
            }
        }

        // 1. Recuperar datos reales de PostgreSQL
        List<Registro> registrosHoy = repositorio.obtenerTodosHoy(usuario);

        if (registrosHoy.isEmpty()) {
            LOGGER.log(Level.WARNING,
                "[REPORTE] Sin actividad registrada hoy para {0}. No se genera informe.",
                usuario);
            return;
        }

        LOGGER.log(Level.INFO,
            "[REPORTE] {0} registros encontrados para hoy. Generando HTML...",
            registrosHoy.size());

        // 2. Delegar al adaptador HTML (inyectado, no instanciado aqui)
        reporteHtml.generarReporteHtml(registrosHoy, rutaSalida);

        LOGGER.log(Level.INFO, "[REPORTE] Informe generado en: {0}", rutaSalida);
    }

    /**
     * Genera el informe en la ruta por defecto del directorio de trabajo.
     * Uso: AppWiring.detenerTodo() llama esto sin preocuparse de rutas.
     */
    public void generarInformeHoyDefault() {
        String nombreArchivo = "DelayLog_" + LocalDate.now() + ".html";
        Path rutaDefault = Paths.get(System.getProperty("user.dir"), nombreArchivo);
        generarInformeHoy(rutaDefault.toString());
    }
}