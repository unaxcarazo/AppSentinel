package org.appsentinel.infrastructure.bootstrap;

import org.appsentinel.domain.service.DistractionDetector;
import org.appsentinel.domain.service.MantenimientoDiarioService;
import org.appsentinel.domain.service.ReporteDiarioService;
import org.appsentinel.domain.service.TimeTrackingService;
import org.appsentinel.infrastructure.adapter.in.ProcessWindowMonitorAdapter;
import org.appsentinel.infrastructure.adapter.in.WebSocketAdapter;
import org.appsentinel.infrastructure.adapter.out.HtmlReportAdapter;
import org.appsentinel.infrastructure.adapter.out.JavaFXAlertAdapter;
import org.appsentinel.infrastructure.adapter.out.OshiRendimientoAdapter;
import org.appsentinel.infrastructure.adapter.out.PostgreSQLCategoriaAdapter;
import org.appsentinel.infrastructure.adapter.out.PostgreSQLRepositoryAdapter;
import org.appsentinel.infrastructure.adapter.out.ProcessKillerAdapter;
import org.appsentinel.infrastructure.adapter.out.persistence.PostgresDatabaseCleaner;
import org.appsentinel.infrastructure.config.AppConfig;
import org.appsentinel.infrastructure.adapter.out.persistence.DatabaseConnection;

import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * AppWiring: Ensamblador del sistema (Dependency Injection manual).
 *
 * RESPONSABILIDAD UNICA: Construir el grafo de dependencias y devolver AppContext.
 * NO contiene logica de ciclo de vida de aplicacion (abrir navegadores,
 * manejar eventos de cierre, etc.). Esa responsabilidad pertenece a AppSentinel.
 *
 * FIX: detenerInfraestructura() ya NO cierra el pool de conexiones.
 * El cierre del pool se delega a cerrarConexiones() para permitir que
 * AppSentinel genere el reporte HTML (que necesita SELECT a PostgreSQL)
 * ANTES de destruir las conexiones.
 */
public class AppWiring {

    private static final Logger LOGGER = Logger.getLogger(AppWiring.class.getName());

    private static WebSocketAdapter             webSocketInstance;
    private static ProcessWindowMonitorAdapter  monitorInstance;
    private static TimeTrackingService          trackingInstance;
    private static MantenimientoDiarioService   mantenimientoInstance;
    private static ReporteDiarioService         reporteInstance;

    public static AppContext construir() {

        // ========== ADAPTADORES DE SALIDA ==========
        PostgreSQLCategoriaAdapter  categorias   = new PostgreSQLCategoriaAdapter();
        PostgreSQLRepositoryAdapter repo         = new PostgreSQLRepositoryAdapter();
        JavaFXAlertAdapter          notificacion = new JavaFXAlertAdapter();
        OshiRendimientoAdapter      rendimiento  = new OshiRendimientoAdapter();

        // ========== MANTENIMIENTO DIARIO ==========
        PostgresDatabaseCleaner cleaner = new PostgresDatabaseCleaner();
        mantenimientoInstance = new MantenimientoDiarioService(cleaner);
        mantenimientoInstance.iniciar();

        // ========== DOMINIO ==========
        DistractionDetector detector = new DistractionDetector(categorias);

        trackingInstance = new TimeTrackingService(
            detector,
            repo,
            notificacion,
            null,
            AppConfig.getSegundosAvisoPreventivo(),
            AppConfig.getSegundosBloqueoSesion(),
            AppConfig.getSegundosPausaReenfoque(),
            AppConfig.isModoEstricto(),
            AppConfig.getUsuarioSistema()
        );

        // ========== ADAPTADORES DE ENTRADA + SALIDA CRUZADOS ==========
        webSocketInstance = new WebSocketAdapter(trackingInstance);
        webSocketInstance.iniciar();

        ProcessKillerAdapter killer = new ProcessKillerAdapter(webSocketInstance);
        trackingInstance.setKiller(killer);

        // ========== REPORTE DIARIO ==========
        HtmlReportAdapter htmlAdapter = new HtmlReportAdapter(AppConfig.isModoEstricto());
        reporteInstance = new ReporteDiarioService(
            repo,
            htmlAdapter,
            AppConfig.getUsuarioSistema()
        );

        // ========== SENSOR DE ENTRADA ==========
        monitorInstance = new ProcessWindowMonitorAdapter(trackingInstance);
        monitorInstance.iniciar();

        // ========== EXPONER EN CONTEXTO ==========
        return new AppContext(
            trackingInstance,
            repo,
            categorias,
            notificacion,
            killer,
            rendimiento,
            trackingInstance,
            mantenimientoInstance,
            reporteInstance,
            detector
        );
    }

    /**
     * Detiene la infraestructura de fondo: monitores, WebSocket, tracking,
     * mantenimiento diario.
     *
     * FIX: Ya NO cierra el pool de conexiones. El pool permanece activo
     * para que AppSentinel pueda generar el reporte HTML (SELECT a PostgreSQL).
     *
     * El cierre del pool se realiza via cerrarConexiones() DESPUES del reporte.
     */
    public static void detenerTodo() {
        LOGGER.log(Level.INFO, "[SHUTDOWN] Deteniendo infraestructura...");

        safe(() -> {
            if (monitorInstance != null) monitorInstance.detener();
        }, "detener monitor");

        safe(() -> {
            if (webSocketInstance != null) webSocketInstance.detener();
        }, "detener WebSocket");

        safe(() -> {
            if (trackingInstance != null) trackingInstance.finalizar();
        }, "flush tracking a BD");

        safe(() -> {
            if (mantenimientoInstance != null) mantenimientoInstance.detener();
        }, "detener mantenimiento diario");

        LOGGER.log(Level.INFO, "[SHUTDOWN] Infraestructura detenida (Pool PostgreSQL sigue activo).");
    }

    /**
     * Cierra el pool de conexiones HikariCP.
     * DEBE invocarse DESPUES de generar el reporte HTML, ya que el reporte
     * necesita realizar SELECT a PostgreSQL.
     *
     * Este método es el PASO FINAL del shutdown.
     */
    public static void cerrarConexiones() {
        safe(() -> {
            DatabaseConnection.cerrarPool();
        }, "cerrar pool HikariCP");
        LOGGER.log(Level.INFO, "[SHUTDOWN] Pool de conexiones cerrado.");
    }

    private static void safe(Runnable accion, String descripcion) {
        try {
            accion.run();
        } catch (Exception e) {
            LOGGER.log(Level.SEVERE,
                "[SHUTDOWN] Error en paso \"{0}\" — continua",
                new Object[]{descripcion});
            LOGGER.log(Level.SEVERE, e.getMessage(), e);
        }
    }
}