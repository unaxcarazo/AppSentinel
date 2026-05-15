package org.appsentinel.infrastructure.config;

import java.io.IOException;
import java.io.InputStream;
import java.util.Properties;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * AppConfig: Central de configuración.
 * Lee db.properties del classpath. Si no existe, usa valores por defecto.
 * - Logging estructurado con java.util.logging.Logger.
 * - Validación defensiva de parseo numérico con mensajes descriptivos.
 * - Clamp de valores mínimos para prevenir configuración semánticamente inválida.
 */
public class AppConfig {
    
    private static final Logger LOGGER = Logger.getLogger(AppConfig.class.getName());
    private static final Properties props = new Properties();
    
    static {
        try (InputStream is = AppConfig.class.getClassLoader()
                .getResourceAsStream("db.properties")) {
            if (is != null) {
                props.load(is);
                LOGGER.log(Level.INFO, "[CONFIG] db.properties cargado correctamente");
            } else {
                LOGGER.log(Level.WARNING, "[CONFIG] No se encontró db.properties, usando valores por defecto");
            }
        } catch (IOException e) {
            LOGGER.log(Level.SEVERE, "[CONFIG] Error leyendo db.properties: {0}", e.getMessage());
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
        return parsearEnteroPositivo("tiempo.aviso.preventivo", 120);
    }
    
    public static int getSegundosBloqueoSesion() {
        return parsearEnteroPositivo("tiempo.bloqueo.sesion", 600);
    }
    
    public static int getSegundosPausaReenfoque() {
        return parsearEnteroPositivo("tiempo.pausa.reenfoque", 1500);
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
        return parsearEnteroPositivo("scan.interval", 10);
    }
    
    // ============================================
    // USUARIO DEL SISTEMA
    // ============================================
    
    public static String getUsuarioSistema() {
        return System.getProperty("user.name");
    }
    
    // ============================================
    // UTILIDADES INTERNAS
    // ============================================
    
    /**
     * Parsea una propiedad como entero positivo con defensa contra valores corruptos.
     * Si el valor no es numérico o es menor que 1, retorna el default y loguea el error.
     */
    private static int parsearEnteroPositivo(String clave, int valorPorDefecto) {
        String raw = props.getProperty(clave);
        if (raw == null || raw.isBlank()) {
            return valorPorDefecto;
        }
        
        try {
            int valor = Integer.parseInt(raw.trim());
            if (valor < 1) {
                LOGGER.log(Level.WARNING, 
                    "[CONFIG] {0}={1} es inválido (mínimo 1). Usando default: {2}", 
                    new Object[]{clave, raw, valorPorDefecto});
                return valorPorDefecto;
            }
            return valor;
        } catch (NumberFormatException e) {
            LOGGER.log(Level.SEVERE, 
                "[CONFIG] {0}='{1}' no es numérico. Usando default: {2}", 
                new Object[]{clave, raw, valorPorDefecto});
            return valorPorDefecto;
        }
    }
}