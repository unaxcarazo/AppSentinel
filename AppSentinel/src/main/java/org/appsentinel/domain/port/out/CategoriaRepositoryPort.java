package org.appsentinel.domain.port.out;

import java.util.List;

public interface CategoriaRepositoryPort {
    
    String obtenerCategoria(String nombreApp);
    void guardarCategoria(String nombreApp, String categoria);
    List<String> obtenerAppsNoClasificadas();
}