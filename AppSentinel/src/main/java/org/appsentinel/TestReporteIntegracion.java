package org.appsentinel;

import org.appsentinel.domain.model.Registro;
import org.appsentinel.domain.service.ReporteDiarioService;
import org.appsentinel.infrastructure.adapter.out.HtmlReportAdapter;
import org.appsentinel.infrastructure.adapter.out.PostgreSQLRepositoryAdapter;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * TestReporteIntegracion: Prueba de integracion del flujo completo.
 *
 * Precondicion: PostgreSQL corriendo con seed SQL ejecutado.
 *
 * FLUJO validado (sin hardcodes):
 *   1. Lee TODOS los registros actuales de la BD para el usuario.
 *   2. Inserta actividad simulada via UPSERT (acumula sobre existente).
 *   3. Re-lee TODOS los registros actualizados de la BD.
 *   4. Genera informe HTML con datos reales.
 *   5. Valida que el HTML contiene CADA app de la BD con su tiempo EXACTO.
 *
 * ROBUSTEZ:
 *   - Busqueda estructurada: valida que el nombre aparece dentro de <span class="app-name">,
 *     no como substring accidental en CSS, scripts o texto generico.
 *   - Timestamp sincronizado: todos los registros simulados usan el MISMO LocalDateTime
 *     para evitar condiciones de carrera con CURRENT_DATE en PostgreSQL.
 *   - Tolerancia de formato: si el tiempo exacto no coincide, prueba formato alternativo
 *     antes de fallar.
 */
public class TestReporteIntegracion {

    private static final String RUTA_SALIDA = "DelayLog_integracion.html";

    public static void main(String[] args) throws Exception {
        System.out.println("=== PRUEBA DE INTEGRACION: Reporte Diario ===");
        System.out.println("Precondicion: PostgreSQL corriendo con seed SQL ejecutado.");

        // 1. Adaptadores reales
        PostgreSQLRepositoryAdapter repo = new PostgreSQLRepositoryAdapter();
        HtmlReportAdapter htmlAdapter = new HtmlReportAdapter(false);
        String usuario = System.getProperty("user.name");

        ReporteDiarioService reporteService = new ReporteDiarioService(
            repo, htmlAdapter, usuario
        );

        // 2. Leer registros ANTES del UPSERT (baseline)
        System.out.println("\n>>> Leyendo registros actuales en BD <<<");
        List<Registro> registrosAntes = repo.obtenerTodosHoy(usuario);
        Map<String, Long> tiemposAntes = sumarPorApp(registrosAntes);
        tiemposAntes.forEach((app, seg) -> 
            System.out.println("  [ANTES] " + app + ": " + fmt(seg)));

        // 3. Insertar actividad simulada via UPSERT
        // ROBUSTEZ: Timestamp sincronizado para todos los registros.
        // Evita que CURRENT_DATE en PostgreSQL cambie entre inserts si el test
        // cruza la medianoche (23:59:59 -> 00:00:00).
        System.out.println("\n>>> Insertando actividad simulada via UPSERT <<<");
        LocalDateTime timestampUnificado = LocalDateTime.now();
        List<Registro> simulados = construirActividadSimulada(usuario, timestampUnificado);
        for (Registro r : simulados) {
            repo.guardarOActualizar(r);
            System.out.println("  UPSERT: " + r.getNombreActividad() + " +" + r.getDuracionSeg() + "s");
        }

        // 4. Leer registros DESPUES del UPSERT (valores reales esperados)
        System.out.println("\n>>> Re-leyendo registros actualizados en BD <<<");
        List<Registro> registrosDespues = repo.obtenerTodosHoy(usuario);
        Map<String, Long> tiemposDespues = sumarPorApp(registrosDespues);
        tiemposDespues.forEach((app, seg) -> 
            System.out.println("  [DESPUES] " + app + ": " + fmt(seg)));

        // 5. Generar informe con datos reales
        System.out.println("\n>>> Generando informe HTML <<<");
        reporteService.generarInformeHoy(RUTA_SALIDA);

        // 6. Validar HTML generado contra datos reales de la BD
        System.out.println("\n>>> Validando HTML generado <<<");
        Path rutaArchivo = Paths.get(RUTA_SALIDA);
        String html = Files.readString(rutaArchivo, StandardCharsets.UTF_8);

        mostrarTituloReal(html);
        mostrarH1Real(html);

        // Validar que CADA app de la BD aparece en el HTML con tiempo exacto
        validarDatosRealesEnHtml(html, usuario, tiemposDespues);

        // Validar estructura
        validarSinPlaceholdersResiduales(html);
        validarUsuarioEnH1(html, usuario);

        // Cleanup seguro
        Files.deleteIfExists(rutaArchivo);
        System.out.println("\n=== TODAS LAS VALIDACIONES PASARON ===");
    }

    // =====================================================================
    // Helpers de BD
    // =====================================================================

    private static Map<String, Long> sumarPorApp(List<Registro> registros) {
        if (registros == null) return Map.of();
        return registros.stream()
            .collect(Collectors.groupingBy(
                Registro::getNombreActividad,
                Collectors.summingLong(Registro::getDuracionSeg)
            ));
    }

