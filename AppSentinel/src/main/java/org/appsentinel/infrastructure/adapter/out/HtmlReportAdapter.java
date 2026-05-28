package org.appsentinel.infrastructure.adapter.out;

import org.appsentinel.domain.model.Categoria;
import org.appsentinel.domain.model.Registro;
import org.appsentinel.domain.port.out.ReportPort;

import java.io.IOException;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.stream.Collectors;

/**
 * HtmlReportAdapter: Genera el informe HTML diario de actividad.
 *
 * LÓGICA DE CATEGORÍAS:
 * - La BD almacena registros de foco con categoría directa: PRODUCTIVO, NEUTRAL,
 *   DISTRACCION, SIN_CLASIFICAR.
 * - Los registros de segundo plano tienen el prefijo "BACKGROUND_" seguido de
 *   la categoría base (ej: BACKGROUND_PRODUCTIVO, BACKGROUND_DISTRACCION).
 * - Este adaptador separa ambos grupos y los presenta en secciones distintas.
 *
 * AGRUPACIÓN:
 * - Dentro de cada sección los registros se agrupan por nombreActividad y se
 *   suman sus duraciones, ya que TimeTrackingService persiste chunks parciales
 *   cada 10 segundos, no un único registro por sesión.
 */
public class HtmlReportAdapter implements ReportPort {

    private static final Logger LOGGER = Logger.getLogger(HtmlReportAdapter.class.getName());

    private static final String PREFIX_BACKGROUND = "BACKGROUND_";
    private static final DateTimeFormatter FMT_FECHA = DateTimeFormatter.ofPattern("dd/MM/yyyy");

    @Override
    public void generarReporteHtml(List<Registro> registros, String rutaSalida) {
        if (registros == null || registros.isEmpty()) {
            LOGGER.log(Level.WARNING, "[REPORTE] Lista de registros vacía. No se genera el informe.");
            return;
        }

        // --- Separar foco vs segundo plano ---
        List<Registro> foco = registros.stream()
            .filter(r -> !r.getCategoria().startsWith(PREFIX_BACKGROUND))
            .collect(Collectors.toList());

        List<Registro> background = registros.stream()
            .filter(r -> r.getCategoria().startsWith(PREFIX_BACKGROUND))
            .collect(Collectors.toList());

        // --- Agrupar por app y sumar duraciones ---
        Map<String, Long> focoAgrupado       = agruparPorApp(foco);
        Map<String, Long> backgroundAgrupado = agruparPorApp(background);

        // --- Totales por categoría (solo foco) ---
        long totalProductivo    = sumarPorCategoria(foco, Categoria.PRODUCTIVO);
        long totalDistraccion   = sumarPorCategoria(foco, Categoria.DISTRACCION);
        long totalNeutral       = sumarPorCategoria(foco, Categoria.NEUTRAL);
        long totalSinClasificar = sumarPorCategoria(foco, Categoria.SIN_CLASIFICAR);
        long totalFoco          = totalProductivo + totalDistraccion + totalNeutral + totalSinClasificar;
        long totalBg            = backgroundAgrupado.values().stream().mapToLong(Long::longValue).sum();

        // --- Calcular puntuación de rendimiento (solo sobre tiempo de foco) ---
        int puntuacion = calcularPuntuacion(totalProductivo, totalDistraccion, totalNeutral, totalFoco);

        // --- Obtener usuario del primer registro ---
        String usuario = registros.get(0).getUsuarioSistema();
        String fecha   = LocalDate.now().format(FMT_FECHA);

        // --- Escribir HTML ---
        try (PrintWriter pw = new PrintWriter(rutaSalida, StandardCharsets.UTF_8)) {
            pw.print(construirHtml(
                usuario, fecha, puntuacion,
                totalFoco, totalProductivo, totalDistraccion, totalNeutral, totalSinClasificar,
                totalBg,
                focoAgrupado, backgroundAgrupado, foco, background
            ));
            LOGGER.log(Level.INFO, "[REPORTE] Informe HTML generado en: {0}", rutaSalida);
        } catch (IOException e) {
            LOGGER.log(Level.SEVERE, "[REPORTE] Error al escribir el archivo HTML", e);
        }
    }

