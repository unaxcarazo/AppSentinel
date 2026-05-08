/*
 * Click nbfs://nbhost/SystemFileSystem/Templates/Licenses/license-default.txt to change this license
 * Click nbfs://nbhost/SystemFileSystem/Templates/Classes/Class.java to edit this template
 */
package org.appsentinel.model;

import java.time.LocalDateTime;
import lombok.AllArgsConstructor;
import lombok.Getter;

/**
 *
 * @author DAW1
 */
@Getter 
@AllArgsConstructor
public class Registro {
    private String        nombre;
    private long          duracion;
    private String        url;
    private LocalDateTime fecha;
    private boolean       esDistraccion;
}
    

