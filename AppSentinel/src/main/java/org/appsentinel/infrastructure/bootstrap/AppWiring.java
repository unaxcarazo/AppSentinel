package org.appsentinel.infrastructure.bootstrap;

import org.appsentinel.domain.service.DistractionDetector;
import org.appsentinel.domain.service.TimeTrackingService;
import org.appsentinel.infrastructure.adapter.in.ProcessWindowMonitorAdapter;
import org.appsentinel.infrastructure.adapter.in.WebSocketAdapter;
import org.appsentinel.infrastructure.adapter.out.BufferedRegistroRepositoryAdapter;
import org.appsentinel.infrastructure.adapter.out.JavaFXAlertAdapter;
import org.appsentinel.infrastructure.adapter.out.OshiRendimientoAdapter;
import org.appsentinel.infrastructure.adapter.out.PostgreSQLCategoriaAdapter;
import org.appsentinel.infrastructure.adapter.out.PostgreSQLRepositoryAdapter;
import org.appsentinel.infrastructure.adapter.out.ProcessKillerAdapter;
import org.appsentinel.infrastructure.adapter.out.persistence.DatabaseConnection;
import org.appsentinel.infrastructure.config.AppConfig;

/**
 * AppWiring: Ensamblador del sistema (Dependency Injection manual).
 *
 * Instancia todos los adaptadores, los conecta con el dominio a través
 * de los puertos correspondientes y devuelve un AppContext inmutable.
 *
 * FIX 1.2 (Buffer de persistencia conectado):
 * PostgreSQLRepositoryAdapter se decora con BufferedRegistroRepositoryAdapter
 * antes de inyectarse al dominio. Las escrituras del escáner pasan por el
 * buffer en memoria (non-blocking); las lecturas de la UI van directo a PostgreSQL.
 *
 * FIX 1.3 (Shutdown graceful completo):
 * El orden de apagado garantiza zero data loss:
 *   1. Detener escáner (no más eventos de entrada)
 *   2. Detener WebSocket (no más eventos de navegador)
 *   3. Finalizar tracking (flush de memoria del dominio → encola en buffer)
 *   4. Flush buffer de persistencia (worker + residuales manuales)
 *   5. Cerrar pool HikariCP (liberar conexiones)
 *
 * ORDEN CRÍTICO: tracking.finalizar() DEBE ir ANTES de buffer.shutdown().
 * Si se invierte, finalizar() encola registros en el buffer después de que
 * el worker ya murió, y esos registros se pierden (el drainTo ya pasó).
 *
 * NOTA SOBRE FocoActivoPort (FIX 2.2):
 * TimeTrackingService implementa FocoActivoPort (domain.port.out).
 * En el wiring se pasa la misma instancia (tracking) dos veces en AppContext:
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
    // FIX 1.2: Referencia al buffer para shutdown graceful
    private static BufferedRegistroRepositoryAdapter bufferInstance;

    public static AppContext construir() {

        // ========== ADAPTADORES DE SALIDA ==========
        PostgreSQLCategoriaAdapter  categorias   = new PostgreSQLCategoriaAdapter();

        // FIX 1.2: Decorar PostgreSQLRepositoryAdapter con buffer de persistencia
        PostgreSQLRepositoryAdapter repoCrudo    = new PostgreSQLRepositoryAdapter();
        BufferedRegistroRepositoryAdapter repo   = new BufferedRegistroRepositoryAdapter(repoCrudo, 1000);
        bufferInstance = repo;

        JavaFXAlertAdapter          notificacion = new JavaFXAlertAdapter();
        OshiRendimientoAdapter      rendimiento  = new OshiRendimientoAdapter();

        // ========== DOMINIO ==========
        DistractionDetector detector = new DistractionDetector(categorias);

        // FIX 1.2: El dominio recibe el puerto decorado (buffer), no el crudo
        TimeTrackingService tracking = new TimeTrackingService(
            detector,
            repo,                           // ← BufferedRegistroRepositoryAdapter
            notificacion,
            null,                           // killer se inyecta después
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
        // tracking se pasa dos veces: como servicio y como FocoActivoPort (domain.port.out)
        return new AppContext(
            tracking,      // TimeTrackingService (MonitorPort, BrowserEventPort)
            repo,          // RegistroRepositoryPort — BufferedRegistroRepositoryAdapter
            categorias,
            notificacion,
            killer,
            rendimiento,
            tracking       // FocoActivoPort (domain.port.out, misma instancia, rol distinto)
        );
    }

    /**
     * FIX 1.3: Detiene todos los hilos de infraestructura en el orden correcto
     * para garantizar zero data loss en apagado normal.
     *
     * ORDEN CRÍTICO (no invertir):
     *   1. Monitor de procesos — deja de escanear el OS.
     *   2. WebSocket — cierra el puerto 8080 y el rate-limiter.
     *   3. Tracking — flushea chunks de memoria del dominio ENCOLÁNDOLOS en el buffer.
     *   4. Buffer de persistencia — vacía la cola (worker + drainTo manual) a PostgreSQL.
     *   5. Pool de conexiones — cierra HikariCP (libera recursos).
     *
     * Si el paso 4 ocurriera antes que el 3, los registros flusheados por
     * tracking.finalizar() llegarían a una cola ya vaciada, y el worker
     * estaría muerto. Se perderían datos. El orden actual es el único seguro.
     */
    public static void detenerTodo() {
        System.out.println("[SHUTDOWN] Iniciando apagado limpio de hilos de infraestructura...");

        if (monitorInstance != null) {
            monitorInstance.detener();
        }

        if (webSocketInstance != null) {
            webSocketInstance.detener();
        }

        // FIX 1.3: tracking.finalizar() ANTES de buffer.shutdown()
        // finalizar() encola sus registros pendientes en el buffer.
        // El buffer debe estar vivo para recibirlos.
        if (trackingInstance != null) {
            trackingInstance.finalizar();
        }

        // FIX 1.3: Flush del buffer DESPUÉS de que tracking haya encolado todo
        if (bufferInstance != null) {
            bufferInstance.shutdown();
        }

        // FIX 1.3: Cerrar pool HikariCP después de que todo ha flusheado
        DatabaseConnection.cerrarPool();

        System.out.println("[SHUTDOWN] Sistema apagado correctamente.");
    }
}