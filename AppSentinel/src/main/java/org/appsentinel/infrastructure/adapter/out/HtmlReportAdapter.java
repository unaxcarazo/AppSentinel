package org.appsentinel.infrastructure.adapter.out;

import org.appsentinel.domain.model.Categoria;
import org.appsentinel.domain.model.Registro;
import org.appsentinel.domain.port.out.ReportPort;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.stream.Collectors;

/**
 * HtmlReportAdapter: Genera el informe HTML diario de actividad.
 *
 * Carga DelayLog.html como plantilla desde resources e inyecta datos
 * reales reemplazando placeholders {{PLACEHOLDER}}.
 *
 * CSS separado: styles.css se copia al mismo directorio de salida que el HTML.
 *
 * FIX CATEGORIAS:
 * - NEUTRAL: solo apps explicitamente categorizadas como NEUTRAL.
 * - SIN_CLASIFICAR: NO se suma a NEUTRAL. Apps no asignadas por el usuario
 *   quedan fuera del reporte HTML (el template actual no tiene seccion para ellas).
 * - BACKGROUND_: segundo plano (prefijo interno).
 *
 * MINI-BARRAS: Porcentaje relativo al maximo de esa categoria (no global).
 * La app con mas tiempo en la lista = 100%, el resto proporcional.
 */
public class HtmlReportAdapter implements ReportPort {

    private static final Logger LOGGER = Logger.getLogger(HtmlReportAdapter.class.getName());

    private static final String PREFIX_BACKGROUND  = "BACKGROUND_";
    private static final DateTimeFormatter FMT_FECHA = DateTimeFormatter.ofPattern("EEE, dd MMM");
    private static final String PLANTILLA_PATH     = "/org/appsentinel/DelayLog.html";
    private static final String CSS_RESOURCE_PATH  = "/org/appsentinel/styles.css";

    private final boolean modoEstricto;

    public HtmlReportAdapter(boolean modoEstricto) {
        this.modoEstricto = modoEstricto;
    }

