// ============================================
// org.appsentinel.domain.port.in.MonitorPort
// ============================================
package org.appsentinel.domain.port.in;

import java.time.Instant;

/**
 * Puerto de entrada para el monitoreo del sistema operativo.
 * La infraestructura (ProcessWindowMonitorAdapter) implementa este puerto
 * para reportar actividad detectada al dominio.
 *
 * ARQUITECTURA HEXAGONAL — FIX 2.1:
 * El adaptador de entrada resuelve todos los metadatos del proceso
 * (incluyendo startInstant via ProcessHandle/JNA) ANTES de notificar.
 * El dominio recibe datos planos; nunca consulta el sistema operativo.
 */
public interface MonitorPort {

    /**
     * Reporta una actividad detectada del sistema operativo.
     *
     * @param nombreProceso  Nombre del proceso (ej: "chrome.exe")
     * @param tituloVentana  Título de la ventana activa (ej: "YouTube - Google Chrome")
     * @param pid            PID del proceso detectado, o -1 si no está disponible
     * @param startInstant   Instante de inicio del proceso tal como fue capturado
     *                       por el adaptador de infraestructura. Null si no está
     *                       disponible o si el pid es -1.
     */
    void reportarActividadSistema(String nombreProceso, String tituloVentana,
                                   int pid, Instant startInstant);
}