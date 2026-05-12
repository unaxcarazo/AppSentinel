package org.appsentinel.infrastructure.config;

import java.io.IOException;
import java.io.InputStream;
import java.util.Properties;

/**
 * AppConfig: Central de configuración.
 * Lee db.properties del classpath. Si no existe, usa valores por defecto.
 */
public class AppConfig {
    
    private static final Properties props = new Properties();
    
    static {
        try (InputStream is = AppConfig.class.getClassLoader()
                .getResourceAsStream("db.properties")) {
            if (is != null) {
                props.load(is);
                System.out.println("Configuración cargada desde db.properties");
            } else {
                System.err.println("No se encontró db.properties, usando valores por defecto");
            }
        } catch (IOException e) {
            System.err.println("Error leyendo db.properties: " + e.getMessage());
        }
    }
    
    // ============================================
    // BASE DE DATOS
    // ============================================
    
    public static String getDbUrl() {
        return props.getProperty("db.url", "jdbc:postgresql://localhost:5432/appsentinel");
    }
    
    public static String getDbUser() {
        return props.getProperty("db.user", "postgres");
    }
    
    public static String getDbPassword() {
        return props.getProperty("db.password", "");
    }
    
    // ============================================
    // TIEMPOS DE NIVELES (en segundos)
    // ============================================
    
    public static int getSegundosAvisoPreventivo() {
        return Integer.parseInt(props.getProperty("tiempo.aviso.preventivo", "120"));
    }
    
    public static int getSegundosBloqueoSesion() {
        return Integer.parseInt(props.getProperty("tiempo.bloqueo.sesion", "600"));
    }
    
    public static int getSegundosPausaReenfoque() {
        return Integer.parseInt(props.getProperty("tiempo.pausa.reenfoque", "1500"));
    }
    
    // ============================================
    // MODO ESTRICTO
    // ============================================
    
    public static boolean isModoEstricto() {
        return Boolean.parseBoolean(props.getProperty("modo.estricto", "false"));
    }
    
    // ============================================
    // ESCÁNER
    // ============================================
    
    public static int getEscaneoIntervaloSegundos() {
        return Integer.parseInt(props.getProperty("scan.interval", "10"));
    }
    
    // ============================================
    // USUARIO DEL SISTEMA
    // ============================================
    
    public static String getUsuarioSistema() {
        return System.getProperty("user.name");
    }
}