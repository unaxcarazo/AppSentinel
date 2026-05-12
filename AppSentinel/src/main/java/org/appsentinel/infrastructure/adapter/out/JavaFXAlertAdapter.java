/*
 * Click nbfs://nbhost/SystemFileSystem/Templates/Licenses/license-default.txt to change this license
 * Click nbfs://nbhost/SystemFileSystem/Templates/Classes/Class.java to edit this template
 */
package org.appsentinel.infrastructure.adapter.out;

import org.appsentinel.domain.port.out.NotificacionPort;

/**
 *
 * @author DAW1
 */
public class JavaFXAlertAdapter implements NotificacionPort {

    @Override
    public void mostrarAlertaDistraccion(String mensaje, String nombreApp) {
        throw new UnsupportedOperationException("Not supported yet."); // Generated from nbfs://nbhost/SystemFileSystem/Templates/Classes/Code/GeneratedMethodBody
    }

    @Override
    public void mostrarAlertaBloqueo(String nombreApp, long tiempoExcedido) {
        throw new UnsupportedOperationException("Not supported yet."); // Generated from nbfs://nbhost/SystemFileSystem/Templates/Classes/Code/GeneratedMethodBody
    }
    
}
