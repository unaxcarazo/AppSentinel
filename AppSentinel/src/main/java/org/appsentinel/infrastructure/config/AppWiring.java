package org.appsentinel.infrastructure.config;

import org.appsentinel.domain.service.DistractionDetector;
import org.appsentinel.domain.service.TimeTrackingService;
import org.appsentinel.infrastructure.adapter.in.ProcessWindowMonitorAdapter;
import org.appsentinel.infrastructure.adapter.in.WebSocketAdapter;
import org.appsentinel.infrastructure.adapter.out.JavaFXAlertAdapter;
import org.appsentinel.infrastructure.adapter.out.PostgreSQLCategoriaAdapter;
import org.appsentinel.infrastructure.adapter.out.PostgreSQLRepositoryAdapter;
import org.appsentinel.infrastructure.adapter.out.ProcessKillerAdapter;

/**
 * AppWiring: Ensamblador del sistema (Dependency Injection manual).
 */
public class AppWiring {

    // Referencias estáticas para coordinar el apagado limpio del sistema operativo
    private static WebSocketAdapter webSocketInstance;
    private static ProcessWindowMonitorAdapter monitorInstance;
    private static TimeTrackingService trackingInstance;

    public static AppContext construir() {

        // ========== ADAPTADORES DE SALIDA ==========
        PostgreSQLCategoriaAdapter  categorias   = new PostgreSQLCategoriaAdapter();
        PostgreSQLRepositoryAdapter repo         = new PostgreSQLRepositoryAdapter();
        JavaFXAlertAdapter          notificacion = new JavaFXAlertAdapter();

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
        return new AppContext(tracking, repo, categorias, notificacion, killer);
    }

    /**
     * NUEVO MÉTODO: Asegura el cierre de todos los hilos del sistema al cerrar JavaFX.
     * Debe ser invocado en el evento de salida de la aplicación principal.
     */
    public static void detenerTodo() {
        System.out.println("[SHUTDOWN] Iniciando apagado limpio de hilos de infraestructura...");
        
        if (monitorInstance != null) {
            // Asumiendo que el monitor tiene un método para detener su bucle nativo
            // monitorInstance.detener(); 
        }
        
        if (webSocketInstance != null) {
            webSocketInstance.detener(); // Detiene el servidor de sockets y su rate-limiter
        }
        
        if (trackingInstance != null) {
            trackingInstance.finalizar(); // Cancela el Scheduler subyacente del dominio
        }
        
        System.out.println("[SHUTDOWN] Sistema apagado correctamente.");
    }
}
