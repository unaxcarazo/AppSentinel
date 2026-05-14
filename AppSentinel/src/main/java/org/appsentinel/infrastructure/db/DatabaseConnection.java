package org.appsentinel.infrastructure.db;

import org.appsentinel.infrastructure.config.AppConfig;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;

public class DatabaseConnection {

    private static Connection conexion;

    static {
        try {
            Class.forName("org.postgresql.Driver");
        } catch (ClassNotFoundException e) {
            throw new RuntimeException("Driver PostgreSQL no encontrado", e);
        }
    }

    public static synchronized Connection getConnection() throws SQLException {
        // Si la conexión no existe o se ha cerrado, creamos una nueva
        if (conexion == null || conexion.isClosed()) {
            try {
                // Configuración directa basada en tu pgAdmin
                String url = "jdbc:postgresql://localhost:5432/sentuser";
                String usuario = "postgres";
                String password = "tiger";

                conexion = DriverManager.getConnection(url, usuario, password);
                System.out.println("✅ Conexión exitosa a PostgreSQL: Base de datos 'sentuser'");

            } catch (SQLException e) {
                System.err.println("❌ Error al conectar a PostgreSQL: " + e.getMessage());
                throw e;
            }
        }
        return conexion;
    }

    /**
     * Método opcional para cerrar la conexión manualmente si fuera necesario.
     */
    public static void closeConnection() {
        if (conexion != null) {
            try {
                conexion.close();
                System.out.println("Conexión cerrada correctamente.");
            } catch (SQLException e) {
                System.err.println("Error al cerrar la conexión: " + e.getMessage());
            }
        }
    }
  }
    
