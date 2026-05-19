package org.appsentinel;

import org.appsentinel.infrastructure.adapter.out.persistence.DatabaseConnection;
import org.appsentinel.infrastructure.config.AppContext;
import org.appsentinel.infrastructure.config.AppWiring;

/**
 * PruebaAutoDescubrimiento: Prueba integral del flujo de auto-descubrimiento.
 * 
 * Precondición: Seed SQL ejecutado.
 * Instrucciones:
 *   1. Ejecutar esta clase.
 *   2. Abrir una app NO existente en seed (ej: GIMP, Blender, OBS).
 *      O navegar a una web NO existente (ej: https://ejemplo.com).
 *   3. Esperar a que el escáner la detecte (máximo 10 segundos).
 * 
 * Resultado esperado:
 *   [SIN_CLASIFICAR] gimp / ejemplo.com
 *   [AUTO-DESCUBRIMIENTO] Nueva app guardada en PostgreSQL: xxx
 */
public class TestAutoDescubrimiento {
    
    public static void main(String[] args) throws Exception {
        System.out.println("=== PRUEBA AUTO-DESCUBRIMIENTO ===");
        System.out.println("Precondición: Seed SQL ejecutado.");
        
        AppContext ctx = AppWiring.construir();
        
        System.out.println("\n>>> ABRE UNA APP O WEB DESCONOCIDA <<<");
        System.out.println("Ejemplos: GIMP, Blender, OBS, o https://ejemplo.com");
        System.out.println("Tienes 15 segundos...");
        Thread.sleep(15000);
        
        System.out.println("Esperando detección por el escáner (ciclo de 10s)...");
        Thread.sleep(20000);
        
        System.out.println("\n=== VERIFICACIÓN ===");
        System.out.println("Apps sin clasificar en BD:");
        var sinClasificar = ctx.categorias().obtenerAppsSinClasificar();
        sinClasificar.forEach(app -> System.out.println("  - " + app));
        
        System.out.println("\nEjecuta en PostgreSQL:");
        System.out.println("  SELECT * FROM categorias_app WHERE categoria = 'SIN_CLASIFICAR';");
        
        cleanup(ctx);
    }
    
    private static void cleanup(AppContext ctx) {
        ctx.tracking().finalizar();
        AppWiring.detenerTodo();
        DatabaseConnection.cerrarPool();
        System.out.println("\n[FIN] Prueba finalizada limpiamente. Consola liberada.");
    }
}