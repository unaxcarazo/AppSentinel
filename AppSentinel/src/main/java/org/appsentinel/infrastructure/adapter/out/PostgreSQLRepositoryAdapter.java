package org.appsentinel.infrastructure.adapter.out;

import org.appsentinel.domain.model.Registro;
import org.appsentinel.domain.model.ResumenActividad;
import org.appsentinel.domain.port.out.RegistroRepositoryPort;
import org.appsentinel.infrastructure.adapter.out.persistence.DatabaseConnection;

import java.sql.*;
import java.time.LocalDate;
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
 * ARQUITECTURA HEXAGONAL — Responsabilidad: implementar el contrato del Port
 * usando tecnología PostgreSQL. Contiene toda la lógica de batch,
 * transacciones, y optimizaciones JDBC. El dominio no sabe que existe
 * PostgreSQL.
 *
 * FIX UPSERT: Implementa guardarOActualizar() con INSERT ... ON CONFLICT DO
 * UPDATE para acumular duración en una sola fila por app por día.
 *
 * FIX BATCH: guardarBatch() y guardarOActualizarBatch() usan executeBatch()
 * nativo de JDBC en transacción atómica. No hay defaults en el Port, toda la
 * lógica está aquí.
 *
 * FIX 2.3 (SQL Sargable): Reemplaza DATE(fecha_registro) = CURRENT_DATE por
 * rango de timestamp que aprovecha índices B-Tree.
 *
 * ÍNDICES REQUERIDOS: CREATE INDEX idx_registros_user_fecha ON
 * registros_actividad(usuario_sistema, fecha_registro); CREATE UNIQUE INDEX
 * idx_registro_unico_dia ON registros_actividad( usuario_sistema,
 * nombre_actividad, categoria, DATE(fecha_registro) );
 */
public class PostgreSQLRepositoryAdapter implements RegistroRepositoryPort {

    private static final Logger LOGGER = Logger.getLogger(PostgreSQLRepositoryAdapter.class.getName());

    private static final int LIMITE_NOMBRE_ACTIVIDAD = 255;
    private static final int LIMITE_CATEGORIA = 100;
    private static final int LIMITE_DETALLE = 500;
    private static final int LIMITE_USUARIO = 100;

    // -------------------------------------------------------------------------
    // INSERT individual (legacy, para compatibilidad)
    // -------------------------------------------------------------------------
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

    // -------------------------------------------------------------------------
    // BATCH INSERT (executeBatch nativo de JDBC)
    // -------------------------------------------------------------------------
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

