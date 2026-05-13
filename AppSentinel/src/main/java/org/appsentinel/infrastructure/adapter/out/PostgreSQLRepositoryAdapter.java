package org.appsentinel.infrastructure.adapter.out;

import org.appsentinel.domain.model.Registro;
import org.appsentinel.domain.port.out.RegistroRepositoryPort;
import org.appsentinel.infrastructure.adapter.out.persistence.DatabaseConnection;

import java.sql.*;
import java.util.ArrayList;
import java.util.List;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * PostgreSQLRepositoryAdapter: Adaptador de SALIDA para la persistencia del historial.
 * 
 * Registra y extrae las métricas de consumo de tiempo acumuladas en los hilos del dominio.
 */
public class PostgreSQLRepositoryAdapter implements RegistroRepositoryPort {
    
    private static final Logger LOGGER = Logger.getLogger(PostgreSQLRepositoryAdapter.class.getName());
    
    @Override
    public void guardar(Registro registro) {
        if (registro == null) return;

        String sql = """
            INSERT INTO registros_actividad 
            (usuario_sistema, nombre_actividad, categoria, detalle, duracion_seg, fecha_registro)
            VALUES (?, ?, ?, ?, ?, ?)
            """;
        
        try (Connection conn = DatabaseConnection.getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS)) {
            
            stmt.setString(1, registro.getUsuarioSistema());
            stmt.setString(2, registro.getNombreActividad());
            stmt.setString(3, registro.getCategoria());
            stmt.setString(4, registro.getDetalle());
            stmt.setLong(5, registro.getDuracionSeg());
            stmt.setTimestamp(6, Timestamp.valueOf(registro.getFechaRegistro()));
            
            stmt.executeUpdate();
            
            try (ResultSet rs = stmt.getGeneratedKeys()) {
                if (rs.next()) {
                    registro.setId(rs.getLong(1));
                }
            }
            
            LOGGER.log(Level.INFO, "[PERSISTENCIA] Registro guardado en PostgreSQL: {0} ({1}s)", 
                new Object[]{registro.getNombreActividad(), registro.getDuracionSeg()});
            
        } catch (SQLException e) {
            LOGGER.log(Level.SEVERE, "[ERROR] Fallo crítico al insertar registro de actividad en la base de datos", e);
        }
    }
    
    @Override
    public List<Registro> obtenerTodosHoy(String usuario) {
        List<Registro> registros = new ArrayList<>();
        String sql = """
            SELECT id, usuario_sistema, nombre_actividad, categoria, detalle, duracion_seg, fecha_registro 
            FROM registros_actividad 
            WHERE usuario_sistema = ? 
            AND DATE(fecha_registro) = CURRENT_DATE
            ORDER BY fecha_registro DESC
            """;
        
        try (Connection conn = DatabaseConnection.getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql)) {
            
            stmt.setString(1, usuario);
            try (ResultSet rs = stmt.executeQuery()) {
                while (rs.next()) {
                    registros.add(mapearRegistro(rs));
                }
            }
            
        } catch (SQLException e) {
            LOGGER.log(Level.SEVERE, "[ERROR] Fallo al extraer el historial diario de actividad de la BD", e);
        }
        
        return registros;
    }
    
    @Override
    public List<Registro> obtenerPorCategoria(String usuario, String categoria) {
        List<Registro> registros = new ArrayList<>();
        String sql = """
            SELECT id, usuario_sistema, nombre_actividad, categoria, detalle, duracion_seg, fecha_registro 
            FROM registros_actividad 
            WHERE usuario_sistema = ? AND categoria = ?
            AND DATE(fecha_registro) = CURRENT_DATE
            ORDER BY duracion_seg DESC
            """;
        
        try (Connection conn = DatabaseConnection.getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql)) {
            
            stmt.setString(1, usuario);
            stmt.setString(2, categoria);
            try (ResultSet rs = stmt.executeQuery()) {
                while (rs.next()) {
                    registros.add(mapearRegistro(rs));
                }
            }
            
        } catch (SQLException e) {
            LOGGER.log(Level.SEVERE, "[ERROR] Fallo al filtrar registros por categoría en PostgreSQL", e);
        }
        
        return registros;
    }
    
    /**
     * Mapeo tradicional mediante instanciación estándar y setters.
     * 
     */
    private Registro mapearRegistro(ResultSet rs) throws SQLException {
        Registro reg = new Registro();
        reg.setId(rs.getLong("id"));
        reg.setUsuarioSistema(rs.getString("usuario_sistema"));
        reg.setNombreActividad(rs.getString("nombre_actividad"));
        reg.setCategoria(rs.getString("categoria"));
        reg.setDetalle(rs.getString("detalle"));
        reg.setDuracionSeg(rs.getLong("duracion_seg"));
        reg.setFechaRegistro(rs.getTimestamp("fecha_registro").toLocalDateTime());
        return reg;
    }
}
