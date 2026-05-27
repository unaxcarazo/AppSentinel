package org.appsentinel.domain.service;

import org.appsentinel.domain.model.Registro;
import org.appsentinel.domain.port.out.RegistroRepositoryPort;
import org.appsentinel.domain.port.out.ReportPort;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.stream.Collectors;
import org.appsentinel.domain.model.Categoria;

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
 *
 * INVOCACION: AppSentinel.shutdownConReporte() → ctx.reporteService().generarInformeHoy(ruta)
 *             El reporte se genera DESPUES de tracking.finalizar() (pool activo para SELECT).
 */
public class ReporteDiarioService {

    private static final Logger LOGGER = Logger.getLogger(ReporteDiarioService.class.getName());

    private final RegistroRepositoryPort repositorio;
    private final ReportPort reporteHtml;
    private final String usuario;

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
     */
    public void generarInformeHoy(String rutaSalida) {
        LOGGER.log(Level.INFO,
            "[REPORTE] Generando informe diario para usuario: {0}",
            usuario);

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

        // 2. FIX TOP 5: Preparar datos — agrupar, ordenar, truncar a 5 por categoria
        List<Registro> registrosFiltrados = prepararDatosParaReporte(registrosHoy, 5);

        LOGGER.log(Level.INFO,
            "[REPORTE] {0} registros en BD, {1} tras filtrar TOP 5 por categoria. Generando HTML...",
            new Object[]{registrosHoy.size(), registrosFiltrados.size()});

        // 3. Delegar al adaptador HTML (firma del puerto sin cambios)
        reporteHtml.generarReporteHtml(registrosFiltrados, rutaSalida);

        LOGGER.log(Level.INFO, "[REPORTE] Informe generado en: {0}", rutaSalida);
    }

    // -------------------------------------------------------------------------
    // FIX TOP 5: Preparacion de datos para presentacion
    // -------------------------------------------------------------------------

    /**
     * Prepara los registros para el reporte HTML.
     *
     * <p>Agrupa por categoria, ordena por duracion descendente dentro de cada grupo,
     * y limita a N apps por categoria. Retorna una lista plana con los registros
     * seleccionados.</p>
     *
     * @param registros Lista cruda de la BD (una fila por app, ya acumulada via UPSERT)
     * @param limite    Maximo de apps por categoria (ej: 5)
     * @return Lista plana con maximo N registros por categoria, lista para el adaptador
     */
    private List<Registro> prepararDatosParaReporte(List<Registro> registros, int limite) {
    if (registros == null || registros.isEmpty()) {
        return List.of();
    }

    // Agrupar por categoria, fusionando BACKGROUND_* en "BACKGROUND"
    Map<String, List<Registro>> porCategoria = registros.stream()
        .collect(Collectors.groupingBy(r -> {
            String cat = r.getCategoria();
            return cat.startsWith(Categoria.BACKGROUND) ? Categoria.BACKGROUND : cat;
        }));

    List<Registro> resultado = new ArrayList<>();

    for (List<Registro> grupo : porCategoria.values()) {
        // Ordenar por duracion descendente y truncar
        List<Registro> top = grupo.stream()
            .sorted(Comparator.comparingLong(Registro::getDuracionSeg).reversed())
            .limit(limite)
            .collect(Collectors.toList());
        resultado.addAll(top);
    }

    return resultado;
}
}