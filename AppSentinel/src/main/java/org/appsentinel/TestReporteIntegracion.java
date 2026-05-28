package org.appsentinel;

import org.appsentinel.domain.model.Registro;
import org.appsentinel.domain.service.ReporteDiarioService;
import org.appsentinel.infrastructure.adapter.out.HtmlReportAdapter;
import org.appsentinel.infrastructure.adapter.out.PostgreSQLRepositoryAdapter;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
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
 * FIX TOP 5: Ajustado para validar que el HTML muestra maximo 5 apps por
 * categoria, ya que ReporteDiarioService aplica este filtro antes de generar.
 *
 * RUTA FIJA DEL INFORME:
 *   %USER_HOME%/AppSentinel/reports/DelayLog_integracion.html
 *
 * Precondicion: PostgreSQL corriendo con seed SQL ejecutado y actividad
 * registrada (ej: haber ejecutado TestEscritorio o TestWeb previamente).
 */
public class TestReporteIntegracion {

    private static final Path DIR_REPORTES = Paths.get(
        System.getProperty("user.home"), "AppSentinel", "reports"
    );
    private static final String RUTA_SALIDA = DIR_REPORTES.resolve(
        "DelayLog_integracion.html"
    ).toString();

    public static void main(String[] args) throws Exception {
        System.out.println("=== PRUEBA DE INTEGRACION: Reporte Diario ===");
        System.out.println("Precondicion: PostgreSQL corriendo con seed SQL ejecutado.");
        System.out.println("Precondicion: Debe existir actividad registrada hoy.");

        // 1. Adaptadores reales
        PostgreSQLRepositoryAdapter repo = new PostgreSQLRepositoryAdapter();
        HtmlReportAdapter htmlAdapter = new HtmlReportAdapter(false);
        String usuario = System.getProperty("user.name");

        ReporteDiarioService reporteService = new ReporteDiarioService(
            repo, htmlAdapter, usuario
        );

        // 2. Leer registros EXISTENTES de la BD
        System.out.println("\n>>> Leyendo registros existentes en BD <<<");
        List<Registro> registrosReales = repo.obtenerTodosHoy(usuario);

        if (registrosReales.isEmpty()) {
            System.out.println("[ADVERTENCIA] Sin registros en BD para hoy.");
            System.out.println("Ejecuta primero TestEscritorio o TestWeb para generar datos.");
        }

        Map<String, Long> tiemposPorApp = sumarPorApp(registrosReales);
        tiemposPorApp.forEach((app, seg) -> 
            System.out.println("  [BD] " + app + ": " + fmt(seg)));

        // 3. Generar informe con datos reales
        System.out.println("\n>>> Generando informe HTML <<<");
        System.out.println("Ruta destino: " + RUTA_SALIDA);
        Files.createDirectories(DIR_REPORTES);

        reporteService.generarInformeHoy(RUTA_SALIDA);

        // 4. Validar HTML generado
        System.out.println("\n>>> Validando HTML generado <<<");
        Path rutaArchivo = Paths.get(RUTA_SALIDA);
        String html = Files.readString(rutaArchivo, StandardCharsets.UTF_8);

        mostrarTituloReal(html);
        mostrarH1Real(html);

        if (!registrosReales.isEmpty()) {
            // FIX TOP 5: Validar que apps en HTML existen en BD (no todas las de BD estan en HTML)
            validarAppsHtmlContraBd(html, tiemposPorApp);
            // FIX TOP 5: Validar maximo 5 apps por categoria
            validarMaximo5PorCategoria(html);
        } else {
            System.out.println("[SKIP] Sin datos en BD, omitiendo validacion de contenido");
        }

        validarSinPlaceholdersResiduales(html);
        validarUsuarioEnH1(html, usuario);

        System.out.println("\n[INFO] Informe generado en: " + rutaArchivo.toAbsolutePath());


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
    // Validaciones del HTML
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
     * FIX TOP 5: Valida que las apps que aparecen en el HTML existen en la BD.
     * Ya no valida que TODAS las apps de la BD esten en el HTML (puede haber mas de 5).
     */
    private static void validarAppsHtmlContraBd(String html, Map<String, Long> tiemposPorApp) {
        List<String> appsEnHtml = extraerAppsDeHtml(html);
        
        for (String appHtml : appsEnHtml) {
            if (!tiemposPorApp.containsKey(appHtml)) {
                throw new AssertionError(
                    "ERROR: App '" + appHtml + "' aparece en HTML pero no existe en BD"
                );
            }
        }
        
        System.out.println("[OK] " + appsEnHtml.size() + " apps en HTML validadas contra BD");
    }

    /**
     * FIX TOP 5: Extrae los nombres de apps del HTML generado.
     */
    private static List<String> extraerAppsDeHtml(String html) {
        List<String> apps = new ArrayList<>();
        Pattern p = Pattern.compile("<span class=\"app-name\">(.*?)</span>");
        Matcher m = p.matcher(html);
        while (m.find()) {
            // Desescapar HTML para comparar con nombres de BD
            String app = m.group(1)
                .replace("&amp;", "&")
                .replace("&lt;", "<")
                .replace("&gt;", ">")
                .replace("&quot;", "\"");
            apps.add(app);
        }
        return apps;
    }

    /**
     * FIX TOP 5: Valida que cada seccion del HTML tiene maximo 5 apps.
     */
    private static void validarMaximo5PorCategoria(String html) {
        Pattern ulPattern = Pattern.compile("<ul\\s+class=\"app-list\">(.*?)</ul>", Pattern.DOTALL);
        Matcher ulMatcher = ulPattern.matcher(html);
        
        String[] nombresSecciones = {"Productivas", "Distracciones", "Neutrales", "Background"};
        int seccion = 0;
        int seccionesValidadas = 0;
        
        while (ulMatcher.find()) {
            if (seccion >= nombresSecciones.length) break;
            
            String contenidoUl = ulMatcher.group(1);
            long countLi = Pattern.compile("<li").matcher(contenidoUl).results().count();
            
            if (countLi > 5) {
                throw new AssertionError(
                    "ERROR: Seccion " + nombresSecciones[seccion] + 
                    " tiene " + countLi + " apps (maximo permitido: 5)"
                );
            }
            
            // Solo contar secciones con contenido real (no "Sin actividad registrada")
            if (countLi > 0 && !contenidoUl.contains("Sin actividad registrada")) {
                System.out.println("[OK] Seccion " + nombresSecciones[seccion] + 
                                  ": " + countLi + " apps (max 5)");
                seccionesValidadas++;
            }
            
            seccion++;
        }
        
        System.out.println("[OK] " + seccionesValidadas + " secciones con apps validadas");
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
    // Formateador de tiempo
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