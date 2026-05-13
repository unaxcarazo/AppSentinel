package org.appsentinel.domain.model;

/**
 * Utilidad de dominio: taxonomía de clasificación de actividades.
 * Centraliza las etiquetas para evitar strings dispersos
 * en adaptadores, servicios y controladores.
 */
public final class Categoria {

    private Categoria() {
        
    }

    public static final String PRODUCTIVO = "PRODUCTIVO";
    public static final String NEUTRAL = "NEUTRAL";
    public static final String DISTRACCION = "DISTRACCION";
    public static final String SIN_CLASIFICAR = "SIN_CLASIFICAR";
}