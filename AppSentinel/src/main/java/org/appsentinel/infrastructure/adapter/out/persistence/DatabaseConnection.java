package org.appsentinel.infrastructure.adapter.out.persistence;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import org.appsentinel.infrastructure.config.AppConfig;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * DatabaseConnection: Pool de conexiones optimizado con HikariCP.
 * Thread-safe. Diseñado para entornos concurrentes de alta disponibilidad.
 */
public class DatabaseConnection {

    private static final Logger LOGGER = Logger.getLogger(DatabaseConnection.class.getName());
    private static final HikariDataSource dataSource;

    static {
        try {
            HikariConfig config = new HikariConfig();
            config.setJdbcUrl(AppConfig.getDbUrl());
            config.setUsername(AppConfig.getDbUser());
            config.setPassword(AppConfig.getDbPassword());
            
            // Optimizaciones del pool para entornos de escritorio
            config.setMaximumPoolSize(10);
            config.setMinimumIdle(2);
            config.setConnectionTimeout(30000); // 30 segundos
            config.setIdleTimeout(600000);      // 10 minutos
            config.setMaxLifetime(1800000);     // 30 minutos
             
            // Los drivers modernos de PostgreSQL usan JDBC4 Connection.isValid() automáticamente.

            dataSource = new HikariDataSource(config);
            LOGGER.log(Level.INFO, "Pool de conexiones HikariCP iniciado con éxito.");
        } catch (Exception e) {
            LOGGER.log(Level.SEVERE, "Fallo crítico al inicializar el pool HikariCP", e);
            throw new ExceptionInInitializerError(e);
        }
    }

    /*
      Obtiene una conexión activa del pool. 
      Debe cerrarse siempre usando un bloque try-with-resources para devolverla al pool.
     */
    public static Connection getConnection() throws SQLException {
        return dataSource.getConnection();
    }

    /*
      Libera y cierra todos los recursos del pool al detener la aplicación.
     */
    public static void cerrarPool() {
        if (dataSource != null && !dataSource.isClosed()) {
            dataSource.close();
            LOGGER.log(Level.INFO, "Pool de conexiones HikariCP cerrado correctamente.");
        }
    }
}
