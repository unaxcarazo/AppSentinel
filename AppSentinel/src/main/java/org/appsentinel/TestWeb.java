package org.appsentinel;

import org.appsentinel.infrastructure.adapter.out.persistence.DatabaseConnection;
import org.appsentinel.infrastructure.bootstrap.AppWiring;

/**
 * PruebaWeb: Prueba integral de la pila de red y el comportamiento con el navegador.
 * 
 * Precondición: Seed SQL ejecutado. youtube.com como DISTRACCION en categorias_app.
 * Precondición: Extensión Chrome instalada que conecte a ws://localhost:8080
 *   y envíe: {"url":"https://youtube.com/watch?v=...","titulo":"YouTube","tabId":123}
 */
public class TestWeb {
    
    public static void main(String[] args) throws Exception {
        System.out.println("=== PRUEBA WEB ===");
        System.out.println("Precondición: Seed SQL ejecutado. youtube.com como DISTRACCION.");
        System.out.println("Precondición: Extensión Chrome conectada a ws://localhost:8080");
        
        AppWiring.construir();
        
        System.out.println("\nWebSocket escuchando activamente en puerto 8080...");
        System.out.println(">>> ABRE CHROME + YOUTUBE AHORA (tienes 15 segundos) <<<");
        System.out.println(">>> ASEGÚRATE DE QUE LA EXTENSIÓN ENVÍE EL tabId EN EL JSON <<<");
        Thread.sleep(15000);
        
        System.out.println("Esperando detección asíncrona del dominio + orden de cierre de pestaña...");
        
        Thread.sleep(60000);
        
        System.out.println("\n=== VERIFICACIÓN MANUAL ===");
        System.out.println("Ejecuta en PostgreSQL para validar el guardado físico:");
        System.out.println("  SELECT id, nombre_actividad, categoria, detalle, duracion_seg FROM registros_actividad WHERE nombre_actividad LIKE '%youtube%';");
        
        cleanup();
    }
    

    private static void cleanup() {
        AppWiring.detenerTodo();
        DatabaseConnection.cerrarPool();
        System.out.println("\n[FIN] Prueba web concluida. Puerto 8080 liberado con éxito.");
    }
}