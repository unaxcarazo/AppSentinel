package org.appsentinel.infrastructure.adapter.out;

import org.appsentinel.domain.model.Registro;
import org.appsentinel.domain.model.ResumenActividad;
import org.appsentinel.domain.port.out.RegistroRepositoryPort;
import org.appsentinel.infrastructure.adapter.out.persistence.DatabaseConnection;

import java.sql.*;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * PostgreSQLRepositoryAdapter: Adaptador de SALIDA para la persistencia del
 * historial.
 *
 * FIX BATCH: Implementa guardarBatch() con PreparedStatement.addBatch() +
 * executeBatch() en transacción atómica (setAutoCommit(false) + commit()).
 *
 * FIX UI: obtenerResumenPorApp() usa GROUP BY para que cada app aparezca UNA
 * SOLA VEZ con su tiempo total acumulado, eliminando duplicados de la vista.
 *
 * FIX 2.3 (SQL Sargable + Preservar Orden): - Reemplaza DATE(fecha_registro) =
 * CURRENT_DATE por rango de timestamp que aprovecha índices B-Tree: >=
 * CURRENT_DATE AND < CURRENT_DATE + INTERVAL '1 day'. - Cambia HashMap por
 * LinkedHashMap en obtenerResumenPorApp() para preservar el ORDER BY
 * tiempo_total_seg DESC de PostgreSQL.
 *
 * ÍNDICE RECOMENDADO: CREATE INDEX idx_registros_user_fecha ON
 * registros_actividad(usuario_sistema, fecha_registro);
 */
public class PostgreSQLRepositoryAdapter implements RegistroRepositoryPort {

    private static final Logger LOGGER = Logger.getLogger(PostgreSQLRepositoryAdapter.class.getName());

    private static final int LIMITE_NOMBRE_ACTIVIDAD = 255;
    private static final int LIMITE_CATEGORIA = 100;
    private static final int LIMITE_DETALLE = 500;
    private static final int LIMITE_USUARIO = 100;

