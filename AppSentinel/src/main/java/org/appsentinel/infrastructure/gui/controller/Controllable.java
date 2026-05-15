package org.appsentinel.infrastructure.gui.controller;

import org.appsentinel.infrastructure.config.AppContext;

/*
 * Obliga a todos los controladores a tener la misma puerta de entrada.
 */
public interface Controllable {
    /*
     * Recibe el contexto global con todos los puertos del sistema.
     */
    void init(AppContext ctx);
}
