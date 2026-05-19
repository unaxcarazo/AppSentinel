package org.appsentinel;

import javafx.application.Application;
import javafx.fxml.FXMLLoader;
import javafx.scene.Scene;
import javafx.stage.Stage;
import org.appsentinel.infrastructure.adapter.out.persistence.DatabaseConnection;
import org.appsentinel.infrastructure.config.AppContext;
import org.appsentinel.infrastructure.config.AppWiring;
import org.appsentinel.infrastructure.gui.controller.MainController;

/**
 * AppSentinel: Clase de aplicación JavaFX.
 *
 * Extiende Application y gestiona el ciclo de vida gráfico.
 * NO contiene main() — el arranque lo delega Launcher.java.
 *
 * Soporta dos modos:
 * - GUI (por defecto): JavaFX + MainController + escáner + WebSocket.
 * - HEADLESS: solo dominio, sin UI, para pruebas automatizadas.
 *   Activar con: java -Dappsentinel.headless=true -jar AppSentinel.jar
 */
public class AppSentinel extends Application {

    // Lee la propiedad de sistema, no los args[].
    // Se activa con el flag -Dappsentinel.headless=true al lanzar la JVM.
    private static final boolean MODO_HEADLESS = Boolean.getBoolean("appsentinel.headless");

    // Ruta al FXML principal dentro del classpath.
    // Coincide con src/main/resources/org/appsentinel/infrastructure/adapter/in/gui/views/Main.fxml
    private static final String FXML_MAIN = 
        "/org/appsentinel/infrastructure/adapter/in/gui/views/Main.fxml";

    @Override
    public void start(Stage stage) throws Exception {
        AppContext ctx = AppWiring.construir();

        if (MODO_HEADLESS) {
            System.out.println("[HEADLESS] Modo prueba activo. Sin UI.");
            ejecutarHeadless();
            return;
        }

        iniciarGUI(stage, ctx);
    }

    // -------------------------------------------------------------------------
    // Modo headless
    // -------------------------------------------------------------------------

    /**
     * Ejecuta el sistema sin interfaz gráfica durante 30 segundos.
     *
     * El bloque try-finally garantiza que shutdown() se ejecute siempre,
     * incluso si Thread.sleep() es interrumpido externamente. En la versión
     * anterior no existía este bloque: una interrupción dejaba todos los
     * hilos y el pool de conexiones abiertos indefinidamente.
     */
    private void ejecutarHeadless() {
        try {
            Thread.sleep(30000);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            System.out.println("[HEADLESS] Interrumpido antes de completar.");
        } finally {
            shutdown();
            System.exit(0);
        }
    }

    // -------------------------------------------------------------------------
    // Modo GUI
    // -------------------------------------------------------------------------

    /**
     * Inicia la interfaz gráfica JavaFX.
     *
     * Validación del FXML: getResource() devuelve null si el archivo no
     * existe en el classpath. Sin esta comprobación, loader.load() lanza
     * un NullPointerException genérico sin indicar la causa real.
     *
     * Ruta corregida: Main.fxml está en
     * src/main/resources/org/appsentinel/infrastructure/adapter/in/gui/views/
     * y no en la raíz de org/appsentinel/ como indicaba la versión anterior.
     *
     * Dimensiones mínimas: sin setMinWidth/setMinHeight el usuario puede
     * colapsar la ventana a 0x0 px, rompiendo todos los layouts de JavaFX.
     *
     * setOnCloseRequest delega en shutdown(), que invoca
     * AppWiring.detenerTodo() para parar monitor + WebSocket + tracking
     * antes de cerrar el pool HikariCP.
     */
    private void iniciarGUI(Stage stage, AppContext ctx) throws Exception {
        java.net.URL fxmlUrl = getClass().getResource(FXML_MAIN);
        if (fxmlUrl == null) {
            throw new IllegalStateException(
                "[ERROR] No se encontró Main.fxml en: " + FXML_MAIN + "\n" +
                "Verificar que el archivo esté en " +
                "src/main/resources/org/appsentinel/infrastructure/adapter/in/gui/views/");
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

        stage.setOnCloseRequest(e -> {
            System.out.println("[GUI] Ventana cerrada. Iniciando apagado...");
            shutdown();
        });

        stage.show();
    }

    // -------------------------------------------------------------------------
    // Apagado centralizado
    // -------------------------------------------------------------------------

    /**
     * Para todos los hilos de infraestructura y libera recursos.
     *
     * El orden importa:
     * 1. AppWiring.detenerTodo(): para monitor, WebSocket y tracking
     *    en el orden correcto que ya gestiona AppWiring internamente.
     * 2. DatabaseConnection.cerrarPool(): cierra el pool HikariCP
     *    después de que tracking haya persistido los últimos chunks.
     *
     * Cada bloque try-catch está aislado para que un fallo en el paso 1
     * no impida la ejecución del paso 2.
     */
    private void shutdown() {
        try {
            AppWiring.detenerTodo();
        } catch (Exception e) {
            System.err.println("[ERROR] Fallo al detener hilos de infraestructura: " + e.getMessage());
        }

        try {
            DatabaseConnection.cerrarPool();
        } catch (Exception e) {
            System.err.println("[ERROR] Fallo al cerrar pool de conexiones: " + e.getMessage());
        }

        System.out.println("[SHUTDOWN] AppSentinel apagado correctamente.");
    }
}