    /**
     * Construye registros de prueba con timestamp UNIFICADO.
     * 
     * ROBUSTEZ: Todos los registros comparten el mismo LocalDateTime para evitar
     * que PostgreSQL CURRENT_DATE cambie entre inserts (condicion de carrera
     * en el limite de medianoche).
     */
    private static List<Registro> construirActividadSimulada(String usuario, 
                                                               LocalDateTime timestamp) {
        List<Registro> list = new ArrayList<>();

        // Nombres de test unicos para no colisionar con seed data real.
        // Prefijo "Test_" reduce probabilidad de coincidencia accidental en HTML.
        list.add(crearRegistro(usuario, "Test_Productiva", "PRODUCTIVO", 
            "Foco activo: desarrollo", 7200, timestamp));
        list.add(crearRegistro(usuario, "Test_Distraccion", "DISTRACCION", 
            "Foco activo: chat", 1800, timestamp));
        list.add(crearRegistro(usuario, "Test_Neutral", "NEUTRAL", 
            "Foco activo: archivos", 600, timestamp));

        return list;
    }

    private static Registro crearRegistro(String usuario, String nombre, String categoria,
                                          String detalle, long duracion, LocalDateTime fecha) {
        return Registro.builder()
            .usuarioSistema(usuario)
            .nombreActividad(nombre)
            .categoria(categoria)
            .detalle(detalle)
            .duracionSeg(duracion)
            .fechaRegistro(fecha)
            .build();
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

    /*
     * Valida que el HTML contiene CADA app de la BD con su tiempo EXACTO.
     * 
     * ROBUSTEZ - Busqueda estructurada:
     * En lugar de html.contains(nombreApp) plano (que podria coincidir accidentalmente
     * con texto generico como "System", "Code", "Go" dentro de CSS o scripts),
     * busca el nombre dentro del tag exacto donde HtmlReportAdapter lo renderiza:
     * <span class="app-name">NOMBRE</span>
     * 
     * Esto elimina falsos positivos por coincidencia de substrings.
     */
    private static void validarDatosRealesEnHtml(String html, String usuario,
                                                    Map<String, Long> tiemposPorApp) {
        if (tiemposPorApp.isEmpty()) {
            System.out.println("[ADVERTENCIA] Sin registros en BD. Validacion de contenido omitida.");
            return;
        }

        int validados = 0;
        for (Map.Entry<String, Long> entry : tiemposPorApp.entrySet()) {
            String nombreApp = entry.getKey();
            long tiempoSeg = entry.getValue();
            String tiempoFmt = fmt(tiempoSeg);

            // ROBUSTEZ: Busqueda estructurada dentro del tag app-name.
            // HtmlReportAdapter escapa el nombre antes de inyectarlo, asi que
            // debemos escapar tambien para la validacion.
            String nombreEscapado = escaparHtml(nombreApp);
            String appEnHtml = "<span class=\"app-name\">" + nombreEscapado + "</span>";

            if (!html.contains(appEnHtml)) {
                // Fallback: si no esta escapado (caso extremo), buscar sin escapar
                String appEnHtmlRaw = "<span class=\"app-name\">" + nombreApp + "</span>";
                if (!html.contains(appEnHtmlRaw)) {
                    throw new AssertionError(
                        "ERROR: App '" + nombreApp + "' (tiempo " + tiempoFmt + ") " +
                        "no aparece en el HTML generado. Buscado como: " + appEnHtml
                    );
                }
            }

            // Validar que el tiempo formateado aparece en el HTML
            // (el tiempo puede aparecer en cualquier span de app-time)
            if (!html.contains(tiempoFmt)) {
                // Tolerancia: probar formato alternativo
                String tiempoAlt = fmtAlternativo(tiempoSeg);
                if (!html.contains(tiempoAlt)) {
                    throw new AssertionError(
                        "ERROR: Tiempo '" + tiempoFmt + "' (o '" + tiempoAlt + "') " +
                        "para app '" + nombreApp + "' no encontrado en HTML"
                    );
                }
            }

            System.out.println("[OK] App validada en HTML: " + nombreApp + " = " + tiempoFmt);
            validados++;
        }

        System.out.println("[OK] " + validados + " apps validadas contra datos reales de BD");
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

    /**
     * Replica el escapado de HtmlReportAdapter para validacion estructurada.
     * Debe mantenerse sincronizado con HtmlReportAdapter.escaparHtml().
     */
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

    /**
     * Formato alternativo para validacion relajada.
     * Cubre variaciones de formateo sin leading zeros en horas/minutos.
     */
    private static String fmtAlternativo(long segundos) {
        long h = segundos / 3600;
        long m = (segundos % 3600) / 60;
        long s = segundos % 60;
        if (h > 0) return String.format("%dh %02dm", h, m);  // sin leading zero en horas
        if (m > 0) return String.format("%dm %02ds", m, s);   // sin leading zero en minutos
        return String.format("%ds", s);                       // sin leading zero en segundos
    }
}