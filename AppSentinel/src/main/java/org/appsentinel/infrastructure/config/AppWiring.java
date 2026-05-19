package org.appsentinel.infrastructure.config;

import org.appsentinel.domain.service.DistractionDetector;
import org.appsentinel.domain.service.TimeTrackingService;
import org.appsentinel.domain.port.out.RegistroRepositoryPort;
import org.appsentinel.domain.port.out.CategoriaRepositoryPort;
import org.appsentinel.infrastructure.adapter.in.WebSocketAdapter;
import org.appsentinel.infrastructure.adapter.out.JavaFXAlertAdapter;
import org.appsentinel.infrastructure.adapter.out.PostgreSQLCategoriaAdapter;
import org.appsentinel.infrastructure.adapter.out.PostgreSQLRepositoryAdapter;
import org.appsentinel.infrastructure.adapter.out.ProcessKillerAdapter;
import org.appsentinel.infrastructure.adapter.out.ProcessMonitorAdapter;

public class AppWiring {

    // Referencias globales estáticas que alimentarán a la interfaz gráfica (GUI)
    private static PostgreSQLRepositoryAdapter repo;
    private static PostgreSQLCategoriaAdapter categorias;

    public static TimeTrackingService construir() {

        // ====================================================================
        // 🔄 CORRECCIÓN: Asignación directa a los atributos estáticos de la clase
        // ====================================================================
        repo = new PostgreSQLRepositoryAdapter();
        categorias = new PostgreSQLCategoriaAdapter();
        
        JavaFXAlertAdapter notificacion = new JavaFXAlertAdapter();

        // El detector de distracciones usa el adaptador de categorías inyectado
        DistractionDetector detector = new DistractionDetector(categorias);

        TimeTrackingService tracking = new TimeTrackingService(
                detector, repo, notificacion,
                null, // killer — se inyecta justo debajo mediante setter
                AppConfig.getSegundosAvisoPreventivo(),
                AppConfig.getSegundosBloqueoSesion(),
                AppConfig.getSegundosPausaReenfoque(),
                AppConfig.isModoEstricto(),
                AppConfig.getUsuarioSistema()
        );

        // Configuración y encendido del WebSocket para eventos del navegador
        WebSocketAdapter webSocket = new WebSocketAdapter(tracking);
        webSocket.iniciar();

        // El Killer despacha comandos de cierre de pestañas vía WebSocket
        ProcessKillerAdapter killer = new ProcessKillerAdapter(webSocket);

        // Inyectar killer de vuelta en el núcleo del servicio (Cierre de ciclo)
        tracking.setKiller(killer);

        // Inicialización del demonio/hilo monitor de procesos de escritorio
        ProcessMonitorAdapter monitor = new ProcessMonitorAdapter(tracking);
        monitor.iniciar();

        return tracking;
    }

    // ====================================================================
    // 🛠️ MÉTODOS DE EXTRACCIÓN DE PUERTOS (Ahora devuelven datos reales)
    // ====================================================================
    
    // Diego: Devuelve el adaptador vivo de PostgreSQL bajo el contrato del puerto de registros
    public static RegistroRepositoryPort obtenerRegistroRepositoryPort() {
        return repo;
    }

    // Devuelve el adaptador vivo de PostgreSQL bajo el contrato del puerto de categorías
    public static CategoriaRepositoryPort obtenerCategoriaRepositoryPort() {
        return categorias;
    }
}