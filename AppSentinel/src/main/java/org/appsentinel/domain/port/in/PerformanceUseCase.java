/*
 * Click nbfs://nbhost/SystemFileSystem/Templates/Licenses/license-default.txt to change this license
 * Click nbfs://nbhost/SystemFileSystem/Templates/Classes/Class.java to edit this template
 */
package org.appsentinel.domain.port.in;

/**
 *
 * @author DAW1
 */
import org.appsentinel.domain.model.SystemMetrics;

public interface PerformanceUseCase {
    SystemMetrics getLatestMetrics();
}