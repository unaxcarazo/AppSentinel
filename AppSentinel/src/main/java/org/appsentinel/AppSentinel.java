package org.appsentinel;

import javafx.application.Application;
import javafx.application.Platform;
import javafx.fxml.FXMLLoader;
import javafx.scene.Scene;
import javafx.stage.Stage;
import org.appsentinel.infrastructure.bootstrap.AppContext;
import org.appsentinel.infrastructure.bootstrap.AppWiring;
import org.appsentinel.infrastructure.adapter.in.gui.controller.MainController;

import java.awt.Desktop;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDate;
import java.util.concurrent.CompletableFuture;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * AppSentinel: Clase de aplicacion JavaFX.
 *
 * RESPONSABILIDAD DE CICLO DE VIDA:
 * - Arranque: construye AppContext via AppWiring, carga GUI o headless.
 * - Cierre: orquesta shutdown ORDENADO con garantia de reporte HTML.
 *
 * ORDEN DE SHUTDOWN (corrige race condition pool vs reporte):
 *   1. AppWiring.detenerInfraestructura() — para monitores, WebSocket, tracking flush.
 *   2. generarYAbrirReporte() — SELECT a PostgreSQL (pool aun activo) + abrir navegador.
 *   3. AppWiring.cerrarConexiones() — destruir pool HikariCP (paso final).
 *
 * FIXES:
 *   1. Sin instanciacion de infraestructura: usa ctx.reporteService() (Hexagonal).
 *   2. Sin setOnCloseRequest duplicado: unico punto de cierre es stop().
 *   3. Headless NO bloquea Application Thread: CompletableFuture.runAsync().
 *   4. Thread.sleep(500) tras abrir navegador: da tiempo al OS de inicializarlo.
 */
public class AppSentinel extends Application {

    private static final Logger LOGGER = Logger.getLogger(AppSentinel.class.getName());

    private static final boolean MODO_HEADLESS = Boolean.getBoolean("appsentinel.headless");
    private static final String FXML_MAIN =
        "/org/appsentinel/infrastructure/adapter/in/gui/views/Main.fxml";

    private static final Path DIR_REPORTES = Paths.get(
        System.getProperty("user.home"), "AppSentinel", "reports"
    );

    private AppContext ctx;
    private volatile boolean yaSeEjecutoShutdown = false;

    @Override
    public void start(Stage stage) throws Exception {
        ctx = AppWiring.construir();

        if (MODO_HEADLESS) {
            System.out.println("[HEADLESS] Modo activo. Ejecutando en hilo secundario...");
            ejecutarHeadless();
            return;
        }

        iniciarGUI(stage, ctx);
    }

    // -------------------------------------------------------------------------
    // Modo headless — NO bloquea Application Thread
    // -------------------------------------------------------------------------

