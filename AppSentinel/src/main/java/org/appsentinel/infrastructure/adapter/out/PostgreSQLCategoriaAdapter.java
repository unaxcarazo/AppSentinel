package org.appsentinel.infrastructure.adapter.out;

import org.appsentinel.domain.port.out.CategoriaRepositoryPort;
import org.appsentinel.infrastructure.db.DatabaseConnection;

import java.sql.*;
import java.util.ArrayList;
import java.util.List;

/**
 * Implementa CategoriaRepositoryPort con JDBC directo a PostgreSQL.
 * Tabla: categorias_app (nombre_app, categoria, descripcion)
 */
public class PostgreSQLCategoriaAdapter implements CategoriaRepositoryPort {

    @Override
    public String obtenerCategoria(String nombreApp) {
        String sql = """
            SELECT categoria FROM categorias_app
            WHERE nombre_app = ?
            """;

        try (Connection conn = DatabaseConnection.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {

            ps.setString(1, nombreApp);
            ResultSet rs = ps.executeQuery();

            if (rs.next()) {
                return rs.getString("categoria");
            }

            // No existe → insertar como NEUTRAL para que aparezca en la UI
            insertarNeutral(nombreApp);
            return "NEUTRAL";

        } catch (SQLException e) {
            System.err.println("Error al obtener categoría: " + e.getMessage());
            return "NEUTRAL";
        }
    }

    @Override
    public void guardarCategoria(String nombreApp, String categoria) {
        String sql = """
            INSERT INTO categorias_app (nombre_app, categoria)
            VALUES (?, ?)
            ON CONFLICT (nombre_app)
            DO UPDATE SET categoria = EXCLUDED.categoria
            """;

        try (Connection conn = DatabaseConnection.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {

            ps.setString(1, nombreApp);
            ps.setString(2, categoria);
            ps.executeUpdate();

        } catch (SQLException e) {
            System.err.println("Error al guardar categoría: " + e.getMessage());
        }
    }

    @Override
    public List<String> obtenerAppsNoClasificadas() {
        List<String> apps = new ArrayList<>();
        String sql = """
            SELECT nombre_app FROM categorias_app
            WHERE categoria = 'NEUTRAL'
            ORDER BY nombre_app
            """;

        try (Connection conn = DatabaseConnection.getConnection();
             Statement stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery(sql)) {

            while (rs.next()) {
                apps.add(rs.getString("nombre_app"));
            }

        } catch (SQLException e) {
            System.err.println("Error al obtener apps sin clasificar: " + e.getMessage());
        }

        return apps;
    }

    /**
     * Inserta una app nueva como NEUTRAL para que el usuario
     * pueda clasificarla desde la UI.
     */
    private void insertarNeutral(String nombreApp) {
        String sql = """
            INSERT INTO categorias_app (nombre_app, categoria)
            VALUES (?, 'NEUTRAL')
            ON CONFLICT (nombre_app) DO NOTHING
            """;

        try (Connection conn = DatabaseConnection.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {

            ps.setString(1, nombreApp);
            ps.executeUpdate();

        } catch (SQLException e) {
            System.err.println("Error al insertar app neutral: " + e.getMessage());
        }
    }
}