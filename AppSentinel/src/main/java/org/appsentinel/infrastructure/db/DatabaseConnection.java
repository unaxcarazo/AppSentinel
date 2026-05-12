package org.appsentinel.infrastructure.db;

import org.appsentinel.infrastructure.config.AppConfig;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;

public class DatabaseConnection {

    private static Connection conexion;

    static {
        try { Class.forName("org.postgresql.Driver"); }
        catch (ClassNotFoundException e) {
            throw new RuntimeException("Driver PostgreSQL no encontrado", e);
        }
    }

    public static synchronized Connection getConnection() throws SQLException {
        if (conexion == null || conexion.isClosed()) {
            conexion = DriverManager.getConnection(
                AppConfig.getDbUrl(),
                AppConfig.getDbUser(),
                AppConfig.getDbPassword()
            );
            System.out.println("Conexión PostgreSQL establecida");
        }
        return conexion;
    }
}