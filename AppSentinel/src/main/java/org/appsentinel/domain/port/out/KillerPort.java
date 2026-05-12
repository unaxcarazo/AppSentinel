package org.appsentinel.domain.port.out; 

/**
 * Puerto de SALIDA — el dominio ordena cerrar,
 * la infraestructura ejecuta con ProcessHandle.
 */
public interface KillerPort {
    boolean cerrarProceso(String nombreProceso);
    void cerrarPestanaNavegador(int tabId);
}