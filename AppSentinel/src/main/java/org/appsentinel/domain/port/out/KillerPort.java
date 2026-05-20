package org.appsentinel.domain.port.out;

import java.time.Instant;

/**
 * Puerto de SALIDA — el dominio ordena cerrar,
 * la infraestructura ejecuta con ProcessHandle.
 *
 * FIX A.1 (Anti-PID-Recycling):
 * El método cerrarProceso recibe ahora el startInstant del proceso
 * tal como fue detectado por el escáner. El adaptador comparará este
 * valor contra el startInstant actual del PID antes de ejecutar
 * destroyForcibly(), abortando si el PID fue reciclado.
 */
public interface KillerPort {

    /**
     * Ordena el cierre de un proceso del sistema operativo.
     *
     * @param nombreProceso Nombre reportado por el escáner (ej: "discord")
     * @param pid PID del proceso detectado
     * @param startInstant Instante de inicio del proceso tal como fue capturado
     *                     por el escáner. Null si no está disponible.
     * @return true si el cierre fue ejecutado, false si fue abortado por seguridad
     */
    boolean cerrarProceso(String nombreProceso, int pid, Instant startInstant);

    void cerrarPestañaNavegador(int tabId);
}