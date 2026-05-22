package org.appsentinel.domain.service;

import org.appsentinel.domain.model.Categoria;
import org.appsentinel.domain.port.out.CategoriaRepositoryPort;

import java.util.regex.Pattern;

/**
 * DistractionDetector: Lógica pura de clasificación.
 * Optimizaciones industriales:
 * - Patterns compilados estáticamente (cero presión de GC en hot-path).
 * - Estrategia jerárquica para URLs: dominio exacto → dominio base (eTLD+1).
 * - Adaptación para tolerar retornos nulos del adaptador de infraestructura externa.
 */
public class DistractionDetector {

    private final CategoriaRepositoryPort categorias;

    private static final Pattern PROTOCOLO_WWW = Pattern.compile("https?://(www\\.)?");
    private static final Pattern PATH_QUERY = Pattern.compile("/.*");

    public DistractionDetector(CategoriaRepositoryPort categorias) {
        this.categorias = categorias;
    }

    /*
     * Clasifica una app de escritorio por su nombre de proceso.
     */
    public String clasificar(String nombreApp) {
        if (nombreApp == null || nombreApp.isBlank()) {
            return Categoria.NEUTRAL;
        }
        String categoria = categorias.obtenerCategoria(nombreApp.toLowerCase());
        
        // CORRECCIÓN: Si no existe en la BD (retorna null), se traduce a SIN_CLASIFICAR en el dominio
        return (categoria != null) ? categoria : Categoria.SIN_CLASIFICAR;
    }

    /*
     * Clasifica una URL del navegador por su dominio.
     * Estrategia jerárquica:
     *   1. Consulta dominio exacto (ej: music.youtube.com).
     *   2. Si no está clasificado o no existe, consulta dominio base (ej: youtube.com).
     */
    public String clasificarUrl(String url) {
        if (url == null || url.isBlank()) {
            return Categoria.NEUTRAL;
        }

        String dominioExacto = extraerDominio(url);
        String categoriaExacta = categorias.obtenerCategoria(dominioExacto);

        // Si el subdominio exacto existe y tiene una clasificación real, se respeta de inmediato
        if (categoriaExacta != null && !Categoria.SIN_CLASIFICAR.equals(categoriaExacta)) {
            return categoriaExacta;
        }

        // Caída jerárquica al dominio base (eTLD+1) si el exacto devolvió null o SIN_CLASIFICAR
        String dominioBase = extraerDominioBase(dominioExacto);
        if (!dominioExacto.equals(dominioBase)) {
            String categoriaBase = categorias.obtenerCategoria(dominioBase);
            if (categoriaBase != null) {
                return categoriaBase;
            }
        }

        // Si no se encuentra ningún registro en los pasos previos, el veredicto es SIN_CLASIFICAR
        return Categoria.SIN_CLASIFICAR;
    }

    /*
     * Permite a la UI reclasificar una app en tiempo de ejecución.
     */
    public void reclasificar(String nombreApp, String categoria) {
        categorias.guardarCategoria(nombreApp.toLowerCase(), categoria);
    }

    /**
     * Extrae el dominio completo de una URL (sin protocolo, www, path ni query).
     */
    private String extraerDominio(String url) {
        String sinProtocolo = PROTOCOLO_WWW.matcher(url).replaceAll("");
        return PATH_QUERY.matcher(sinProtocolo).replaceAll("").toLowerCase();
    }

    /**
     * Reduce un dominio a su forma base (eTLD+1).
     * Ej: music.youtube.com → youtube.com
     *     app.twitch.tv → twitch.tv
     */
    private String extraerDominioBase(String dominio) {
        int ultimoPunto = dominio.lastIndexOf('.');
        if (ultimoPunto <= 0) {
            return dominio;
        }

        int penultimoPunto = dominio.lastIndexOf('.', ultimoPunto - 1);
        if (penultimoPunto <= 0) {
            return dominio;
        }

        return dominio.substring(penultimoPunto + 1);
    }
}
