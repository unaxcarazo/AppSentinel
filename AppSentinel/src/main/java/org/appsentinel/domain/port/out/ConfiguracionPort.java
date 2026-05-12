package org.appsentinel.domain.port.out;

import java.util.Set;

/**
 * Puerto de salida para configuración.
 * El dominio pregunta qué apps son distracciones
 * sin saber si vienen de un JSON, BD o properties.
 */
public interface ConfiguracionPort {
    Set<String> getListaNegra();
    Set<String> getListaBlanca();
    Set<String> getUrlsNegras();
    int getSegundosAvisoPreventivo();
    int getSegundosBloqueoSesion();
    int getSegundosPausaReenfoque();
    boolean isModoEstricto();
}