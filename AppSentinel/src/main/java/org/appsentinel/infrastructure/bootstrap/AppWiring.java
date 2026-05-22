package org.appsentinel.infrastructure.bootstrap;

import org.appsentinel.domain.service.DistractionDetector;
import org.appsentinel.domain.service.TimeTrackingService;
import org.appsentinel.infrastructure.adapter.in.ProcessWindowMonitorAdapter;
import org.appsentinel.infrastructure.adapter.in.WebSocketAdapter;
import org.appsentinel.infrastructure.adapter.out.JavaFXAlertAdapter;
import org.appsentinel.infrastructure.adapter.out.OshiRendimientoAdapter;
import org.appsentinel.infrastructure.adapter.out.PostgreSQLCategoriaAdapter;
import org.appsentinel.infrastructure.adapter.out.PostgreSQLRepositoryAdapter;
import org.appsentinel.infrastructure.adapter.out.ProcessKillerAdapter;
import org.appsentinel.infrastructure.config.AppConfig;

/**
 * AppWiring: Ensamblador del sistema (Dependency Injection manual).
 *
 * Instancia todos los adaptadores, los conecta con el dominio a través
 * de los puertos correspondientes y devuelve un AppContext inmutable.
 *
 * NOTA SOBRE FocoActivoPort:
 * TimeTrackingService implementa FocoActivoPort. En el wiring se pasa
 * la misma instancia (tracking) dos veces en AppContext:
 *   - como tracking: servicio completo (MonitorPort, BrowserEventPort)
 *   - como focoActivo: puerto de consulta (FocoActivoPort)
 * Esto es polimorfismo por interfaces: la UI consume FocoActivoPort
 * sin saber que detrás está TimeTrackingService.
 */
public class AppWiring {

    // Referencias estáticas para coordinar el apagado limpio
    private static WebSocketAdapter             webSocketInstance;
    private static ProcessWindowMonitorAdapter  monitorInstance;
    private static TimeTrackingService          trackingInstance;

    public static AppContext construir() {

        // ========== ADAPTADORES DE SALIDA ==========
        PostgreSQLCategoriaAdapter  categorias   = new PostgreSQLCategoriaAdapter();
        PostgreSQLRepositoryAdapter repo         = new PostgreSQLRepositoryAdapter();
        JavaFXAlertAdapter          notificacion = new JavaFXAlertAdapter();
        OshiRendimientoAdapter      rendimiento  = new OshiRendimientoAdapter();

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
        // tracking se pasa dos veces: como servicio y como FocoActivoPort
        return new AppContext(
            tracking,      // TimeTrackingService (MonitorPort, BrowserEventPort)
            repo,
            categorias,
            notificacion,
            killer,
            rendimiento,
            tracking       // FocoActivoPort (misma instancia, rol distinto)
        );
    }

    /**
     * Detiene todos los hilos de infraestructura en el orden correcto:
     * 1. Monitor de procesos — deja de escanear el OS.
     * 2. WebSocket — cierra el puerto 8080 y el rate-limiter.
     * 3. Tracking — persiste los chunks pendientes y para el scheduler.
     *
     * OshiRendimientoAdapter no necesita parada explícita: no tiene hilos
     * propios, solo lee el hardware bajo demanda desde el hilo de la UI.
     */
    public static void detenerTodo() {
        System.out.println("[SHUTDOWN] Iniciando apagado limpio de hilos de infraestructura...");

        if (monitorInstance != null) {
            monitorInstance.detener();
        }

        if (webSocketInstance != null) {
            webSocketInstance.detener();
        }

        if (trackingInstance != null) {
            trackingInstance.finalizar();
        }

        System.out.println("[SHUTDOWN] Sistema apagado correctamente.");
    }
}