    @Override
    public void generarReporteHtml(List<Registro> registros, String rutaSalida) {
        if (registros == null || registros.isEmpty()) {
            LOGGER.log(Level.WARNING, "[REPORTE] Lista de registros vacia. No se genera el informe.");
            return;
        }

        String plantilla = cargarRecursoTexto(PLANTILLA_PATH);
        if (plantilla == null) {
            LOGGER.log(Level.SEVERE, "[REPORTE] No se pudo cargar la plantilla {0}. Abortando.", PLANTILLA_PATH);
            return;
        }

        // --- Separar foco vs segundo plano ---
        List<Registro> foco = registros.stream()
            .filter(r -> !r.getCategoria().startsWith(PREFIX_BACKGROUND))
            .collect(Collectors.toList());

        List<Registro> background = registros.stream()
            .filter(r -> r.getCategoria().startsWith(PREFIX_BACKGROUND))
            .collect(Collectors.toList());

        // --- Agrupar por app y categoria (filtrado previo) ---
        Map<String, Long> productivoAgrupado  = agruparPorAppYCategoria(foco, Categoria.PRODUCTIVO);
        Map<String, Long> distraccionAgrupado = agruparPorAppYCategoria(foco, Categoria.DISTRACCION);
        // FIX: NEUTRAL ya NO absorbe SIN_CLASIFICAR. Solo NEUTRAL puro.
        Map<String, Long> neutralAgrupado     = agruparPorAppYCategoria(foco, Categoria.NEUTRAL);
        Map<String, Long> backgroundAgrupado  = agruparPorApp(background);

        // --- Totales ---
        long totalProductivo    = sumarMap(productivoAgrupado);
        long totalDistraccion   = sumarMap(distraccionAgrupado);
        long totalNeutral       = sumarMap(neutralAgrupado);
        // FIX: SIN_CLASIFICAR excluido del reporte HTML (no suma a total ni a NEUTRAL)
        long totalFoco          = totalProductivo + totalDistraccion + totalNeutral;
        long totalBg            = sumarMap(backgroundAgrupado);
        long totalGeneral       = totalFoco + totalBg;

        // --- Porcentajes (garantizar suma = 100%) ---
        int pctProductivo  = totalGeneral > 0 ? (int) Math.round((double) totalProductivo  / totalGeneral * 100) : 0;
        int pctDistraccion = totalGeneral > 0 ? (int) Math.round((double) totalDistraccion / totalGeneral * 100) : 0;
        int pctNeutral     = totalGeneral > 0 ? (int) Math.round((double) totalNeutral     / totalGeneral * 100) : 0;
        int pctBackground  = 100 - pctProductivo - pctDistraccion - pctNeutral;
        if (pctBackground < 0) {
            pctBackground = 0;
            if (pctProductivo >= pctDistraccion && pctProductivo >= pctNeutral) {
                pctProductivo = 100 - pctDistraccion - pctNeutral;
            } else if (pctDistraccion >= pctNeutral) {
                pctDistraccion = 100 - pctProductivo - pctNeutral;
            } else {
                pctNeutral = 100 - pctProductivo - pctDistraccion;
            }
        }

        // --- Metadatos ---
        String usuario = registros.get(0).getUsuarioSistema();
        String fecha   = LocalDate.now().format(FMT_FECHA);
        String estado  = modoEstricto ? "Modo Estricto" : "Modo Normal";

        // --- Listas HTML ---
        String listaProductivas   = generarListaHtml(productivoAgrupado,  "fill-productive");
        String listaDistracciones = generarListaHtml(distraccionAgrupado, "fill-distractions");
        String listaNeutrales     = generarListaHtml(neutralAgrupado,     "fill-neutral");
        String listaBackground    = generarListaHtml(backgroundAgrupado,  "fill-background");

        // --- Reemplazar placeholders ---
        String html = plantilla
            .replace("{{USUARIO}}",           escaparHtml(usuario))
            .replace("{{FECHA}}",             fecha)
            .replace("{{ESTADO}}",            estado)
            .replace("{{TIEMPO_TOTAL}}",      fmt(totalGeneral))
            .replace("{{PCT_PRODUCTIVO}}",    pctProductivo  + "%")
            .replace("{{PCT_DISTRACCION}}",   pctDistraccion + "%")
            .replace("{{PCT_NEUTRAL}}",       pctNeutral     + "%")
            .replace("{{PCT_BACKGROUND}}",    pctBackground  + "%")
            .replace("{{TIEMPO_PRODUCTIVO}}", fmt(totalProductivo))
            .replace("{{TIEMPO_DISTRACCION}}",fmt(totalDistraccion))
            .replace("{{TIEMPO_NEUTRAL}}",    fmt(totalNeutral))
            .replace("{{TIEMPO_BACKGROUND}}", fmt(totalBg))
            .replace("{{TOTAL_APPS}}", String.valueOf(
                productivoAgrupado.size() + distraccionAgrupado.size() +
                neutralAgrupado.size()    + backgroundAgrupado.size()))
            // FIX HTML: placeholders envueltos en comentarios para validacion HTML5 estricta
            .replace("<!-- {{LISTA_PRODUCTIVAS}} -->",   listaProductivas)
            .replace("<!-- {{LISTA_DISTRACCIONES}} -->", listaDistracciones)
            .replace("<!-- {{LISTA_NEUTRALES}} -->",     listaNeutrales)
            .replace("<!-- {{LISTA_BACKGROUND}} -->",      listaBackground);

        // --- Escribir HTML ---
        try {
            Files.writeString(Paths.get(rutaSalida), html, StandardCharsets.UTF_8);
            LOGGER.log(Level.INFO, "[REPORTE] HTML generado en: {0}", rutaSalida);
        } catch (IOException e) {
            LOGGER.log(Level.SEVERE, "[REPORTE] Error al escribir el archivo HTML", e);
            return;
        }

        // --- Copiar CSS al directorio de salida ---
        Path parent = Paths.get(rutaSalida).getParent();
        if (parent != null) {
            copiarCssAlDirectorioSalida(parent.toString());
        } else {
            LOGGER.log(Level.WARNING, "[REPORTE] rutaSalida sin directorio padre. CSS no copiado.");
        }
    }

    // -------------------------------------------------------------------------
    // Carga de recursos
    // -------------------------------------------------------------------------