                int exitosos = contarExitosos(resultados);
                LOGGER.log(Level.INFO, "[PERSISTENCIA] Batch insert: {0}/{1} registros",
                        new Object[]{exitosos, registros.size()});
            }

        } catch (SQLException e) {
            LOGGER.log(Level.SEVERE, "[ERROR] Batch insert falló. Rollback...", e);
            rollbackSilencioso(conn);
        } finally {
            restaurarAutoCommit(conn);
        }
    }

    // -------------------------------------------------------------------------
    // UPSERT individual (INSERT ... ON CONFLICT DO UPDATE)
    // -------------------------------------------------------------------------
    @Override
    public void guardarOActualizar(Registro registro) {
        if (registro == null) {
            return;
        }

        String sql = """
            INSERT INTO registros_actividad
            (usuario_sistema, nombre_actividad, categoria, detalle, duracion_seg, fecha_registro)
            VALUES (?, ?, ?, ?, ?, ?)
            ON CONFLICT (usuario_sistema, nombre_actividad, categoria, DATE(fecha_registro))
            DO UPDATE SET
                duracion_seg = registros_actividad.duracion_seg + EXCLUDED.duracion_seg,
                fecha_registro = EXCLUDED.fecha_registro,
                detalle = EXCLUDED.detalle
            """;

        try (Connection conn = DatabaseConnection.getConnection(); PreparedStatement stmt = conn.prepareStatement(sql)) {

            setParams(stmt, registro);
            int filasAfectadas = stmt.executeUpdate();

            LOGGER.log(Level.FINE, "[UPSERT] {0} +{1}s (filas: {2})",
                    new Object[]{
                        truncar(registro.getNombreActividad(), 50),
                        registro.getDuracionSeg(),
                        filasAfectadas
                    });

        } catch (SQLException e) {
            LOGGER.log(Level.SEVERE,
                    "[ERROR] Upsert falló para {0}: {1}",
                    new Object[]{registro.getNombreActividad(), e.getMessage()});
        }
    }

    // -------------------------------------------------------------------------
    // BATCH UPSERT (executeBatch con ON CONFLICT)
    // -------------------------------------------------------------------------
    @Override
    public void guardarOActualizarBatch(List<Registro> registros) {
        if (registros == null || registros.isEmpty()) {
            return;
        }

        String sql = """
            INSERT INTO registros_actividad
            (usuario_sistema, nombre_actividad, categoria, detalle, duracion_seg, fecha_registro)
            VALUES (?, ?, ?, ?, ?, ?)
            ON CONFLICT (usuario_sistema, nombre_actividad, categoria, DATE(fecha_registro))
            DO UPDATE SET
                duracion_seg = registros_actividad.duracion_seg + EXCLUDED.duracion_seg,
                fecha_registro = EXCLUDED.fecha_registro,
                detalle = EXCLUDED.detalle
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

                int exitosos = contarExitosos(resultados);
                LOGGER.log(Level.INFO, "[UPSERT-BATCH] {0}/{1} registros acumulados",
                        new Object[]{exitosos, registros.size()});
            }

        } catch (SQLException e) {
            LOGGER.log(Level.SEVERE, "[ERROR] Batch upsert falló. Rollback...", e);
            rollbackSilencioso(conn);
        } finally {
            restaurarAutoCommit(conn);
        }
    }

    // -------------------------------------------------------------------------
    // Lecturas (sin cambios respecto a v2)
    // -------------------------------------------------------------------------
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
            LOGGER.log(Level.SEVERE, "[ERROR] Fallo al obtener historial diario", e);
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
            LOGGER.log(Level.SEVERE, "[ERROR] Fallo al filtrar por categoría", e);
        }

        return registros;
    }

    @Override
    public Map<String, ResumenActividad> obtenerResumenPorApp(String usuario) {
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

            LOGGER.log(Level.FINE, "[PERSISTENCIA] Resumen: {0} apps hoy", resumen.size());

        } catch (SQLException e) {
            LOGGER.log(Level.SEVERE, "[ERROR] Fallo al generar resumen", e);
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

    //PRUEBAS FECHAS =====================================================
    @Override
    public List<Registro> findByUsuario(String usuario) {
        List<Registro> registros = new ArrayList<>();
        String sql = "SELECT id, usuario_sistema, nombre_actividad, categoria, detalle, duracion_seg, fecha_registro " +
                 "FROM registros_actividad " +
                 "WHERE usuario_sistema = ?";

        try (Connection conn = DatabaseConnection.getConnection(); // Usa tu clase de conexión real
                 PreparedStatement stmt = conn.prepareStatement(sql)) {

            stmt.setString(1, usuario);

            try (ResultSet rs = stmt.executeQuery()) {
                while (rs.next()) {
                    registros.add(mapearRegistro(rs));
                }
            }
        } catch (SQLException e) {
            e.printStackTrace();
        }
        return registros;
    }

    @Override
    public List<Registro> findByUsuarioAndFecha(String usuario, LocalDate fecha) {
        List<Registro> registros = new ArrayList<>();
        // Usamos java.sql.Date para pasar el LocalDate a la query de PostgreSQL
        String sql = "SELECT id, usuario_sistema, nombre_actividad, categoria, detalle, duracion_seg, fecha_registro " +
             "FROM registros_actividad " +
             "WHERE usuario_sistema = ? AND CAST(fecha_registro AS DATE) = ?";

        try (Connection conn = DatabaseConnection.getConnection(); 
             PreparedStatement stmt = conn.prepareStatement(sql)) {

            stmt.setString(1, usuario);
            stmt.setDate(2, java.sql.Date.valueOf(fecha));

            try (ResultSet rs = stmt.executeQuery()) {
                while (rs.next()) {
                    // Aquí usas la misma lógica que ya tienes en tu método antiguo
                    // para mapear el ResultSet a tu objeto Registro y añadirlo a la lista
                    registros.add(mapearRegistro(rs));
                }
            }
        } catch (SQLException e) {
            e.printStackTrace(); // O tu sistema de logs habitual
        }
        return registros;
    }

    // -------------------------------------------------------------------------
    // Utilidades privadas
    // -------------------------------------------------------------------------
    private void setParams(PreparedStatement stmt, Registro reg) throws SQLException {
        stmt.setString(1, truncar(reg.getUsuarioSistema(), LIMITE_USUARIO));
        stmt.setString(2, truncar(reg.getNombreActividad(), LIMITE_NOMBRE_ACTIVIDAD));
        stmt.setString(3, truncar(reg.getCategoria(), LIMITE_CATEGORIA));
        stmt.setString(4, truncar(reg.getDetalle(), LIMITE_DETALLE));
        stmt.setLong(5, reg.getDuracionSeg());
        stmt.setTimestamp(6, Timestamp.valueOf(reg.getFechaRegistro()));
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

    private int contarExitosos(int[] resultados) {
        int exitosos = 0;
        for (int r : resultados) {
            if (r >= 0 || r == Statement.SUCCESS_NO_INFO) {
                exitosos++;
            }
        }
        return exitosos;
    }

    private void rollbackSilencioso(Connection conn) {
        if (conn != null) {
            try {
                conn.rollback();
            } catch (SQLException ex) {
                LOGGER.log(Level.SEVERE, "[ERROR] Rollback fallido", ex);
            }
        }
    }

    private void restaurarAutoCommit(Connection conn) {
        if (conn != null) {
            try {
                try (conn) {
                    conn.setAutoCommit(true);
                }
            } catch (SQLException e) {
                LOGGER.log(Level.WARNING, "[ERROR] Fallo restaurando auto-commit", e);
            }
        }
    }

    private String truncar(String valor, int maximo) {
        if (valor == null) {
            return null;
        }
        return valor.length() > maximo ? valor.substring(0, maximo) : valor;
    }
}
