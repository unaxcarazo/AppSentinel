package org.appsentinel;

import org.appsentinel.domain.model.Categoria;
import org.appsentinel.domain.model.Registro;
import org.appsentinel.infrastructure.adapter.out.HtmlReportAdapter;

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
 * TestHtmlReport: Prueba integral del generador de informes HTML.
 *
 * Verifica:
 *   1. Placeholders reemplazados correctamente (sin residuos {{...}}).
 *   2. Estructura HTML valida: las <ul> solo contienen <li> (sin texto suelto).
 *   3. CSS copiado al mismo directorio que el HTML.
 *   4. Datos mock reflejados en el HTML generado.
 *   5. SIN_CLASIFICAR excluido del reporte.
 *   6. Titulo del documento contiene correctamente el nombre de usuario.
 *
 * Instrucciones:
 *   mvn clean compile
 *   java -cp target/classes:target/dependency/* org.appsentinel.TestHtmlReport
 */
public class TestHtmlReport {

    // FIX CSS: La ruta DEBE tener un directorio padre explicito.
    // Si se usa solo "DelayLog_test.html", path.getParent() devuelve null
    // y HtmlReportAdapter no sabe donde copiar styles.css.
    // Con "reportes/DelayLog_test.html", el padre es "reportes/" y el CSS
    // se copia ahi junto al HTML, igual que en produccion.
    private static final String DIR_SALIDA  = "reportes";
    private static final String RUTA_SALIDA = DIR_SALIDA + "/DelayLog_test.html";

    public static void main(String[] args) throws Exception {
        System.out.println("=== PRUEBA HTML REPORT ===");

        // FIX CSS: Crear el directorio de salida antes de generar el reporte.
        // HtmlReportAdapter escribe el HTML pero NO crea directorios intermedios;
        // si "reportes/" no existe, Files.writeString() lanza NoSuchFileException.
        Files.createDirectories(Paths.get(DIR_SALIDA));

        // 1. Construir datos mock realistas
        List<Registro> registros = construirRegistrosMock();

        // 2. Generar reporte con modo estricto activo
        HtmlReportAdapter adapter = new HtmlReportAdapter(true);
        adapter.generarReporteHtml(registros, RUTA_SALIDA);

        // 3. Leer resultado generado
        Path path = Paths.get(RUTA_SALIDA);
        if (!Files.exists(path)) {
            throw new AssertionError("ERROR: No se genero el archivo HTML en: " + path.toAbsolutePath());
        }
        String html = Files.readString(path, StandardCharsets.UTF_8);

        // 4. Ejecutar validaciones estrictas
        validarSinPlaceholdersResiduales(html);
        validarTituloDocumento(html);
        validarEstructuraUl(html);
        validarContenidoEsperado(html);
        validarCssCopiado(path.getParent());  // path.getParent() = "reportes/", nunca null

        System.out.println("\n=== TODAS LAS VALIDACIONES PASARON ===");
        System.out.println("Archivo generado: " + path.toAbsolutePath());
        System.out.println("CSS generado:     " + path.getParent().resolve("styles.css").toAbsolutePath());
    }

    // =====================================================================
    // Datos mock
    // =====================================================================

    private static List<Registro> construirRegistrosMock() {
        List<Registro> list = new ArrayList<>();
        String user = "testuser";

        // Productivas (varias para probar mini-barras proporcionales)
        list.add(crearReg(user, "IntelliJ IDEA", Categoria.PRODUCTIVO,  "Foco activo: proyecto",      7200));
        list.add(crearReg(user, "Terminal",       Categoria.PRODUCTIVO,  "Foco activo: compilacion",   1800));
        list.add(crearReg(user, "Chrome",         Categoria.PRODUCTIVO,  "Foco activo: documentacion",  900));

        // Distracciones
        list.add(crearReg(user, "Discord", Categoria.DISTRACCION, "Foco activo: chat",  3600));
        list.add(crearReg(user, "YouTube", Categoria.DISTRACCION, "Foco activo: video", 1200));

        // Neutral
        list.add(crearReg(user, "Explorador", Categoria.NEUTRAL, "Foco activo: archivos", 600));

        // SIN_CLASIFICAR -> debe quedar EXCLUIDO del reporte HTML
        list.add(crearReg(user, "AppDesconocida", Categoria.SIN_CLASIFICAR, "Foco activo: ???", 300));

        // Background (prefijo interno BACKGROUND_)
        // Categoria.BACKGROUND ya termina en '_', se concatena con la categoria base
        list.add(crearReg(user, "Spotify",        Categoria.BACKGROUND + Categoria.NEUTRAL,    "Segundo plano", 2400));
        list.add(crearReg(user, "Docker Desktop", Categoria.BACKGROUND + Categoria.PRODUCTIVO, "Segundo plano", 3600));

        return list;
    }

    private static Registro crearReg(String user, String nombre, String categoria,
                                     String detalle, long segs) {
        return Registro.builder()
            .usuarioSistema(user)
            .nombreActividad(nombre)
            .categoria(categoria)
            .detalle(detalle)
            .duracionSeg(segs)
            .fechaRegistro(LocalDateTime.now())
            .build();
    }

    // =====================================================================
    // Validacion 1: Sin placeholders residuales
    // =====================================================================

    private static void validarSinPlaceholdersResiduales(String html) {
        Pattern pattern = Pattern.compile("\\{\\{[A-Z_]+\\}\\}");
        Matcher matcher = pattern.matcher(html);
        if (matcher.find()) {
            throw new AssertionError(
                "ERROR: Placeholder residual encontrado: " + matcher.group() +
                " en posicion " + matcher.start()
            );
        }
        System.out.println("[OK] No hay placeholders residuales.");
    }

