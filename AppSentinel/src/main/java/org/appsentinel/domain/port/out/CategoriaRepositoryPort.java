package org.appsentinel.domain.port.out;

import java.util.List;

public interface CategoriaRepositoryPort {
    
    String obtenerCategoria(String nombreApp);
    
    void guardarCategoria(String nombreApp, String categoria);
    
    List<String> obtenerAppsSinClasificar();
    
    /*
     * NUEVO: Devuelve apps filtradas por categoría específica.
     * Usado por la capa de presentación para cargar listas segmentadas
     * (ej: listaTrabajo = obtenerAppsPorCategoria(Categoria.PRODUCTIVO)).
     */
    List<String> obtenerAppsPorCategoria(String categoria);
}