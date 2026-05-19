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
 * 
 * FIX: Trunca campos de texto que excedan los límites de la BD para evitar
 * PSQLException: "el valor es demasiado largo para el tipo character varying(N)".
 */
public class PostgreSQLRepositoryAdapter implements RegistroRepositoryPort {

    private static final Logger LOGGER = Logger.getLogger(PostgreSQLRepositoryAdapter.class.getName());

    // Límites de columnas en PostgreSQL (ajustar según el DDL real)
    private static final int LIMITE_NOMBRE_ACTIVIDAD = 255;
    private static final int LIMITE_CATEGORIA = 100;
    private static final int LIMITE_DETALLE = 500;
    private static final int LIMITE_USUARIO = 100;

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

            stmt.setString(1, truncar(registro.getUsuarioSistema(), LIMITE_USUARIO));
            stmt.setString(2, truncar(registro.getNombreActividad(), LIMITE_NOMBRE_ACTIVIDAD));
            stmt.setString(3, truncar(registro.getCategoria(), LIMITE_CATEGORIA));
            stmt.setString(4, truncar(registro.getDetalle(), LIMITE_DETALLE));
            stmt.setLong(5, registro.getDuracionSeg());
            stmt.setTimestamp(6, Timestamp.valueOf(registro.getFechaRegistro()));

            stmt.executeUpdate();

            try (ResultSet rs = stmt.getGeneratedKeys()) {
                if (rs.next()) {
                    registro.setId(rs.getLong(1));
                }
            }

            LOGGER.log(Level.INFO, "[PERSISTENCIA] Registro guardado en PostgreSQL: {0} ({1}s)", 
                new Object[]{truncar(registro.getNombreActividad(), 50), registro.getDuracionSeg()});

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

    /**
     * Trunca un string al máximo permitido, evitando PSQLException por overflow.
     * Si el valor es null, retorna null.
     */
    private String truncar(String valor, int maximo) {
        if (valor == null) return null;
        return valor.length() > maximo ? valor.substring(0, maximo) : valor;
    }
}