    @Override
    public void guardar(Registro registro) {
        if (registro == null) {
            return;
        }

        String sql = """
            INSERT INTO registros_actividad
            (usuario_sistema, nombre_actividad, categoria, detalle, duracion_seg, fecha_registro)
            VALUES (?, ?, ?, ?, ?, ?)
            """;

        try (Connection conn = DatabaseConnection.getConnection(); PreparedStatement stmt = conn.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS)) {

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
     * Batch insert transaccional con executeBatch(). Usa una sola conexión, una
     * sola PreparedStatement, un solo round-trip de red. Si falla, hace
     * rollback para mantener consistencia.
     */
    @Override
    public void guardarBatch(List<Registro> registros) {
        if (registros == null || registros.isEmpty()) {
            return;
        }

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
                    if (reg == null) {
                        continue;
                    }
                    setParams(stmt, reg);
                    stmt.addBatch();
                }

                int[] resultados = stmt.executeBatch();
                conn.commit();

                int exitosos = 0;
                for (int r : resultados) {
                    if (r >= 0 || r == Statement.SUCCESS_NO_INFO) {
                        exitosos++;
                    }
                }

                LOGGER.log(Level.INFO, "[PERSISTENCIA] Batch ejecutado: {0}/{1} registros insertados",
                        new Object[]{exitosos, registros.size()});
            }

        } catch (SQLException e) {
            LOGGER.log(Level.SEVERE, "[ERROR] Fallo en batch insert de " + registros.size() + " registros. Haciendo rollback...", e);
            if (conn != null) {
                try {
                    conn.rollback();
                } catch (SQLException ex) {
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

    /**
     * FIX 2.3: SQL sargable — reemplaza DATE(fecha_registro) = CURRENT_DATE por
     * rango de timestamp que aprovecha índices B-Tree.
     */
    @Override
    public List<Registro> obtenerTodosHoy(String usuario) {
        List<Registro> registros = new ArrayList<>();
        String sql = """
            SELECT id, usuario_sistema, nombre_actividad, categoria, detalle, duracion_seg, fecha_registro
            FROM registros_actividad
            WHERE usuario_sistema = ?
              AND fecha_registro >= CURRENT_DATE
              AND fecha_registro < CURRENT_DATE + INTERVAL '1 day'
            ORDER BY fecha_registro DESC
            """;

        try (Connection conn = DatabaseConnection.getConnection(); PreparedStatement stmt = conn.prepareStatement(sql)) {

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

    /**
     * FIX 2.3: SQL sargable — mismo rango de timestamp que obtenerTodosHoy.
     *
     * NOTA SOBRE CATEGORÍAS DE BACKGROUND: Este método filtra por coincidencia
     * exacta de la categoría pasada como parámetro. Si se pasa "PRODUCTIVO", NO
     * devolverá registros con categoría "BACKGROUND_PRODUCTIVO". Las categorías
     * de segundo plano (prefijo BACKGROUND_) se gestionan de forma separada en
     * el dominio y no están incluidas en los resultados de foco activo. La UI
     * debe consumir este método sabiendo que solo recibe registros de foco
     * directo.
     */
    @Override
    public List<Registro> obtenerPorCategoria(String usuario, String categoria) {
        List<Registro> registros = new ArrayList<>();
        String sql = """
            SELECT id, usuario_sistema, nombre_actividad, categoria, detalle, duracion_seg, fecha_registro
            FROM registros_actividad
            WHERE usuario_sistema = ? AND categoria = ?
              AND fecha_registro >= CURRENT_DATE
              AND fecha_registro < CURRENT_DATE + INTERVAL '1 day'
            ORDER BY duracion_seg DESC
            """;

        try (Connection conn = DatabaseConnection.getConnection(); PreparedStatement stmt = conn.prepareStatement(sql)) {

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
     * FIX UI + FIX 2.3: Resumen agrupado por aplicación.
     *
     * Cada app aparece UNA SOLA VEZ con su tiempo total acumulado. SQL: GROUP
     * BY nombre_actividad, categoria + SUM(duracion_seg) + MAX(fecha_registro)
     *
     * FIX 2.3: Usa LinkedHashMap para preservar el orden descendente de tiempo
     * total que PostgreSQL devuelve via ORDER BY. HashMap perdía el orden.
     *
     * @param usuario Usuario del sistema operativo
     * @return Map<nombre_actividad, ResumenActividad> ordenado por tiempo total
     * DESC. LinkedHashMap preserva el orden de inserción (orden de la query).
     */
    @Override
    public Map<String, ResumenActividad> obtenerResumenPorApp(String usuario) {
        // FIX 2.3: LinkedHashMap preserva el orden de inserción (orden del ORDER BY SQL)
        Map<String, ResumenActividad> resumen = new LinkedHashMap<>();

        String sql = """
            SELECT
                nombre_actividad,
                categoria,
                SUM(duracion_seg) as tiempo_total_seg,
                MAX(fecha_registro) as ultima_vez
            FROM registros_actividad
            WHERE usuario_sistema = ?
              AND fecha_registro >= CURRENT_DATE
              AND fecha_registro < CURRENT_DATE + INTERVAL '1 day'
            GROUP BY nombre_actividad, categoria
            ORDER BY tiempo_total_seg DESC
            """;

        try (Connection conn = DatabaseConnection.getConnection(); PreparedStatement stmt = conn.prepareStatement(sql)) {

            stmt.setString(1, usuario);
            try (ResultSet rs = stmt.executeQuery()) {
                while (rs.next()) {
                    String nombre = rs.getString("nombre_actividad");
                    String categoria = rs.getString("categoria");
                    long tiempoTotal = rs.getLong("tiempo_total_seg");
                    Timestamp ultimaVez = rs.getTimestamp("ultima_vez");

                    resumen.put(nombre, new ResumenActividad(
                            nombre,
                            categoria,
                            tiempoTotal,
                            ultimaVez != null ? ultimaVez.toLocalDateTime() : null
                    ));
                }
            }

            LOGGER.log(Level.FINE, "[PERSISTENCIA] Resumen por app: {0} apps únicas hoy", resumen.size());

        } catch (SQLException e) {
            LOGGER.log(Level.SEVERE, "[ERROR] Fallo al generar resumen agrupado por app", e);
        }

        return resumen;
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
        //GROUP BY nombre_actividad, category = categoria -- Nota: Ajustado sintaxis GROUP BY estándar

        try (Connection conn = DatabaseConnection.getConnection(); PreparedStatement stmt = conn.prepareStatement(sql)) {

            stmt.setString(1, usuario);
            stmt.setInt(2, limite);
            try (ResultSet rs = stmt.executeQuery()) {
                while (rs.next()) {
                    registros.add(Registro.builder()
                            .nombreActividad(rs.getString("nombre_actividad"))
                            .duracionSeg(rs.getLong("total_seg"))
                            .categoria(rs.getString("categoria"))
                            .build());
                }
            }
        } catch (SQLException e) {
            LOGGER.log(Level.SEVERE, "[ERROR] Fallo al obtener ranking de distracciones", e);
        }
        return registros;
    }

    @Override
    public List<Registro> obtenerActividadHoy(String usuario) {
        List<Registro> registros = new ArrayList<>();
        String sql = """
            SELECT id, usuario_sistema, nombre_actividad, categoria, detalle, duracion_seg, fecha_registro 
            FROM registros_actividad 
            WHERE usuario_sistema = ? 
            AND DATE(fecha_registro) = CURRENT_DATE
            ORDER BY fecha_registro DESC
            """;

        try (Connection conn = DatabaseConnection.getConnection(); PreparedStatement stmt = conn.prepareStatement(sql)) {

            stmt.setString(1, usuario);
            try (ResultSet rs = stmt.executeQuery()) {
                while (rs.next()) {
                    registros.add(mapearRegistro(rs));
                }
            }

        } catch (SQLException e) {
            LOGGER.log(Level.SEVERE, "[ERROR] Fallo al obtener la actividad de hoy", e);
        }

        return registros;
    }

    @Override
    public List<Registro> obtenerBloqueosHoy(String usuario) {
        List<Registro> registros = new ArrayList<>();
        String sql = """
            SELECT id, usuario_sistema, nombre_actividad, categoria, detalle, duracion_seg, fecha_registro 
            FROM registros_actividad 
            WHERE usuario_sistema = ? AND detalle LIKE '%Blocked%'
            AND DATE(fecha_registro) = CURRENT_DATE
            ORDER BY fecha_registro DESC
            """;

        try (Connection conn = DatabaseConnection.getConnection(); PreparedStatement stmt = conn.prepareStatement(sql)) {

            stmt.setString(1, usuario);
            try (ResultSet rs = stmt.executeQuery()) {
                while (rs.next()) {
                    registros.add(mapearRegistro(rs));
                }
            }
        } catch (SQLException e) {
            LOGGER.log(Level.SEVERE, "[ERROR] Fallo al obtener bloqueos de hoy", e);
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
            try (ResultSet rs = stmt.executeQuery()) {
                while (rs.next()) {
                    registros.add(Registro.builder()
                            .nombreActividad(rs.getString("nombre_actividad"))
                            .duracionSeg(rs.getLong("total_seg"))
                            .categoria(rs.getString("categoria"))
                            .build());
                }
            }
        } catch (SQLException e) {
            LOGGER.log(Level.SEVERE, "[ERROR] Fallo al obtener ranking de trabajo", e);
        }
        return registros;
    }

    @Override
    public List<Registro> obtenerHistorialCompleto() {
        List<Registro> historial = new ArrayList<>();
        String sql = """
            SELECT id, usuario_sistema, nombre_actividad, categoria, detalle, duracion_seg, fecha_registro 
            FROM registros_actividad 
            ORDER BY fecha_registro DESC
            """;

        try (Connection conn = DatabaseConnection.getConnection(); PreparedStatement stmt = conn.prepareStatement(sql); ResultSet rs = stmt.executeQuery()) {

            while (rs.next()) {
                historial.add(mapearRegistro(rs));
            }

        } catch (SQLException e) {
            LOGGER.log(Level.SEVERE, "[ERROR] Fallo crítico al obtener el historial completo de PostgreSQL", e);
        }

        return historial;
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
        if (valor == null) {
            return null;
        }
        return valor.length() > maximo ? valor.substring(0, maximo) : valor;
    }
}