    // =====================================================================
    // Validacion 2: Titulo del documento contiene el usuario
    // =====================================================================

    private static void validarTituloDocumento(String html) {
        Pattern titlePattern = Pattern.compile("<title>(.*?)</title>", Pattern.DOTALL);
        Matcher titleMatcher = titlePattern.matcher(html);

        if (!titleMatcher.find()) {
            throw new AssertionError("ERROR: No se encontro la etiqueta <title> en el HTML");
        }

        String titulo = titleMatcher.group(1).trim();
        System.out.println("[INFO] Titulo detectado: [" + titulo + "]");

        if (!titulo.contains("testuser")) {
            throw new AssertionError(
                "ERROR: El <title> no contiene el nombre de usuario. Titulo actual: [" + titulo + "]"
            );
        }

        String tituloLower = titulo.toLowerCase();
        if (!tituloLower.contains("informe") || !tituloLower.contains("rendimiento")) {
            throw new AssertionError(
                "ERROR: El <title> no contiene la frase esperada 'Informe de Rendimiento'. " +
                "Titulo actual: [" + titulo + "]"
            );
        }

        if (titulo.contains("{{") || titulo.contains("}}")) {
            throw new AssertionError(
                "ERROR: El <title> contiene placeholders sin reemplazar: [" + titulo + "]"
            );
        }

        System.out.println("[OK] Titulo del documento correcto: " + titulo);
    }

    // =====================================================================
    // Validacion 3: Estructura <ul> valida (sin texto suelto)
    // =====================================================================

    private static void validarEstructuraUl(String html) {
        Pattern ulPattern = Pattern.compile(
            "<ul\\s+class=\"app-list\">(.*?)</ul>",
            Pattern.DOTALL
        );
        Matcher ulMatcher = ulPattern.matcher(html);

        int count = 0;
        while (ulMatcher.find()) {
            count++;
            String contenidoUl = ulMatcher.group(1);

            String sinComentarios = contenidoUl.replaceAll("<!--.*?-->", "");
            String sinLi          = sinComentarios.replaceAll("(?s)<li.*?</li>", "");
            String resto          = sinLi.replaceAll("\\s", "");

            if (!resto.isEmpty()) {
                throw new AssertionError(
                    "ERROR: <ul class=\"app-list\"> contiene texto suelto o tags invalidos: [" +
                    resto.substring(0, Math.min(resto.length(), 120)) + "]"
                );
            }
        }

        if (count == 0) {
            throw new AssertionError("ERROR: No se encontraron <ul class=\"app-list\"> en el HTML");
        }
        System.out.println("[OK] Estructura <ul> valida en " + count + " listas (sin texto suelto).");
    }

    // =====================================================================
    // Validacion 4: Contenido esperado presente
    // =====================================================================

    private static void validarContenidoEsperado(String html) {
        // Metadatos
        assertContiene(html, "testuser",      "Usuario en body");
        assertContiene(html, "Modo Estricto", "Estado");
        assertContiene(html, "TIEMPO TOTAL",  "Header tiempo total");

        // Leyendas / tarjetas de categoria
        assertContiene(html, "Productivo",    "Leyenda productivo");
        assertContiene(html, "Distracciones", "Leyenda distracciones");
        assertContiene(html, "Neutral",       "Leyenda neutral");
        assertContiene(html, "Segundo Plano", "Leyenda background");

        // Apps especificas que deben aparecer
        assertContiene(html, "IntelliJ IDEA",  "App productiva IntelliJ");
        assertContiene(html, "Terminal",       "App productiva Terminal");
        assertContiene(html, "Discord",        "App distraccion Discord");
        assertContiene(html, "YouTube",        "App distraccion YouTube");
        assertContiene(html, "Explorador",     "App neutral Explorador");
        assertContiene(html, "Spotify",        "App background Spotify");
        assertContiene(html, "Docker Desktop", "App background Docker");

        // SIN_CLASIFICAR debe estar AUSENTE
        if (html.contains("AppDesconocida")) {
            throw new AssertionError(
                "ERROR: AppDesconocida (SIN_CLASIFICAR) no debe aparecer en el reporte HTML"
            );
        }

        // Mini-barras renderizadas
        assertContiene(html, "mini-fill",         "Mini barras de progreso");
        assertContiene(html, "fill-productive",   "Clase CSS productivo");
        assertContiene(html, "fill-distractions", "Clase CSS distracciones");
        assertContiene(html, "fill-neutral",      "Clase CSS neutral");
        assertContiene(html, "fill-background",   "Clase CSS background");

        // Tiempos formateados
        assertContiene(html, "h", "Formato horas");
        assertContiene(html, "m", "Formato minutos");

        System.out.println("[OK] Contenido esperado presente; SIN_CLASIFICAR correctamente ausente.");
    }

    private static void assertContiene(String html, String texto, String descripcion) {
        if (!html.contains(texto)) {
            throw new AssertionError(
                "ERROR: No se encontro '" + descripcion + "' (texto esperado: " + texto + ") en el HTML"
            );
        }
    }

    // =====================================================================
    // Validacion 5: CSS copiado al directorio de salida
    // =====================================================================

    private static void validarCssCopiado(Path directorio) throws Exception {
        // directorio nunca sera null porque RUTA_SALIDA tiene padre explicito
        Path css = directorio.resolve("styles.css");
        if (!Files.exists(css)) {
            throw new AssertionError(
                "ERROR: styles.css no fue copiado al directorio de salida: " + css.toAbsolutePath()
            );
        }
        long size = Files.size(css);
        if (size == 0) {
            throw new AssertionError("ERROR: styles.css existe pero esta vacio");
        }
        System.out.println("[OK] styles.css copiado correctamente (" + size + " bytes).");
    }
}