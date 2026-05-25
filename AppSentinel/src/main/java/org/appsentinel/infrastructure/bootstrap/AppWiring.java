package org.appsentinel.infrastructure.bootstrap;

import org.appsentinel.domain.service.DistractionDetector;
import org.appsentinel.domain.service.MantenimientoDiarioService;
import org.appsentinel.domain.service.TimeTrackingService;
import org.appsentinel.infrastructure.adapter.in.ProcessWindowMonitorAdapter;
import org.appsentinel.infrastructure.adapter.in.WebSocketAdapter;
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
 * FIX: Añade MantenimientoDiarioService para limpieza automática diaria
 * de la BD, funcionando en ambos modos (GUI y headless).
 *
 * FIX: detenerTodo() cierra el pool HikariCP para evitar fugas de
 * conexiones en el shutdown de JavaFX.
 */
public class AppWiring {

    // Referencias estáticas para coordinar el apagado limpio
    private static WebSocketAdapter             webSocketInstance;
    private static ProcessWindowMonitorAdapter  monitorInstance;
    private static TimeTrackingService          trackingInstance;
    private static MantenimientoDiarioService   mantenimientoInstance;

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
            null,  // killer se inyecta después de construir WebSocketAdapter
            AppConfig.getSegundosAvisoPreventivo(),
            AppConfig.getSegundosBloqueoSesion(),
            AppConfig.getSegundosPausaReenfoque(),
            AppConfig.isModoEstricto(),
            AppConfig.getUsuarioSistema()
        );
        trackingInstance = tracking;

        // ========== ADAPTADORES DE ENTRADA + SALIDA CRUZADOS ==========
        WebSocketAdapter webSocket = new WebSocketAdapter(tracking);
        webSocket.iniciar();
        webSocketInstance = webSocket;

        ProcessKillerAdapter killer = new ProcessKillerAdapter(webSocket);
        tracking.setKiller(killer);

        // ========== SENSOR DE ENTRADA ==========
        ProcessWindowMonitorAdapter monitor = new ProcessWindowMonitorAdapter(tracking);
        monitor.iniciar();
        monitorInstance = monitor;

        // ========== CONTEXTO: SOLO PUERTOS, NO ADAPTADORES CONCRETOS ==========
        return new AppContext(
            tracking,      // TimeTrackingService (MonitorPort, BrowserEventPort)
            repo,          // RegistroRepositoryPort
            categorias,    // CategoriaRepositoryPort
            notificacion,  // NotificacionPort
            killer,        // KillerPort
            rendimiento,   // RendimientoSistemaPort
            tracking,      // FocoActivoPort (misma instancia, rol distinto)
            mantenimiento  // MantenimientoDiarioService (nuevo)
        );
    }

    /**
     * Detiene todos los hilos de infraestructura en el orden correcto:
     * 1. Mantenimiento diario — para la limpieza programada.
     * 2. Monitor de procesos — deja de escanear el OS.
     * 3. WebSocket — cierra el puerto 8080.
     * 4. Tracking — persiste los chunks pendientes y para el scheduler.
     * 5. Pool de conexiones — libera conexiones HikariCP.
     */
    public static void detenerTodo() {
        System.out.println("[SHUTDOWN] Iniciando apagado limpio...");

        if (mantenimientoInstance != null) {
            mantenimientoInstance.detener();
        }

        if (monitorInstance != null) {
            monitorInstance.detener();
        }

        if (webSocketInstance != null) {
            webSocketInstance.detener();
        }

        if (trackingInstance != null) {
            trackingInstance.finalizar();
        }

        // FIX: Cerrar pool HikariCP para evitar fugas de conexiones
        DatabaseConnection.cerrarPool();

        System.out.println("[SHUTDOWN] Sistema apagado correctamente.");
    }
}