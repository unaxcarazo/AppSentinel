// ============================================
// org.appsentinel.domain.port.out.ReportPort
// ============================================
package org.appsentinel.domain.port.out;

import org.appsentinel.domain.model.Registro;
import java.util.List;

/**
 * Puerto de salida para generación de informes.
 */
public interface ReportPort {
    
    /**
     * Genera el archivo DelayLog.html con los registros del día.
     * @param registros Lista de actividades de hoy
     * @param rutaSalida Ruta donde guardar el archivo
     */
    void generarReporteHtml(List<Registro> registros, String rutaSalida);
}