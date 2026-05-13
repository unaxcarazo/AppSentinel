package org.appsentinel.infrastructure.config;

import org.appsentinel.domain.service.DistractionDetector;
import org.appsentinel.domain.service.TimeTrackingService;
import org.appsentinel.infrastructure.adapter.in.WebSocketAdapter;
import org.appsentinel.infrastructure.adapter.out.JavaFXAlertAdapter;
import org.appsentinel.infrastructure.adapter.out.PostgreSQLCategoriaAdapter;
import org.appsentinel.infrastructure.adapter.out.PostgreSQLRepositoryAdapter;
import org.appsentinel.infrastructure.adapter.out.ProcessKillerAdapter;
import org.appsentinel.infrastructure.adapter.in.ProcessMonitorAdapter;

public class AppWiring {

    public static TimeTrackingService construir() {

    PostgreSQLCategoriaAdapter  categorias   = new PostgreSQLCategoriaAdapter();
    PostgreSQLRepositoryAdapter repo         = new PostgreSQLRepositoryAdapter();
    JavaFXAlertAdapter          notificacion = new JavaFXAlertAdapter();

    // WebSocketAdapter implementa BrowserEventPort Y BrowserCommandPort
    // Se crea primero porque ProcessKillerAdapter lo necesita
    DistractionDetector detector  = new DistractionDetector(categorias);

    TimeTrackingService tracking = new TimeTrackingService(
        detector, repo, notificacion,
        null, // killer — se inyecta justo debajo
        AppConfig.getSegundosAvisoPreventivo(),
        AppConfig.getSegundosBloqueoSesion(),
        AppConfig.getSegundosPausaReenfoque(),
        AppConfig.isModoEstricto(),
        AppConfig.getUsuarioSistema()
    );

    // WebSocket: adaptador de entrada Y de salida (comandos al navegador)
    WebSocketAdapter webSocket = new WebSocketAdapter(tracking);
    webSocket.iniciar();

    // Killer recibe el webSocket como BrowserCommandPort — sin acoplamiento directo
    ProcessKillerAdapter killer = new ProcessKillerAdapter(webSocket);

    // Inyectar killer en tracking
    tracking.setKiller(killer);

    // Monitor
    ProcessMonitorAdapter monitor = new ProcessMonitorAdapter(tracking);
    monitor.iniciar();

    return tracking;
}
}