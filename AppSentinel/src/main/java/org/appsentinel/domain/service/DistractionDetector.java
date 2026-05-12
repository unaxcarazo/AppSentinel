package org.appsentinel.domain.service;

import org.appsentinel.domain.port.out.CategoriaRepositoryPort;

/**
 * DistractionDetector: Lógica pura de clasificación.
 * Consulta la BD a través del puerto — no tiene listas
 * en el código ni dependencias de infraestructura.
 */
public class DistractionDetector {

    private final CategoriaRepositoryPort categorias;

    public DistractionDetector(CategoriaRepositoryPort categorias) {
        this.categorias = categorias;
    }

    /**
     * Clasifica una app de escritorio por su nombre de proceso.
     * Ej: "discord.exe" → "DISTRACCION"
     */
    public String clasificar(String nombreApp) {
        if (nombreApp == null || nombreApp.isBlank()) return "NEUTRAL";
        return categorias.obtenerCategoria(nombreApp.toLowerCase());
    }

    /**
     * Clasifica una URL del navegador por su dominio.
     * Ej: "https://www.youtube.com/watch?v=..." → "DISTRACCION"
     */
    public String clasificarUrl(String url) {
        if (url == null || url.isBlank()) return "NEUTRAL";
        String dominio = extraerDominio(url);
        return categorias.obtenerCategoria(dominio);
    }

    /**
     * Permite a la UI reclasificar una app en tiempo de ejecución.
     */
    public void reclasificar(String nombreApp, String categoria) {
        categorias.guardarCategoria(nombreApp.toLowerCase(), categoria);
    }

    private String extraerDominio(String url) {
        return url.replaceAll("https?://(www\\.)?", "")
                  .replaceAll("/.*", "")
                  .toLowerCase();
    }
}