    private String cargarRecursoTexto(String ruta) {
        try (InputStream is = getClass().getResourceAsStream(ruta)) {
            if (is == null) {
                LOGGER.log(Level.SEVERE, "[REPORTE] Recurso no encontrado en classpath: {0}", ruta);
                return null;
            }
            return new String(is.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            LOGGER.log(Level.SEVERE, "[REPORTE] Error leyendo recurso: {0}", ruta);
            return null;
        }
    }

    private void copiarCssAlDirectorioSalida(String directorio) {
        try (InputStream is = getClass().getResourceAsStream(CSS_RESOURCE_PATH)) {
            if (is == null) {
                LOGGER.log(Level.WARNING,
                    "[REPORTE] styles.css no encontrado en classpath: {0}. Reporte sin estilos.",
                    CSS_RESOURCE_PATH);
                return;
            }
            Path destino = Paths.get(directorio, "styles.css");
            Files.write(destino, is.readAllBytes());
            LOGGER.log(Level.INFO, "[REPORTE] styles.css copiado a: {0}", destino);
        } catch (IOException e) {
            LOGGER.log(Level.SEVERE, "[REPORTE] Error copiando styles.css", e);
        }
    }

    // -------------------------------------------------------------------------
    // Agrupacion
    // -------------------------------------------------------------------------

    private Map<String, Long> agruparPorAppYCategoria(List<Registro> registros, String categoria) {
        return registros.stream()
            .filter(r -> categoria.equals(r.getCategoria()))
            .collect(Collectors.groupingBy(
                Registro::getNombreActividad,
                Collectors.summingLong(Registro::getDuracionSeg)
            ));
    }

    private Map<String, Long> agruparPorApp(List<Registro> registros) {
        return registros.stream()
            .collect(Collectors.groupingBy(
                Registro::getNombreActividad,
                Collectors.summingLong(Registro::getDuracionSeg)
            ));
    }

    private long sumarMap(Map<String, Long> map) {
        return map.values().stream().mapToLong(Long::longValue).sum();
    }

    // -------------------------------------------------------------------------
    // Generacion de listas HTML
    // -------------------------------------------------------------------------

    private String generarListaHtml(Map<String, Long> agrupado, String cssFillClass) {
        if (agrupado == null || agrupado.isEmpty()) {
            return "<li class=\"app-item-vertical\">"
                + "<div class=\"app-info-row\">"
                + "<span><span class=\"app-index\">01.</span>"
                + "<span class=\"app-name\">Sin actividad registrada</span></span>"
                + "<span class=\"app-time\">00m</span>"
                + "</div>"
                + "<div class=\"app-mini-bar\">"
                + "<div class=\"mini-fill " + cssFillClass + "\" style=\"width: 0%;\"></div>"
                + "</div></li>";
        }

        long maxTiempo = agrupado.values().stream().max(Long::compare).orElse(1L);
        StringBuilder sb = new StringBuilder();
        int index = 1;

        List<Map.Entry<String, Long>> ordenados = agrupado.entrySet().stream()
            .sorted(Map.Entry.<String, Long>comparingByValue().reversed())
            .collect(Collectors.toList());

        for (Map.Entry<String, Long> entry : ordenados) {
            String nombre = entry.getKey();
            long segs     = entry.getValue();
            int pct       = (int) Math.round((double) segs / maxTiempo * 100);
            String idx    = String.format("%02d.", index);

            sb.append("<li class=\"app-item-vertical\">");
            sb.append("<div class=\"app-info-row\">");
            sb.append("<span>")
              .append("<span class=\"app-index\">").append(idx).append("</span>")
              .append("<span class=\"app-name\">").append(escaparHtml(nombre)).append("</span>")
              .append("</span>");
            sb.append("<span class=\"app-time\">").append(fmt(segs)).append("</span>");
            sb.append("</div>");
            sb.append("<div class=\"app-mini-bar\">")
              .append("<div class=\"mini-fill ").append(cssFillClass)
              .append("\" style=\"width: ").append(pct).append("%;\"></div>")
              .append("</div>");
            sb.append("</li>");
            index++;
        }

        return sb.toString();
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private String fmt(long segundos) {
        long h = segundos / 3600;
        long m = (segundos % 3600) / 60;
        long s = segundos % 60;
        if (h > 0) return String.format("%02dh %02dm", h, m);
        if (m > 0) return String.format("%02dm %02ds", m, s);
        return String.format("%02ds", s);
    }

    private String escaparHtml(String texto) {
        if (texto == null) return "";
        return texto
            .replace("&",  "&amp;")
            .replace("<",  "&lt;")
            .replace(">",  "&gt;")
            .replace("\"", "&quot;");
    }
}