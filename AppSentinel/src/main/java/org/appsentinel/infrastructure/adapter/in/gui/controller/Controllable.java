package org.appsentinel.infrastructure.adapter.in.gui.controller;

import org.appsentinel.infrastructure.bootstrap.AppContext;

/**
 * Controllable: Contrato de ciclo de vida para todos los controladores FXML.
 *
 * Define dos métodos:
 * - init(ctx):  inyección de dependencias al cargar la vista.
 * - shutdown(): liberación de recursos al descargar la vista.
 *
 * POR QUÉ shutdown() ESTÁ AQUÍ Y NO EN EL DOMINIO:
 * Es un contrato de infraestructura, no de dominio. El dominio no sabe
 * que existe JavaFX ni schedulers de UI. Este patrón es consistente con
 * el resto del proyecto: WebSocketAdapter tiene detener(), 
 * ProcessWindowMonitorAdapter tiene detener(), TimeTrackingService tiene
 * finalizar(). Controllable.shutdown() es exactamente lo mismo dentro
 * de su capa correspondiente.
 *
 * POR QUÉ shutdown() ES default:
 * La mayoría de controladores no tienen recursos que liberar (no arrancan
 * schedulers ni hilos propios). El método default evita que cada controlador
 * vacío tenga que implementar un cuerpo vacío. Solo los controladores que
 * arrancan recursos propios (como PerformanceController) lo sobreescriben.
 *
 * FLUJO DE NAVEGACIÓN EN MainController:
 *   1. controladorActual.shutdown()  → libera recursos del controlador saliente
 *   2. nuevoControlador.init(ctx)    → inyecta dependencias al entrante
 */
public interface Controllable {

    /**
     * Inyecta el contexto global de la aplicación.
     * Se llama una vez al cargar la vista, antes de que sea visible.
     *
     * @param ctx Grafo de dependencias inmutable con todos los puertos.
     */
    void init(AppContext ctx);

    /**
     * Libera los recursos propios del controlador al descargar la vista.
     *
     * Implementación por defecto vacía: los controladores sin recursos
     * propios no necesitan sobreescribir este método.
     *
     * Sobreescribir cuando el controlador arranque schedulers, timers,
     * listeners o cualquier recurso que deba cerrarse explícitamente.
     */
    default void shutdown() {
        // Sin recursos que liberar por defecto
    }
}   