    // -------------------------------------------------------------------------
    // Construcción del HTML
    // -------------------------------------------------------------------------

    private String construirHtml(
            String usuario, String fecha, int puntuacion,
            long totalFoco, long totalProductivo, long totalDistraccion,
            long totalNeutral, long totalSinClasificar, long totalBg,
            Map<String, Long> focoAgrupado, Map<String, Long> backgroundAgrupado,
            List<Registro> foco, List<Registro> background) {

        StringBuilder sb = new StringBuilder();

        sb.append("""
            <!DOCTYPE html>
            <html lang="es">
            <head>
                <meta charset="UTF-8">
                <meta name="viewport" content="width=device-width, initial-scale=1.0">
                <title>AppSentinel — Informe Diario</title>
                <style>
                    * { box-sizing: border-box; margin: 0; padding: 0; }
                    body { font-family: 'Segoe UI', Arial, sans-serif; background: #f0f2f5; color: #1a1a2e; }

                    header {
                        background: linear-gradient(135deg, #1a1a2e 0%, #16213e 100%);
                        color: white; padding: 28px 40px;
                        display: flex; justify-content: space-between; align-items: center;
                    }
                    header h1 { font-size: 1.6rem; letter-spacing: 1px; }
                    header span { font-size: 0.9rem; opacity: 0.7; }

                    .container { max-width: 1100px; margin: 30px auto; padding: 0 20px; }

                    /* Tarjetas de resumen */
                    .resumen {
                        display: grid;
                        grid-template-columns: repeat(auto-fit, minmax(160px, 1fr));
                        gap: 16px; margin-bottom: 30px;
                    }
                    .card {
                        background: white; border-radius: 12px;
                        padding: 20px; text-align: center;
                        box-shadow: 0 2px 8px rgba(0,0,0,0.08);
                    }
                    .card .valor { font-size: 1.8rem; font-weight: 700; margin-bottom: 4px; }
                    .card .etiqueta { font-size: 0.78rem; color: #888; text-transform: uppercase; letter-spacing: 0.5px; }
                    .card.productivo .valor  { color: #27ae60; }
                    .card.distraccion .valor { color: #e74c3c; }
                    .card.neutral .valor     { color: #3498db; }
                    .card.sin .valor         { color: #f39c12; }
                    .card.bg .valor          { color: #8e44ad; }
                    .card.score .valor       { color: #1a1a2e; font-size: 2.4rem; }

                    /* Barra de rendimiento */
                    .barra-wrap { background: white; border-radius: 12px; padding: 24px; margin-bottom: 30px; box-shadow: 0 2px 8px rgba(0,0,0,0.08); }
                    .barra-wrap h2 { font-size: 1rem; margin-bottom: 14px; color: #555; }
                    .barra-bg { background: #eee; border-radius: 20px; height: 22px; overflow: hidden; }
                    .barra-fill { height: 100%; border-radius: 20px; transition: width 0.6s ease; }

                    /* Tablas */
                    .seccion { background: white; border-radius: 12px; padding: 24px; margin-bottom: 24px; box-shadow: 0 2px 8px rgba(0,0,0,0.08); }
                    .seccion h2 { font-size: 1.05rem; margin-bottom: 16px; padding-bottom: 10px; border-bottom: 2px solid #f0f2f5; }
                    table { width: 100%; border-collapse: collapse; font-size: 0.88rem; }
                    th { background: #f8f9fa; padding: 10px 14px; text-align: left; font-weight: 600; color: #555; }
                    td { padding: 9px 14px; border-bottom: 1px solid #f0f2f5; }
                    tr:last-child td { border-bottom: none; }
                    tr:hover td { background: #fafafa; }

                    /* Badges de categoría */
                    .badge {
                        display: inline-block; padding: 3px 10px; border-radius: 20px;
                        font-size: 0.75rem; font-weight: 600; text-transform: uppercase;
                    }
                    .badge.PRODUCTIVO    { background: #d5f5e3; color: #1e8449; }
                    .badge.DISTRACCION   { background: #fadbd8; color: #c0392b; }
                    .badge.NEUTRAL       { background: #d6eaf8; color: #1a5276; }
                    .badge.SIN_CLASIFICAR{ background: #fdebd0; color: #d35400; }
                    .badge.BACKGROUND    { background: #e8daef; color: #7d3c98; }

                    footer { text-align: center; padding: 30px; font-size: 0.8rem; color: #aaa; }
                </style>
            </head>
            <body>
            """);

        // CABECERA
        sb.append("<header>");
        sb.append("<div><h1>&#128737; AppSentinel</h1><div>Informe diario de actividad</div></div>");
        sb.append("<div style='text-align:right'>");
        sb.append("<div style='font-size:1rem;font-weight:600;'>").append(usuario).append("</div>");
        sb.append("<span>").append(fecha).append("</span>");
        sb.append("</div></header>");

        sb.append("<div class='container'>");

        // TARJETAS RESUMEN
        sb.append("<div class='resumen'>");
        sb.append(card("Puntuación", puntuacion + " / 100", "score"));
        sb.append(card("Tiempo productivo", fmt(totalProductivo), "productivo"));
        sb.append(card("Distracciones", fmt(totalDistraccion), "distraccion"));
        sb.append(card("Neutral", fmt(totalNeutral), "neutral"));
        sb.append(card("Sin clasificar", fmt(totalSinClasificar), "sin"));
        sb.append(card("Segundo plano", fmt(totalBg), "bg"));
        sb.append("</div>");

        // BARRA DE RENDIMIENTO
        String colorBarra = puntuacion >= 70 ? "#27ae60" : puntuacion >= 40 ? "#f39c12" : "#e74c3c";
        sb.append("<div class='barra-wrap'>");
        sb.append("<h2>Rendimiento del d&#237;a &#8212; ").append(puntuacion).append(" / 100</h2>");
        sb.append("<div class='barra-bg'><div class='barra-fill' style='width:")
          .append(puntuacion).append("%;background:").append(colorBarra).append(";'></div></div>");
        sb.append("</div>");

        // TABLA FOCO ACTIVO
        sb.append("<div class='seccion'>");
        sb.append("<h2>&#128250; Actividad con foco activo</h2>");
        sb.append(tablaFoco(focoAgrupado, foco));
        sb.append("</div>");

        // TABLA SEGUNDO PLANO
        sb.append("<div class='seccion'>");
        sb.append("<h2>&#127756; Actividad en segundo plano</h2>");
        sb.append(tablaBackground(backgroundAgrupado, background));
        sb.append("</div>");

        sb.append("</div>"); // container

        sb.append("<footer>Generado por AppSentinel &mdash; ").append(fecha).append("</footer>");
        sb.append("</body></html>");

        return sb.toString();
    }

