package org.appsentinel.infrastructure.adapter.out;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.appsentinel.domain.port.out.ConfiguracionPort;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.util.Collections;
import java.util.HashSet;
import java.util.Set;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * JsonConfigAdapter: Adaptador de SALIDA que implementa ConfiguracionPort.
 *
 * FIX 3.1: Lee configuración del dominio desde config.json.
 * - Busca primero en el directorio de trabajo (fuera del JAR).
 * - Fallback al classpath (dentro del JAR) si no existe externo.
 * - Valida que el JSON contenga todas las claves obligatorias.
 * - Usa valores por defecto conservadores si alguna clave falta.
 *
 * ARQUITECTURA HEXAGONAL:
 * - El dominio (TimeTrackingService) consume ConfiguracionPort sin saber
 *   que la implementación lee JSON.
 * - AppConfig (infraestructura) se limita a propiedades de BD y paths.
 */
public class JsonConfigAdapter implements ConfiguracionPort {

    private static final Logger LOGGER = Logger.getLogger(JsonConfigAdapter.class.getName());
    private static final String CONFIG_FILE = "config.json";
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final JsonNode root;

    public JsonConfigAdapter() {
        this.root = cargarConfiguracion();
    }

    private JsonNode cargarConfiguracion() {
        // ESTRATEGIA: directorio de trabajo primero, classpath como fallback
        try {
            File externo = new File(CONFIG_FILE);
            if (externo.exists() && externo.isFile()) {
                LOGGER.log(Level.INFO, "[CONFIG] Cargando {0} desde directorio de trabajo", CONFIG_FILE);
                return MAPPER.readTree(externo);
            }
        } catch (IOException e) {
            LOGGER.log(Level.WARNING, "[CONFIG] Error leyendo {0} externo: {1}",
                new Object[]{CONFIG_FILE, e.getMessage()});
        }

        try (InputStream is = getClass().getClassLoader().getResourceAsStream(CONFIG_FILE)) {
            if (is != null) {
                LOGGER.log(Level.INFO, "[CONFIG] Cargando {0} desde classpath", CONFIG_FILE);
                return MAPPER.readTree(is);
            }
        } catch (Exception e) {
            LOGGER.log(Level.SEVERE, "[CONFIG] Error leyendo {0} del classpath: {1}",
                new Object[]{CONFIG_FILE, e.getMessage()});
        }

        LOGGER.log(Level.SEVERE, "[CONFIG] {0} no encontrado. Usando configuración vacía (valores por defecto).", CONFIG_FILE);
        return MAPPER.createObjectNode();
    }

    // =========================================================================
    // Implementación de ConfiguracionPort
    // =========================================================================

    @Override
    public Set<String> getListaNegra() {
        return leerStringSet("listaNegra");
    }

    @Override
    public Set<String> getListaBlanca() {
        return leerStringSet("listaBlanca");
    }

    @Override
    public Set<String> getUrlsNegras() {
        return leerStringSet("urlsNegras");
    }

    @Override
    public int getSegundosAvisoPreventivo() {
        return leerEnteroPositivo("segundosAvisoPreventivo", 120);
    }

    @Override
    public int getSegundosBloqueoSesion() {
        // MAPEO LEGACY: config.json antiguo usa "tiempoLimiteSegundos"
        // Si existe la clave legacy, se usa. Si no, se busca la nueva.
        JsonNode legacy = root.path("tiempoLimiteSegundos");
        if (!legacy.isMissingNode() && legacy.isInt()) {
            LOGGER.log(Level.INFO, "[CONFIG] Usando clave legacy 'tiempoLimiteSegundos' para bloqueo");
            return legacy.asInt();
        }
        return leerEnteroPositivo("segundosBloqueoSesion", 600);
    }

    @Override
    public int getSegundosPausaReenfoque() {
        return leerEnteroPositivo("segundosPausaReenfoque", 1500);
    }

    @Override
    public boolean isModoEstricto() {
        return leerBoolean("modoEstricto", false);
    }

    // =========================================================================
    // Helpers de lectura JSON defensiva
    // =========================================================================

    private Set<String> leerStringSet(String clave) {
        JsonNode nodo = root.path(clave);
        if (!nodo.isArray()) {
            LOGGER.log(Level.FINE, "[CONFIG] {0} no es array o no existe. Retornando set vacío.", clave);
            return Collections.emptySet();
        }

        Set<String> resultado = new HashSet<>();
        for (JsonNode elem : nodo) {
            if (elem.isTextual()) {
                resultado.add(elem.asText().toLowerCase().trim());
            }
        }
        return Collections.unmodifiableSet(resultado);
    }

    private int leerEnteroPositivo(String clave, int porDefecto) {
        JsonNode nodo = root.path(clave);
        if (nodo.isMissingNode() || !nodo.isInt()) {
            LOGGER.log(Level.WARNING, "[CONFIG] {0} no encontrado o no es entero. Usando default: {1}",
                new Object[]{clave, porDefecto});
            return porDefecto;
        }
        int valor = nodo.asInt();
        if (valor < 1) {
            LOGGER.log(Level.WARNING, "[CONFIG] {0}={1} es inválido (mínimo 1). Usando default: {2}",
                new Object[]{clave, valor, porDefecto});
            return porDefecto;
        }
        return valor;
    }

    private boolean leerBoolean(String clave, boolean porDefecto) {
        JsonNode nodo = root.path(clave);
        if (nodo.isMissingNode() || !nodo.isBoolean()) {
            LOGGER.log(Level.FINE, "[CONFIG] {0} no encontrado o no es boolean. Usando default: {1}",
                new Object[]{clave, porDefecto});
            return porDefecto;
        }
        return nodo.asBoolean();
    }
}