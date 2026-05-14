// ============================================
// org.appsentinel.domain.port.out.RegistroRepositoryPort
// ============================================
package org.appsentinel.domain.port.out;

import org.appsentinel.domain.model.Registro;
import java.util.List;

/**
 * Puerto de salida para persistencia.
 * El dominio dice: "Necesito guardar y leer registros".
 * PostgreSQLRepositoryAdapter implementará esto.
 */
public interface RegistroRepositoryPort {
    
    void guardar(Registro registro);
    
    List<Registro> obtenerTodosHoy(String usuario);
    
    List<Registro> obtenerPorCategoria(String usuario, String categoria);
    
    List<Registro> obtenerTopDistracciones(String usuario, int limite);
    
    List<Registro> obtenerTopTrabajo(String usuario, int limite);
    
    List<Registro> obtenerActividadHoy(String usuario);
    
    List<Registro> obtenerBloqueosHoy(String usuario);
}