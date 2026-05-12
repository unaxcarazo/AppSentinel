package org.appsentinel.infrastructure.adapter.out;

import org.appsentinel.domain.model.Registro;
import org.appsentinel.domain.port.out.RegistroRepositoryPort;
import org.appsentinel.infrastructure.db.DatabaseConnection;

import java.sql.*;
import java.util.ArrayList;
import java.util.List;

/**
 * PostgreSQLRepositoryAdapter: Implementa el puerto de persistencia.
 * Traduce objetos Registro a sentencias SQL y viceversa.
 */
public class PostgreSQLRepositoryAdapter implements RegistroRepositoryPort {
    
    @Override
    public void guardar(Registro registro) {
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
            
            // Recupera el ID autogenerado
            try (ResultSet rs = stmt.getGeneratedKeys()) {
                if (rs.next()) {
                    registro.setId(rs.getLong(1));
                }
            }
            
            System.out.println("Registro guardado: " + registro.getNombreActividad());
            
        } catch (SQLException e) {
            System.err.println("Error al guardar registro: " + e.getMessage());
        }
    }
    
    @Override
    public List<Registro> obtenerTodosHoy(String usuario) {
        List<Registro> registros = new ArrayList<>();
        String sql = """
            SELECT * FROM registros_actividad 
            WHERE usuario_sistema = ? 
            AND DATE(fecha_registro) = CURRENT_DATE
            ORDER BY fecha_registro DESC
            """;
        
        try (Connection conn = DatabaseConnection.getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql)) {
            
            stmt.setString(1, usuario);
            ResultSet rs = stmt.executeQuery();
            
            while (rs.next()) {
                registros.add(mapearRegistro(rs));
            }
            
        } catch (SQLException e) {
            System.err.println("Error al leer registros: " + e.getMessage());
        }
        
        return registros;
    }
    
    @Override
    public List<Registro> obtenerPorCategoria(String usuario, String categoria) {
        List<Registro> registros = new ArrayList<>();
        String sql = """
            SELECT * FROM registros_actividad 
            WHERE usuario_sistema = ? AND categoria = ?
            AND DATE(fecha_registro) = CURRENT_DATE
            ORDER BY duracion_seg DESC
            """;
        
        try (Connection conn = DatabaseConnection.getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql)) {
            
            stmt.setString(1, usuario);
            stmt.setString(2, categoria);
            ResultSet rs = stmt.executeQuery();
            
            while (rs.next()) {
                registros.add(mapearRegistro(rs));
            }
            
        } catch (SQLException e) {
            System.err.println("Error al filtrar registros: " + e.getMessage());
        }
        
        return registros;
    }
    
    /**
     * Mapea un ResultSet a un objeto Registro.
     */
    private Registro mapearRegistro(ResultSet rs) throws SQLException {
        return Registro.builder()
            .id(rs.getLong("id"))
            .usuarioSistema(rs.getString("usuario_sistema"))
            .nombreActividad(rs.getString("nombre_actividad"))
            .categoria(rs.getString("categoria"))
            .detalle(rs.getString("detalle"))
            .duracionSeg(rs.getLong("duracion_seg"))
            .fechaRegistro(rs.getTimestamp("fecha_registro").toLocalDateTime())
            .build();
    }
}