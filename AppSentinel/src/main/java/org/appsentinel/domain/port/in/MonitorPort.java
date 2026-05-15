// ============================================
// org.appsentinel.domain.port.in.MonitorPort
// ============================================
package org.appsentinel.domain.port.in;

/**
 * Puerto de entrada para el monitoreo del sistema.
 * La infraestructura (ProcessMonitorAdapter) implementará esto
 * para decirle al dominio: "Oye, detecté esta actividad".
 */
public interface MonitorPort {
    
    /**
     * Reporta una actividad detectada del sistema operativo.
     * @param nombreProceso Nombre del proceso (ej: "chrome.exe")
     * @param tituloVentana Título de la ventana activa (ej: "YouTube - Google Chrome")
     */
    void reportarActividadSistema(String nombreProceso, String tituloVentana, int pid);
}