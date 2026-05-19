package org.appsentinel;

/**
 * Launcher: Único punto de entrada real de la JVM.
 *
 * ¿POR QUÉ EXISTE ESTA CLASE?
 * Cuando Maven Shade empaqueta el JAR y la clase principal extiende
 * javafx.application.Application directamente, el runtime de JavaFX
 * no está inicializado todavía en el momento en que la JVM carga la clase.
 * Esto provoca el error:
 *   "Error: JavaFX runtime components are missing, and are required to run this application"
 *
 * La solución es que el main() viva en una clase que NO extienda Application.
 * Así la JVM carga Launcher sin tocar JavaFX, y solo cuando se llama a
 * AppSentinel.launch() es cuando JavaFX arranca de forma controlada.
 *
 * ¿DÓNDE ENCAJA EN LA ARQUITECTURA HEXAGONAL?
 * Vive en el paquete raíz org.appsentinel, que actúa como capa de bootstrap.
 * No pertenece al dominio ni a la infraestructura: su única responsabilidad
 * es delegar el arranque. No contiene ninguna lógica de negocio.
 */
public class Launcher {

    public static void main(String[] args) {
        // Delega el arranque completo a AppSentinel, que extiende Application
        // y gestiona el ciclo de vida de JavaFX (start, stop).
        // Se pasan los args para que flags como -Dappsentinel.headless=true funcionen.
        AppSentinel.launch(AppSentinel.class, args);
    }
}