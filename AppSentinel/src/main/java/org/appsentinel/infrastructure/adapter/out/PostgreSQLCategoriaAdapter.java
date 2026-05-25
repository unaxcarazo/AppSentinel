package org.appsentinel.infrastructure.adapter.out;

import org.appsentinel.domain.model.Categoria;
import org.appsentinel.domain.port.out.CategoriaRepositoryPort;
import org.appsentinel.infrastructure.adapter.out.persistence.DatabaseConnection;

import java.sql.*;
import java.util.ArrayList;
import java.util.List;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * PostgreSQLCategoriaAdapter: Adaptador de SALIDA para persistencia de categorías.
 * 
 * Implementa auto-descubrimiento: cuando una app no existe en la BD,
 * la registra automáticamente como SIN_CLASIFICAR para que el usuario
 * la categorice posteriormente desde la interfaz gráfica.
 * 
 * Diseño fusionado:
 * - obtenerCategoria() es autónomo: consulta, y si no existe, registra y retorna SIN_CLASIFICAR.
 * - registrarNuevaAppDesconocida() es público para usos explícitos (UI, imports masivos, tests).
 * - Normalización centralizada en obtenerCategoria() para evitar inconsistencias de búsqueda.
 */
public class PostgreSQLCategoriaAdapter implements CategoriaRepositoryPort {

    private static final Logger LOGGER = Logger.getLogger(PostgreSQLCategoriaAdapter.class.getName());

    @Override
    public String obtenerCategoria(String nombreApp) {
        if (nombreApp == null || nombreApp.isBlank()) {
            return Categoria.SIN_CLASIFICAR;
        }

        String normalizado = nombreApp.toLowerCase().trim();
        String sql = "SELECT categoria FROM categorias_app WHERE nombre_app = ?";

        try (Connection conn = DatabaseConnection.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {

            ps.setString(1, normalizado);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    return rs.getString("categoria");
                }
            }

            // Auto-descubrimiento: no existe en BD → registrar y retornar SIN_CLASIFICAR
            registrarNuevaAppDesconocida(normalizado);
            return Categoria.SIN_CLASIFICAR;

        } catch (SQLException e) {
            LOGGER.log(Level.SEVERE, "[ERROR] Fallo al obtener categoría para: {0}", normalizado);
            return Categoria.SIN_CLASIFICAR;
        }
    }

    @Override
    public void guardarCategoria(String nombreApp, String categoria) {
        if (nombreApp == null || nombreApp.isBlank() || categoria == null || categoria.isBlank()) {
            LOGGER.log(Level.WARNING, "[GUARDAR] Parámetros inválidos: nombreApp={0}, categoria={1}", 
                new Object[]{nombreApp, categoria});
            return;
        }

        String sql = """
            INSERT INTO categorias_app (nombre_app, categoria)
            VALUES (?, ?)
            ON CONFLICT (nombre_app)
            DO UPDATE SET categoria = EXCLUDED.categoria
            """;

        try (Connection conn = DatabaseConnection.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {

            ps.setString(1, nombreApp.toLowerCase().trim());
            ps.setString(2, categoria);
            ps.executeUpdate();
            
            LOGGER.log(Level.INFO, "[CATEGORIA] Actualización de estado exitosa: {0} -> {1}", 
                new Object[]{nombreApp, categoria});

        } catch (SQLException e) {
            LOGGER.log(Level.SEVERE, "[ERROR] Fallo al guardar o actualizar la categoría en PostgreSQL", e);
        }
    }

    @Override
    public List<String> obtenerAppsSinClasificar() {
        List<String> apps = new ArrayList<>();
        String sql = """
            SELECT nombre_app FROM categorias_app
            WHERE categoria = ?
            ORDER BY nombre_app ASC
            """;

        try (Connection conn = DatabaseConnection.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {

            ps.setString(1, Categoria.SIN_CLASIFICAR);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    apps.add(rs.getString("nombre_app"));
                }
            }

        } catch (SQLException e) {
            LOGGER.log(Level.SEVERE, "[ERROR] Fallo al obtener listado de apps sin clasificar", e);
        }

        return apps;
    }

    /**
     * NUEVO: Consulta apps filtradas por categoría específica.
     * Usado por la capa de presentación para cargar listas segmentadas
     * (ej: listaTrabajo = obtenerAppsPorCategoria(Categoria.PRODUCTIVO)).
     */
    @Override
    public List<String> obtenerAppsPorCategoria(String categoria) {
        if (categoria == null || categoria.isBlank()) {
            LOGGER.log(Level.WARNING, "[CONSULTA] Categoría nula o vacía, retornando lista vacía");
            return List.of();
        }

        List<String> apps = new ArrayList<>();
        String sql = """
            SELECT nombre_app FROM categorias_app
            WHERE categoria = ?
            ORDER BY nombre_app ASC
            """;

        try (Connection conn = DatabaseConnection.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {

            ps.setString(1, categoria);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    apps.add(rs.getString("nombre_app"));
                }
            }

        } catch (SQLException e) {
            LOGGER.log(Level.SEVERE, "[ERROR] Fallo al obtener apps por categoría: {0}", categoria);
        }

        return apps;
    }

    /**
     * Inserción explícita de auto-descubrimiento.
     * 
     * Uso principal: invocado internamente por obtenerCategoria() cuando no encuentra registro.
     * Uso secundario: UI de reclasificación, imports masivos, scripts de migración, tests unitarios.
     * 
     * ON CONFLICT DO NOTHING blinda contra condiciones de carrera entre hilos concurrentes.
     */
    public void registrarNuevaAppDesconocida(String nombreApp) {
        if (nombreApp == null || nombreApp.isBlank()) {
            LOGGER.log(Level.FINE, "[REGISTRO] Ignorando app nula o vacía");
            return;
        }
        
        String sql = """
            INSERT INTO categorias_app (nombre_app, categoria)
            VALUES (?, ?)
            ON CONFLICT (nombre_app) DO NOTHING
            """;

        try (Connection conn = DatabaseConnection.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {

            ps.setString(1, nombreApp.toLowerCase().trim());
            ps.setString(2, Categoria.SIN_CLASIFICAR);
            int filas = ps.executeUpdate();
            
            if (filas > 0) {
                LOGGER.log(Level.INFO, "[AUTO-DESCUBRIMIENTO] Nueva app guardada en PostgreSQL: {0}", nombreApp);
            }

        } catch (SQLException e) {
            LOGGER.log(Level.WARNING, "[ERROR] Fallo al insertar la aplicación como SIN_CLASIFICAR", e);
        }
    }
   
}


