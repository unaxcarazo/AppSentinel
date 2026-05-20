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
 * FIX BATCH: Implementa guardarBatch() con PreparedStatement.addBatch() + executeBatch()
 * en transacción atómica (setAutoCommit(false) + commit()).
 * Un batch de 50 registros = 1 round-trip de red en lugar de 50.
 */
public class PostgreSQLRepositoryAdapter implements RegistroRepositoryPort {

    private static final Logger LOGGER = Logger.getLogger(PostgreSQLRepositoryAdapter.class.getName());

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

            setParams(stmt, registro);
            stmt.executeUpdate();

            try (ResultSet rs = stmt.getGeneratedKeys()) {
                if (rs.next()) {
                    registro.setId(rs.getLong(1));
                }
            }

            LOGGER.log(Level.FINE, "[PERSISTENCIA] Registro guardado: {0} ({1}s)", 
                new Object[]{truncar(registro.getNombreActividad(), 50), registro.getDuracionSeg()});

        } catch (SQLException e) {
            LOGGER.log(Level.SEVERE, "[ERROR] Fallo al insertar registro de actividad", e);
        }
    }

    /**
     * Batch insert transaccional con executeBatch().
     * Usa una sola conexión, una sola PreparedStatement, un solo round-trip de red.
     * Si falla, hace rollback para mantener consistencia.
     */
    @Override
    public void guardarBatch(List<Registro> registros) {
        if (registros == null || registros.isEmpty()) return;

        String sql = """
            INSERT INTO registros_actividad 
            (usuario_sistema, nombre_actividad, categoria, detalle, duracion_seg, fecha_registro)
            VALUES (?, ?, ?, ?, ?, ?)
            """;

        Connection conn = null;
        try {
            conn = DatabaseConnection.getConnection();
            conn.setAutoCommit(false);

            try (PreparedStatement stmt = conn.prepareStatement(sql)) {
                for (Registro reg : registros) {
                    if (reg == null) continue;
                    setParams(stmt, reg);
                    stmt.addBatch();
                }

                int[] resultados = stmt.executeBatch();
                conn.commit();

                int exitosos = 0;
                for (int r : resultados) {
                    if (r >= 0 || r == Statement.SUCCESS_NO_INFO) exitosos++;
                }

                LOGGER.log(Level.INFO, "[PERSISTENCIA] Batch ejecutado: {0}/{1} registros insertados",
                    new Object[]{exitosos, registros.size()});
            }

        } catch (SQLException e) {
            LOGGER.log(Level.SEVERE, "[ERROR] Fallo en batch insert de " + registros.size() + " registros. Haciendo rollback...", e);
            if (conn != null) {
                try { conn.rollback(); } catch (SQLException ex) {
                    LOGGER.log(Level.SEVERE, "[ERROR] Rollback fallido", ex);
                }
            }
        } finally {
            if (conn != null) {
                try {
                    conn.setAutoCommit(true);
                    conn.close();
                } catch (SQLException e) {
                    LOGGER.log(Level.WARNING, "[ERROR] Fallo restaurando auto-commit", e);
                }
            }
        }
    }

    private void setParams(PreparedStatement stmt, Registro reg) throws SQLException {
        stmt.setString(1, truncar(reg.getUsuarioSistema(), LIMITE_USUARIO));
        stmt.setString(2, truncar(reg.getNombreActividad(), LIMITE_NOMBRE_ACTIVIDAD));
        stmt.setString(3, truncar(reg.getCategoria(), LIMITE_CATEGORIA));
        stmt.setString(4, truncar(reg.getDetalle(), LIMITE_DETALLE));
        stmt.setLong(5, reg.getDuracionSeg());
        stmt.setTimestamp(6, Timestamp.valueOf(reg.getFechaRegistro()));
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

    private String truncar(String valor, int maximo) {
        if (valor == null) return null;
        return valor.length() > maximo ? valor.substring(0, maximo) : valor;
    }
}