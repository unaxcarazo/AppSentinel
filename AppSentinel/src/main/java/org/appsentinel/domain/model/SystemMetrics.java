/*
 * Click nbfs://nbhost/SystemFileSystem/Templates/Licenses/license-default.txt to change this license
 * Click nbfs://nbhost/SystemFileSystem/Templates/Classes/Class.java to edit this template
 */
package org.appsentinel.domain.model;

/**
 *
 * @author DAW1
 */
import java.util.List;

public record SystemMetrics(
    double cpuTotalUsage,
    double cpuFrequencyGHz,
    int logicalCores,
    double getRamUsagePercent,
    double ramUsedGB,
    double ramTotalGB,
    double downloadMBps,
    double uploadMBps,
    List<ProcesoInfo> topProcesses
) {}