    // -------------------------------------------------------------------------
    // Componentes HTML
    // -------------------------------------------------------------------------

    private String card(String etiqueta, String valor, String clase) {
        return "<div class='card " + clase + "'>" +
               "<div class='valor'>" + valor + "</div>" +
               "<div class='etiqueta'>" + etiqueta + "</div>" +
               "</div>";
    }

    private String tablaFoco(Map<String, Long> agrupado, List<Registro> registros) {
        if (agrupado.isEmpty()) return "<p style='color:#aaa;font-size:0.88rem;'>Sin registros de foco.</p>";

        // Mapa de app → categoría (tomamos la del primer registro encontrado)
        Map<String, String> categoriaPorApp = registros.stream()
            .collect(Collectors.toMap(
                Registro::getNombreActividad,
                Registro::getCategoria,
                (a, b) -> a
            ));

        StringBuilder t = new StringBuilder();
        t.append("<table><tr><th>Aplicación / Web</th><th>Categoría</th><th>Tiempo total</th></tr>");

        agrupado.entrySet().stream()
            .sorted(Map.Entry.<String, Long>comparingByValue().reversed())
            .forEach(e -> {
                String cat = categoriaPorApp.getOrDefault(e.getKey(), "—");
                t.append("<tr>")
                 .append("<td>").append(e.getKey()).append("</td>")
                 .append("<td><span class='badge ").append(cat).append("'>").append(cat).append("</span></td>")
                 .append("<td>").append(fmt(e.getValue())).append("</td>")
                 .append("</tr>");
            });

        t.append("</table>");
        return t.toString();
    }

