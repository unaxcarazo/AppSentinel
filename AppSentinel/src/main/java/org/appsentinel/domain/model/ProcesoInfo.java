/*
 * Click nbfs://nbhost/SystemFileSystem/Templates/Licenses/license-default.txt to change this license
 * Click nbfs://nbhost/SystemFileSystem/Templates/Classes/Class.java to edit this template
 */
package org.appsentinel.domain.model;

/**
 *
 * @author DAW1
 */
public record ProcesoInfo(
    String nombre,
    String status,
    double cpuUsage,
    double memoryUsage,
    long upTime
) {}