    private void ejecutarHeadless() {
        CompletableFuture.runAsync(() -> {
            try {
                Thread.sleep(30000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                System.out.println("[HEADLESS] Interrumpido antes de completar.");
            }
        }).thenRun(() -> Platform.runLater(() -> {
            // Volvemos al JavaFX Application Thread para invocar stop() correctamente
            Platform.exit();
        }));
        // NOTA: No llamamos shutdownConReporte() aqui. Platform.exit() invocara stop().
    }

    // -------------------------------------------------------------------------
    // Modo GUI
    // -------------------------------------------------------------------------

    private void iniciarGUI(Stage stage, AppContext ctx) throws Exception {
        java.net.URL fxmlUrl = getClass().getResource(FXML_MAIN);
        if (fxmlUrl == null) {
            throw new IllegalStateException("[ERROR] No se encontro Main.fxml en: " + FXML_MAIN);
        }

        FXMLLoader loader = new FXMLLoader(fxmlUrl);
        stage.setScene(new Scene(loader.load(), 900, 600));
        stage.setTitle("AppSentinel");
        stage.setMinWidth(800);
        stage.setMinHeight(500);

        MainController controller = loader.getController();
        if (controller != null) {
            controller.init(ctx);
        } else {
            System.err.println("[ADVERTENCIA] MainController no pudo cargarse desde FXML.");
        }

        // SIN setOnCloseRequest. El unico punto de cierre es stop().
        // JavaFX invoca stop() automaticamente al cerrar la ventana.
        stage.show();
    }

    // -------------------------------------------------------------------------
    // Shutdown ORDENADO — corrige race condition pool vs reporte
    // -------------------------------------------------------------------------

    /**
     * UNICO punto de entrada de apagado garantizado por JavaFX.
     * Captura: cerrar ventana, Platform.exit(), Ctrl+C, kill -TERM.
     *
     * ORDEN CRITICO:
     *   1. detenerInfraestructura() — monitores + flush tracking a BD.
     *   2. generarYAbrirReporte() — SELECT a PostgreSQL (pool activo) + navegador.
     *   3. cerrarConexiones() — destruir pool (PASO FINAL).
     *
     * Flag yaSeEjecutoShutdown evita doble ejecucion si stop() se invoca
     * multiples veces (raro, pero posible en ciertos frameworks de testing).
     */
    @Override
    public void stop() {
        LOGGER.log(Level.INFO, "[SHUTDOWN] Hook stop() de JavaFX invocado.");
        shutdownConReporte();
    }

    private synchronized void shutdownConReporte() {
        if (yaSeEjecutoShutdown) {
            LOGGER.log(Level.WARNING, "[SHUTDOWN] Ignorando invocacion duplicada.");
            return;
        }
        yaSeEjecutoShutdown = true;

        LOGGER.log(Level.INFO, "[SHUTDOWN] Iniciando apagado ordenado...");

        // 1. Detener todo (monitores, WebSocket, flush tracking a BD)
        // POOL SIGUE ACTIVO — necesario para SELECT del reporte
        AppWiring.detenerTodo();

        // 2. GENERAR Y ABRIR INFORME — condicion SI O SI
        // PostgreSQL responde SELECT porque el pool aun no se cerro
        generarYAbrirReporte();

        // 3. PASO FINAL: Cerrar pool de conexiones
        // Ya no se necesita BD. El reporte esta generado y el navegador abierto.
        AppWiring.cerrarConexiones();

        LOGGER.log(Level.INFO, "[SHUTDOWN] Aplicacion cerrada correctamente.");
    }

    /**
     * Genera el informe HTML en ruta fija y lo abre en navegador.
     * INAMOVIBLE: nunca lanza excepcion hacia arriba.
     *
     * FIX: Thread.sleep(500) tras abrir navegador da tiempo al sistema
     * operativo de inicializar el proceso del navegador antes de que
     * la JVM termine (especialmente en Windows donde el spawn de proceso
     * puede tardar >100ms).
     *
     * Usa ctx.reporteService() — cero instanciacion de infraestructura.
     */
    private void generarYAbrirReporte() {
        try {
            Files.createDirectories(DIR_REPORTES);

            String nombreArchivo = "DelayLog_" + LocalDate.now() + ".html";
            Path rutaReporte = DIR_REPORTES.resolve(nombreArchivo);

            if (ctx != null && ctx.reporteService() != null) {
                ctx.reporteService().generarInformeHoy(rutaReporte.toString());
                LOGGER.log(Level.INFO, "[SHUTDOWN] Informe generado: {0}", rutaReporte);
            } else {
                LOGGER.log(Level.SEVERE,
                    "[SHUTDOWN] ReporteDiarioService no disponible en AppContext. " +
                    "No se genero informe.");
                return;
            }

            // ABRIR EN NAVEGADOR
            if (Desktop.isDesktopSupported()
                    && Desktop.getDesktop().isSupported(Desktop.Action.BROWSE)) {
                Desktop.getDesktop().browse(rutaReporte.toUri());
                LOGGER.log(Level.INFO, "[SHUTDOWN] Informe abierto en navegador.");

                // FIX: Pausa de 500ms para dar tiempo al OS de inicializar el navegador
                // antes de que la JVM termine. En Windows el spawn de proceso puede
                // ser asincrono y cancelarse si el proceso padre (JVM) muere rapido.
                Thread.sleep(500);

            } else {
                LOGGER.log(Level.WARNING,
                    "[SHUTDOWN] Desktop no soportado. Abrir manualmente: {0}", rutaReporte);
            }

        } catch (Exception e) {
            LOGGER.log(Level.SEVERE,
                "[SHUTDOWN] FALLO CRITICO generando o abriendo informe. " +
                "Datos del dia estan en BD pero no en HTML.", e);
        }
    }
}