package org.appsentinel.domain.port.out; 

/**
 * Puerto de SALIDA — el dominio ordena cerrar,
 * la infraestructura ejecuta con ProcessHandle.
 */
public interface KillerPort {
    boolean cerrarProceso(String nombreProceso, int pid);
    void cerrarPestañaNavegador(int tabId);
}