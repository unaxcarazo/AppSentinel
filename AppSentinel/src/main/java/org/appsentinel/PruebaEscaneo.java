package org.appsentinel;

import org.appsentinel.domain.service.DistractionDetector;
import org.appsentinel.domain.service.TimeTrackingService;
import org.appsentinel.infrastructure.adapter.in.ProcessMonitorAdapter;
import org.appsentinel.infrastructure.adapter.in.WebSocketAdapter;
import org.appsentinel.infrastructure.adapter.out.*;
import org.appsentinel.infrastructure.config.AppConfig;

public class PruebaEscaneo {
    public static void main(String[] args) throws Exception {
        System.out.println("=== PRUEBA DE ESCANEO Y CIERRE ===");
        
        // 1. Construir dependencias mínimas
        var categorias = new PostgreSQLCategoriaAdapter();
        var repo = new PostgreSQLRepositoryAdapter();
        var notificacion = new JavaFXAlertAdapter(); // No mostrará UI sin FX, pero sirve
        var detector = new DistractionDetector(categorias);
        
        // 2. Crear TimeTrackingService
        var tracking = new TimeTrackingService(
            detector, repo, notificacion, null,
            10, 20, 30, false, AppConfig.getUsuarioSistema()
        );
        
        // 3. Crear Killer e inyectarlo
        var webSocket = new WebSocketAdapter(tracking);
        var killer = new ProcessKillerAdapter(webSocket);
        tracking.setKiller(killer);
        
        // 4. Iniciar escáner
        var monitor = new ProcessMonitorAdapter(tracking);
        monitor.iniciar();
        
        // 5. Abrir Notepad manualmente AHORA
        System.out.println(">>> Abre Notepad ahora (tienes 5 segundos) <<<");
        Thread.sleep(5000);
        
        // 6. Esperar a que se cierre automáticamente (30 segundos + márgenes)
        System.out.println("Esperando cierre automático... No toques nada.");
        Thread.sleep(45000);
        
        // 7. Verificar en consola
        System.out.println("=== VERIFICACIÓN MANUAL ===");
        System.out.println("1. ¿Notepad se cerró solo? (debería aparecer 'Cerrado PID xxx')");
        System.out.println("2. Revisa la tabla registros_actividad en PostgreSQL:");
        System.out.println("   SELECT * FROM registros_actividad WHERE nombre_actividad LIKE '%notepad%';");
        
        tracking.finalizar();
        monitor.detener();
        System.exit(0);
    }
}