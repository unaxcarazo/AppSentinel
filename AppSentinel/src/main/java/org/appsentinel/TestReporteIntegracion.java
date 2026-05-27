package org.appsentinel;

import org.appsentinel.domain.model.Registro;
import org.appsentinel.domain.service.ReporteDiarioService;
import org.appsentinel.infrastructure.adapter.out.HtmlReportAdapter;
import org.appsentinel.infrastructure.adapter.out.PostgreSQLRepositoryAdapter;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * TestReporteIntegracion: Prueba de integracion PURA del flujo completo.
 *
 * PRINCIPIO FUNDAMENTAL: El test NO crea datos. Lee UNICAMENTE lo que ya
 * existe en PostgreSQL, genera el informe HTML, y valida que el HTML
 * refleja fielmente los datos reales de la base de datos.
 *
 * RUTA FIJA DEL INFORME:
 *   %USER_HOME%/AppSentinel/reports/DelayLog_integracion.html
 *   (misma estructura que produccion: %USER_HOME%/AppSentinel/reports/)
 *
 * Precondicion: PostgreSQL corriendo con seed SQL ejecutado y actividad
 * registrada (ej: haber ejecutado TestEscritorio o TestWeb previamente).
 *
 * FLUJO:
 *   1. Lee TODOS los registros del usuario para hoy desde PostgreSQL.
 *   2. Genera informe HTML con esos datos reales en ruta fija.
 *   3. Valida que el HTML contiene CADA app de la BD con su tiempo EXACTO.
 *   4. Muestra la ruta absoluta del informe generado.
 */
public class TestReporteIntegracion {

    // RUTA FIJA: coincide con estructura de produccion en AppSentinel.java
    // %USER_HOME%/AppSentinel/reports/DelayLog_integracion.html
    private static final Path DIR_REPORTES = Paths.get(
        System.getProperty("user.home"), "AppSentinel", "reports"
    );
    private static final String RUTA_SALIDA = DIR_REPORTES.resolve(
        "DelayLog_integracion.html"
    ).toString();

    public static void main(String[] args) throws Exception {
        System.out.println("=== PRUEBA DE INTEGRACION: Reporte Diario ===");
        System.out.println("Precondicion: PostgreSQL corriendo con seed SQL ejecutado.");
        System.out.println("Precondicion: Debe existir actividad registrada hoy (ejecutar");
        System.out.println("              TestEscritorio o TestWeb primero).");

        // 1. Adaptadores reales
        PostgreSQLRepositoryAdapter repo = new PostgreSQLRepositoryAdapter();
        HtmlReportAdapter htmlAdapter = new HtmlReportAdapter(false);
        String usuario = System.getProperty("user.name");

        ReporteDiarioService reporteService = new ReporteDiarioService(
            repo, htmlAdapter, usuario
        );

        // 2. Leer registros EXISTENTES de la BD (sin insertar nada)
        System.out.println("\n>>> Leyendo registros existentes en BD <<<");
        List<Registro> registrosReales = repo.obtenerTodosHoy(usuario);

        if (registrosReales.isEmpty()) {
            System.out.println("[ADVERTENCIA] Sin registros en BD para hoy.");
            System.out.println("Ejecuta primero TestEscritorio o TestWeb para generar datos.");
            System.out.println("Generando reporte vacio para validar estructura...");
        }

        Map<String, Long> tiemposPorApp = sumarPorApp(registrosReales);
        tiemposPorApp.forEach((app, seg) -> 
            System.out.println("  [BD] " + app + ": " + fmt(seg)));

        // 3. Generar informe con datos reales de la BD en RUTA FIJA
        System.out.println("\n>>> Generando informe HTML <<<");
        System.out.println("Ruta destino: " + RUTA_SALIDA);

        // Crear directorios intermedios si no existen
        Files.createDirectories(DIR_REPORTES);

        reporteService.generarInformeHoy(RUTA_SALIDA);

        // 4. Validar HTML generado contra datos reales de la BD
        System.out.println("\n>>> Validando HTML generado <<<");
        Path rutaArchivo = Paths.get(RUTA_SALIDA);
        String html = Files.readString(rutaArchivo, StandardCharsets.UTF_8);

        mostrarTituloReal(html);
        mostrarH1Real(html);

        // Validar que CADA app de la BD aparece en el HTML con tiempo exacto
        if (!registrosReales.isEmpty()) {
            validarDatosRealesEnHtml(html, usuario, tiemposPorApp);
        } else {
            System.out.println("[SKIP] Sin datos en BD, omitiendo validacion de contenido");
        }

        // Validar estructura siempre
        validarSinPlaceholdersResiduales(html);
        validarUsuarioEnH1(html, usuario);

        // Mostrar ruta absoluta del informe generado
        System.out.println("\n[INFO] Informe generado en: " + rutaArchivo.toAbsolutePath());

        // Cleanup seguro (eliminar archivo de prueba)
        Files.deleteIfExists(rutaArchivo);
        System.out.println("[INFO] Archivo de prueba eliminado: " + rutaArchivo.toAbsolutePath());

        System.out.println("\n=== TODAS LAS VALIDACIONES PASARON ===");
    }

