package org.appsentinel;

import org.appsentinel.infrastructure.adapter.out.persistence.DatabaseConnection;

import org.appsentinel.infrastructure.config.AppWiring;

/**
 * TestEscritorio: Prueba integral del entorno de escritorio real.
 * 
 * Precondición: Ejecutar seed SQL en PostgreSQL. Discord debe estar en categorias_app como DISTRACCION.
 * Instrucciones:
 *   1. Compilar: mvn clean package
 *   2. Ejecutar: java -cp target/AppSentinel-1.0-SNAPSHOT.jar org.appsentinel.TestEscritorio
 *   3. Abrir Discord manualmente cuando el programa lo indique.
 *   4. Esperar. El escáner detectará la ventana activa cada 10 segundos.
 * 
 * Resultado esperado:
 *   [DISTRACCION] discord
 *   [ALERTA] Llevas Xm Ys en discord
 *   [BLOQUEO] discord excedió en Zs
 *   [KILL] Cerrado forzoso PID xxx
 */
public class TestEscritorio {
    
    public static void main(String[] args) throws Exception {
        System.out.println("=== PRUEBA ESCRITORIO ===");
        System.out.println("Precondición: Seed SQL ejecutado en PostgreSQL.");
        System.out.println("Precondición: Discord clasificado como DISTRACCION en categorias_app.");
        
        AppWiring.construir();
        
        System.out.println("\n>>> ABRE DISCORD AHORA (tienes 5 segundos) <<<");
        Thread.sleep(5000);
        
        System.out.println("Escáner activo. Detectará ventana activa cada 10 segundos...");
        System.out.println("Esperando aviso (10s) → bloqueo (20s) → cierre (30s) + margen...");
        
        Thread.sleep(60000);
        
        System.out.println("\n=== VERIFICACIÓN MANUAL ===");
        System.out.println("Ejecuta en PostgreSQL para comprobar la persistencia física:");
        System.out.println("  SELECT id, nombre_actividad, categoria, duracion_seg FROM registros_actividad WHERE nombre_actividad LIKE '%discord%';");
        
        cleanup();
    }
    
    private static void cleanup() {
        AppWiring.detenerTodo();
        DatabaseConnection.cerrarPool();
        System.out.println("\n[FIN] Prueba finalizada limpiamente. Consola liberada.");
    }
}