    private String tablaBackground(Map<String, Long> agrupado, List<Registro> registros) {
        if (agrupado.isEmpty()) return "<p style='color:#aaa;font-size:0.88rem;'>Sin registros de segundo plano.</p>";

        // Para el badge mostramos siempre BACKGROUND
        StringBuilder t = new StringBuilder();
        t.append("<table><tr><th>Aplicación / Web</th><th>Categoría base</th><th>Tiempo total</th></tr>");

        // Mapa app → categoría base (quitamos el prefijo BACKGROUND_)
        Map<String, String> catBasePorApp = registros.stream()
            .collect(Collectors.toMap(
                Registro::getNombreActividad,
                r -> r.getCategoria().replace(PREFIX_BACKGROUND, ""),
                (a, b) -> a
            ));

        agrupado.entrySet().stream()
            .sorted(Map.Entry.<String, Long>comparingByValue().reversed())
            .forEach(e -> {
                String catBase = catBasePorApp.getOrDefault(e.getKey(), "—");
                t.append("<tr>")
                 .append("<td>").append(e.getKey()).append("</td>")
                 .append("<td><span class='badge BACKGROUND'>").append(catBase).append("</span></td>")
                 .append("<td>").append(fmt(e.getValue())).append("</td>")
                 .append("</tr>");
            });

        t.append("</table>");
        return t.toString();
    }

    // -------------------------------------------------------------------------
    // Lógica de agrupación y cálculo
    // -------------------------------------------------------------------------

    /**
     * Agrupa registros por nombreActividad y suma sus duraciones.
     * Ordena de mayor a menor tiempo.
     */
    private Map<String, Long> agruparPorApp(List<Registro> registros) {
        return registros.stream()
            .collect(Collectors.groupingBy(
                Registro::getNombreActividad,
                Collectors.summingLong(Registro::getDuracionSeg)
            ))
            .entrySet().stream()
            .sorted(Map.Entry.<String, Long>comparingByValue().reversed())
            .collect(Collectors.toMap(
                Map.Entry::getKey,
                Map.Entry::getValue,
                (a, b) -> a,
                LinkedHashMap::new
            ));
    }

    /**
     * Suma la duración total de registros que coincidan exactamente con la categoría dada.
     */
    private long sumarPorCategoria(List<Registro> registros, String categoria) {
        return registros.stream()
            .filter(r -> categoria.equals(r.getCategoria()))
            .mapToLong(Registro::getDuracionSeg)
            .sum();
    }

    /**
     * Calcula una puntuación de rendimiento entre 0 y 100 basada únicamente
     * en el tiempo de foco activo.
     *
     * Fórmula:
     *   puntuacion = (productivo * 1.0 - distraccion * 0.8) / totalFoco * 100
     *   - Productivo suma puntos completos.
     *   - Distracción resta el 80% de su tiempo.
     *   - Neutral y SIN_CLASIFICAR no suman ni restan.
     *   - El resultado se clampea entre 0 y 100.
     */
    private int calcularPuntuacion(long productivo, long distraccion, long neutral, long total) {
        if (total == 0) return 0;
        double score = ((productivo * 1.0) - (distraccion * 0.8)) / total * 100.0;
        return (int) Math.max(0, Math.min(100, Math.round(score)));
    }

    /**
     * Formatea segundos en formato legible: 1h 23m 45s.
     */
    private String fmt(long segundos) {
        long h = segundos / 3600;
        long m = (segundos % 3600) / 60;
        long s = segundos % 60;
        if (h > 0) return h + "h " + m + "m " + s + "s";
        if (m > 0) return m + "m " + s + "s";
        return s + "s";
    }
}