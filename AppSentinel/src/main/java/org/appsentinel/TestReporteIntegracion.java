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
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * TestReporteIntegracion: Prueba de integración del flujo completo.
 *
 * Precondición: PostgreSQL corriendo con seed SQL ejecutado.
 *
 * FLUJO validado (sin hardcodes):
 *   1. Lee tiempos actuales de la BD para apps de prueba.
 *   2. Inserta actividad simulada vía UPSERT (acumula sobre existente).
 *   3. Re-lee tiempos actualizados de la BD.
 *   4. Genera informe HTML con datos reales.
 *   5. Valida que el HTML contiene las apps y los tiempos EXACTOS de la BD.
 */
public class TestReporteIntegracion {

    private static final String RUTA_SALIDA = "DelayLog_integracion.html";

    public static void main(String[] args) throws Exception {
        System.out.println("=== PRUEBA DE INTEGRACIÓN: Reporte Diario ===");
        System.out.println("Precondición: PostgreSQL corriendo con seed SQL ejecutado.");

        // 1. Adaptadores reales
        PostgreSQLRepositoryAdapter repo = new PostgreSQLRepositoryAdapter();
        HtmlReportAdapter htmlAdapter = new HtmlReportAdapter(false);
        String usuario = System.getProperty("user.name");

        ReporteDiarioService reporteService = new ReporteDiarioService(
            repo, htmlAdapter, usuario
        );

        // 2. Leer tiempos ANTES del UPSERT (para calcular delta)
        System.out.println("\n>>> Leyendo tiempos actuales en BD <<<");
        long tiempoIntelliJAntes = obtenerDuracionApp(repo, usuario, "IntelliJ IDEA");
        long tiempoDiscordAntes = obtenerDuracionApp(repo, usuario, "Discord");
        System.out.println("IntelliJ IDEA antes: " + fmt(tiempoIntelliJAntes));
        System.out.println("Discord antes: " + fmt(tiempoDiscordAntes));

        // 3. Insertar actividad simulada vía UPSERT
        System.out.println("\n>>> Insertando actividad simulada vía UPSERT <<<");
        insertarActividadSimulada(repo, usuario);

        // 4. Leer tiempos DESPUÉS del UPSERT (valores reales esperados)
        System.out.println("\n>>> Leyendo tiempos actualizados en BD <<<");
        long tiempoIntelliJDespues = obtenerDuracionApp(repo, usuario, "IntelliJ IDEA");
        long tiempoDiscordDespues = obtenerDuracionApp(repo, usuario, "Discord");
        System.out.println("IntelliJ IDEA después: " + fmt(tiempoIntelliJDespues));
        System.out.println("Discord después: " + fmt(tiempoDiscordDespues));

        // 5. Generar informe con datos reales
        System.out.println("\n>>> Generando informe HTML <<<");
        reporteService.generarInformeHoy(RUTA_SALIDA);

        // 6. Validar HTML generado contra datos reales de la BD
        System.out.println("\n>>> Validando HTML generado <<<");
        Path rutaArchivo = Paths.get(RUTA_SALIDA);
        String html = Files.readString(rutaArchivo, StandardCharsets.UTF_8);

        mostrarTituloReal(html);
        validarDatosRealesEnHtml(html, usuario, tiempoIntelliJDespues, tiempoDiscordDespues);

        // Cleanup seguro
        Files.deleteIfExists(rutaArchivo);
        System.out.println("\n=== TODAS LAS VALIDACIONES PASARON ===");
    }

    // =====================================================================
    // Helpers de BD
    // =====================================================================

    private static long obtenerDuracionApp(PostgreSQLRepositoryAdapter repo,
                                           String usuario, String nombreApp) {
        List<Registro> todos = repo.obtenerTodosHoy(usuario);
        if (todos == null) {
            return 0;
        }
        return todos.stream()
            .filter(r -> nombreApp.equals(r.getNombreActividad()))
            .mapToLong(Registro::getDuracionSeg)
            .sum();
    }

    private static void insertarActividadSimulada(PostgreSQLRepositoryAdapter repo,
                                                  String usuario) {
        LocalDateTime ahora = LocalDateTime.now();

        Registro r1 = Registro.builder()
            .usuarioSistema(usuario)
            .nombreActividad("IntelliJ IDEA")
            .categoria("PRODUCTIVO")
            .detalle("Foco activo: desarrollo")
            .duracionSeg(7200)
            .fechaRegistro(ahora)
            .build();

        Registro r2 = Registro.builder()
            .usuarioSistema(usuario)
            .nombreActividad("Discord")
            .categoria("DISTRACCION")
            .detalle("Foco activo: chat")
            .duracionSeg(1800)
            .fechaRegistro(ahora)
            .build();

        repo.guardarOActualizar(r1);
        repo.guardarOActualizar(r2);

        System.out.println("UPSERT: IntelliJ IDEA +7200s");
        System.out.println("UPSERT: Discord +1800s");
    }

    // =====================================================================
    // Validación del HTML contra datos reales de la BD
    // =====================================================================

    private static void mostrarTituloReal(String html) {
        Pattern p = Pattern.compile("<title>(.*?)</title>", Pattern.DOTALL);
        Matcher m = p.matcher(html);
        if (m.find()) {
            System.out.println("[DEBUG] Título real: [" + m.group(1) + "]");
        }
        Pattern h1 = Pattern.compile("<h1>(.*?)</h1>", Pattern.DOTALL);
        Matcher h1m = h1.matcher(html);
        if (h1m.find()) {
            System.out.println("[DEBUG] H1 real: [" + h1m.group(1) + "]");
        }
    }

    private static void validarDatosRealesEnHtml(String html, String usuario,
                                                  long tiempoIntelliJ, long tiempoDiscord) {
        // Validar usuario presente
        if (!html.contains(usuario)) {
            throw new AssertionError("ERROR: Usuario '" + usuario + "' no aparece en HTML");
        }
        System.out.println("[OK] Usuario '" + usuario + "' encontrado");

        // Validar apps presentes
        assertContiene(html, "IntelliJ IDEA", "App productiva");
        assertContiene(html, "Discord", "App distracción");

        // Validar tiempos EXACTOS de la BD (no hardcodes)
        String fmtIntelliJ = fmt(tiempoIntelliJ);
        String fmtDiscord = fmt(tiempoDiscord);

        assertContiene(html, fmtIntelliJ, "Tiempo IntelliJ (" + fmtIntelliJ + ")");
        assertContiene(html, fmtDiscord, "Tiempo Discord (" + fmtDiscord + ")");

        System.out.println("[OK] Tiempos reales de BD reflejados: IntelliJ=" + fmtIntelliJ
            + ", Discord=" + fmtDiscord);

        // Validar categorías
        assertContiene(html, "Productivo", "Categoría productiva");
        assertContiene(html, "Distracciones", "Categoría distracción");

        // Validar sin placeholders residuales (Escape corregido para cadenas en Java)
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

    private static void assertContiene(String html, String texto, String desc) {
        if (!html.contains(texto)) {
            throw new AssertionError("ERROR: " + desc + " no encontrado. Esperado: " + texto);
        }
    }

    // =====================================================================
    // Formateador de tiempo (igual que HtmlReportAdapter)
    // =====================================================================

    private static String fmt(long segundos) {
        long h = segundos / 3600;
        long m = (segundos % 3600) / 60;
        long s = segundos % 60;
        if (h > 0) return String.format("%02dh %02dm", h, m);
        return String.format("%02dm %02ds", m, s);
    }
}
