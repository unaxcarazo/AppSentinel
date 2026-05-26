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

/**
 * AppWiring: Ensamblador del sistema (Dependency Injection manual).
 *
 * Instancia todos los adaptadores, los conecta con el dominio a través
 * de los puertos correspondientes y devuelve un AppContext inmutable.
 *
 * FIX: ReporteDiarioService genera DelayLog.html al cerrar la aplicación
 * con datos reales del usuario (cualquier usuario, no hardcodeado).
 *
 * FIX: detenerTodo() genera el informe ANTES de cerrar el pool de conexiones.
 * El orden de shutdown garantiza que los datos del día se persistan en HTML
 * antes de que MantenimientoDiarioService limpie la BD a medianoche.
 */
public class AppWiring {

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
        MantenimientoDiarioService mantenimiento = new MantenimientoDiarioService(cleaner);
        mantenimiento.iniciar();
        mantenimientoInstance = mantenimiento;

        // ========== DOMINIO ==========
        DistractionDetector detector = new DistractionDetector(categorias);

        TimeTrackingService tracking = new TimeTrackingService(
            detector,
            repo,
            notificacion,
            null,
            AppConfig.getSegundosAvisoPreventivo(),
            AppConfig.getSegundosBloqueoSesion(),
            AppConfig.getSegundosPausaReenfoque(),
            AppConfig.isModoEstricto(),
            AppConfig.getUsuarioSistema()  // ← Cualquier usuario del sistema
        );
        trackingInstance = tracking;

        // ========== ADAPTADORES DE ENTRADA + SALIDA CRUZADOS ==========
        WebSocketAdapter webSocket = new WebSocketAdapter(tracking);
        webSocket.iniciar();
        webSocketInstance = webSocket;

        ProcessKillerAdapter killer = new ProcessKillerAdapter(webSocket);
        tracking.setKiller(killer);

        // ========== REPORTE DIARIO ==========
        HtmlReportAdapter htmlAdapter = new HtmlReportAdapter(AppConfig.isModoEstricto());
        ReporteDiarioService reporte = new ReporteDiarioService(
            repo,
            htmlAdapter,
            AppConfig.getUsuarioSistema()  // ← Dinámico: funciona con cualquier usuario
        );
        reporteInstance = reporte;

        // ========== SENSOR DE ENTRADA ==========
        ProcessWindowMonitorAdapter monitor = new ProcessWindowMonitorAdapter(tracking);
        monitor.iniciar();
        monitorInstance = monitor;

        return new AppContext(
            tracking, repo, categorias, notificacion, killer,
            rendimiento, tracking, mantenimiento
        );
    }

    /**
     * Orden de apagado crítico para garantizar integridad de datos:
     * 1. Parar monitor y WebSocket (dejar de recibir eventos).
     * 2. Flush final de tracking a PostgreSQL (UPSERT acumulado).
     * 3. Generar informe HTML con datos reales del día.
     * 4. Parar mantenimiento diario (evita que limpie durante el reporte).
     * 5. Cerrar pool HikariCP.
     */
    public static void detenerTodo() {
        System.out.println("[SHUTDOWN] Iniciando apagado limpio...");

        if (monitorInstance != null) {
            monitorInstance.detener();
        }

        if (webSocketInstance != null) {
            webSocketInstance.detener();
        }

        // FIX: Flush final a PostgreSQL ANTES de generar informe
        if (trackingInstance != null) {
            trackingInstance.finalizar();
        }

        // FIX: Generar informe HTML con datos reales del usuario actual
        if (reporteInstance != null) {
            System.out.println("[SHUTDOWN] Generando informe diario...");
            reporteInstance.generarInformeHoyDefault();
        }

        // FIX: Parar mantenimiento DESPUÉS del reporte para evitar condición de carrera
        if (mantenimientoInstance != null) {
            mantenimientoInstance.detener();
        }

        DatabaseConnection.cerrarPool();

        System.out.println("[SHUTDOWN] Sistema apagado correctamente.");
    }
}