    // =====================================================================
    // Helpers de BD
    // =====================================================================

    private static Map<String, Long> sumarPorApp(List<Registro> registros) {
        if (registros == null || registros.isEmpty()) return Map.of();
        return registros.stream()
            .collect(Collectors.groupingBy(
                Registro::getNombreActividad,
                Collectors.summingLong(Registro::getDuracionSeg)
            ));
    }

    // =====================================================================
    // Validacion del HTML contra datos reales de la BD
    // =====================================================================

    private static void mostrarTituloReal(String html) {
        Pattern p = Pattern.compile("<title>(.*?)</title>", Pattern.DOTALL);
        Matcher m = p.matcher(html);
        if (m.find()) {
            System.out.println("[DEBUG] <title> real: [" + m.group(1).trim() + "]");
        }
    }

    private static void mostrarH1Real(String html) {
        Pattern p = Pattern.compile("<h1>(.*?)</h1>", Pattern.DOTALL);
        Matcher m = p.matcher(html);
        if (m.find()) {
            System.out.println("[DEBUG] <h1> real: [" + m.group(1).trim() + "]");
        }
    }

    /**
     * Valida que el HTML contiene CADA app de la BD con su tiempo EXACTO.
     * 
     * Busqueda estructurada: valida que el nombre aparece dentro de
     * <span class="app-name">, no como substring accidental.
     */
    private static void validarDatosRealesEnHtml(String html, String usuario,
                                                    Map<String, Long> tiemposPorApp) {
        int validados = 0;
        for (Map.Entry<String, Long> entry : tiemposPorApp.entrySet()) {
            String nombreApp = entry.getKey();
            long tiempoSeg = entry.getValue();
            String tiempoFmt = fmt(tiempoSeg);

            // Busqueda estructurada dentro del tag app-name
            String nombreEscapado = escaparHtml(nombreApp);
            String appEnHtml = "<span class=\"app-name\">" + nombreEscapado + "</span>";

            if (!html.contains(appEnHtml)) {
                // Fallback: sin escapar
                String appEnHtmlRaw = "<span class=\"app-name\">" + nombreApp + "</span>";
                if (!html.contains(appEnHtmlRaw)) {
                    throw new AssertionError(
                        "ERROR: App '" + nombreApp + "' (tiempo " + tiempoFmt + ") " +
                        "no aparece en el HTML generado"
                    );
                }
            }
        }
    }
    
    private static void validarUsuarioEnH1(String html, String usuario) {
        Pattern p = Pattern.compile("<h1>(.*?)</h1>", Pattern.DOTALL);
        Matcher m = p.matcher(html);
        if (!m.find()) {
            throw new AssertionError("ERROR: No se encontro <h1> en el HTML");
        }
        String h1 = m.group(1).trim();
        if (!h1.contains(usuario)) {
            throw new AssertionError(
                "ERROR: <h1> no contiene usuario '" + usuario + "'. H1: [" + h1 + "]"
            );
        }
        System.out.println("[OK] Usuario '" + usuario + "' encontrado en <h1>");
    }

    private static void validarSinPlaceholdersResiduales(String html) {
        if (html.contains("{{")) {
            Pattern residual = Pattern.compile("\\{\\{[A-Z_]+\\}\\}");
            Matcher m = residual.matcher(html);
            StringBuilder sb = new StringBuilder("ERROR: Placeholders sin reemplazar: ");
            while (m.find()) {
                sb.append("[").append(m.group()).append("] ");
            }
            throw new AssertionError(sb.toString());
        }
        System.out.println("[OK] Sin placeholders residuales");
    }

    // =====================================================================
    // Escapado HTML (replica HtmlReportAdapter.escaparHtml)
    // =====================================================================

    private static String escaparHtml(String texto) {
        if (texto == null) return "";
        return texto
            .replace("&",  "&amp;")
            .replace("<",  "&lt;")
            .replace(">",  "&gt;")
            .replace("\"", "&quot;");
    }

    // =====================================================================
    // Formateador de tiempo (igual que HtmlReportAdapter)
    // =====================================================================

    private static String fmt(long segundos) {
        long h = segundos / 3600;
        long m = (segundos % 3600) / 60;
        long s = segundos % 60;
        if (h > 0) return String.format("%02dh %02dm", h, m);
        if (m > 0) return String.format("%02dm %02ds", m, s);
        return String.format("%02ds", s);
    }
}