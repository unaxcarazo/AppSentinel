package org.appsentinel.infrastructure.adapter.out;

import org.appsentinel.domain.model.Registro;
import org.appsentinel.domain.port.out.RegistroRepositoryPort;
import org.appsentinel.infrastructure.db.DatabaseConnection;

import java.sql.*;
import java.util.ArrayList;
import java.util.List;

/**
 * PostgreSQLRepositoryAdapter: Implementa el puerto de persistencia. Traduce
 * objetos Registro a sentencias SQL y viceversa.
 */
public class PostgreSQLRepositoryAdapter implements RegistroRepositoryPort {

    @Override
    public void guardar(Registro registro) {
        String sql = """
            INSERT INTO registros_actividad 
            (usuario_sistema, nombre_actividad, categoria, detalle, duracion_seg, fecha_registro)
            VALUES (?, ?, ?, ?, ?, ?)
            """;

        try (Connection conn = DatabaseConnection.getConnection(); PreparedStatement stmt = conn.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS)) {

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

        try (Connection conn = DatabaseConnection.getConnection(); PreparedStatement stmt = conn.prepareStatement(sql)) {

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

        try (Connection conn = DatabaseConnection.getConnection(); PreparedStatement stmt = conn.prepareStatement(sql)) {

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

    @Override
    public List<Registro> obtenerTopDistracciones(String usuario, int limite) {
        List<Registro> registros = new ArrayList<>();
        String sql = """
            SELECT nombre_actividad, SUM(duracion_seg) as total_seg, categoria 
            FROM registros_actividad 
            WHERE usuario_sistema = ? AND categoria = 'DISTRACCION'
            AND DATE(fecha_registro) = CURRENT_DATE
            GROUP BY nombre_actividad, categoria
            ORDER BY total_seg DESC
            LIMIT ?
            """;

        try (Connection conn = DatabaseConnection.getConnection(); PreparedStatement stmt = conn.prepareStatement(sql)) {

            stmt.setString(1, usuario);
            stmt.setInt(2, limite);
            ResultSet rs = stmt.executeQuery();

            while (rs.next()) {
                // Usamos el builder pero solo con lo necesario para el ranking
                registros.add(Registro.builder()
                        .nombreActividad(rs.getString("nombre_actividad"))
                        .duracionSeg(rs.getLong("total_seg"))
                        .categoria(rs.getString("categoria"))
                        .build());
            }
        } catch (SQLException e) {
            System.err.println("Error al obtener ranking: " + e.getMessage());
        }
        return registros;
    }

    @Override
    public List<Registro> obtenerActividadHoy(String usuario) {
        List<Registro> registros = new ArrayList<>();
        // Traemos toda la actividad del usuario de hoy, ordenada por la más reciente primero
        String sql = """
            SELECT * FROM registros_actividad 
            WHERE usuario_sistema = ? 
            AND DATE(fecha_registro) = CURRENT_DATE
            ORDER BY fecha_registro DESC
            """;

        try (Connection conn = DatabaseConnection.getConnection(); PreparedStatement stmt = conn.prepareStatement(sql)) {

            stmt.setString(1, usuario);
            ResultSet rs = stmt.executeQuery();

            while (rs.next()) {
                // Reutilizamos el método mapearRegistro que ya tienes en el adaptador
                registros.add(mapearRegistro(rs));
            }

        } catch (SQLException e) {
            System.err.println("Error al obtener la actividad de hoy: " + e.getMessage());
        }

        return registros;
    }

    @Override
    public List<Registro> obtenerBloqueosHoy(String usuario) {
        List<Registro> registros = new ArrayList<>();
        String sql = """
            SELECT * FROM registros_actividad 
            WHERE usuario_sistema = ? AND detalle LIKE '%Blocked%'
            AND DATE(fecha_registro) = CURRENT_DATE
            ORDER BY fecha_registro DESC
            """;

        try (Connection conn = DatabaseConnection.getConnection(); PreparedStatement stmt = conn.prepareStatement(sql)) {

            stmt.setString(1, usuario);
            ResultSet rs = stmt.executeQuery();

            while (rs.next()) {
                registros.add(mapearRegistro(rs));
            }
        } catch (SQLException e) {
            System.err.println("Error al obtener bloqueos: " + e.getMessage());
        }
        return registros;
    }

    @Override
    public List<Registro> obtenerTopTrabajo(String usuario, int limite) {
        List<Registro> registros = new ArrayList<>();
        String sql = """
        SELECT nombre_actividad, SUM(duracion_seg) as total_seg, categoria 
        FROM registros_actividad 
        WHERE usuario_sistema = ? AND categoria = 'TRABAJO'
        AND DATE(fecha_registro) = CURRENT_DATE
        GROUP BY nombre_actividad, categoria
        ORDER BY total_seg DESC
        LIMIT ?
        """;

        try (Connection conn = DatabaseConnection.getConnection(); PreparedStatement stmt = conn.prepareStatement(sql)) {

            stmt.setString(1, usuario);
            stmt.setInt(2, limite);
            ResultSet rs = stmt.executeQuery();

            while (rs.next()) {
                registros.add(Registro.builder()
                        .nombreActividad(rs.getString("nombre_actividad"))
                        .duracionSeg(rs.getLong("total_seg"))
                        .categoria(rs.getString("categoria"))
                        .build());
            }
        } catch (SQLException e) {
            System.err.println("Error al obtener ranking de trabajo: " + e.getMessage());
        }
        return registros;
    }
}
