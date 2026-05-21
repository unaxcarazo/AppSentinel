package org.appsentinel.infrastructure.bootstrap;

import org.appsentinel.domain.port.out.CategoriaRepositoryPort;
import org.appsentinel.domain.port.out.FocoActivoPort;
import org.appsentinel.domain.port.out.KillerPort;
import org.appsentinel.domain.port.out.NotificacionPort;
import org.appsentinel.domain.port.out.RegistroRepositoryPort;
import org.appsentinel.domain.port.out.RendimientoSistemaPort;
import org.appsentinel.domain.service.TimeTrackingService;

/**
 * Contenedor inmutable del grafo de dependencias ensamblado.
 *
 * EXPONE PUERTOS, NO ADAPTADORES CONCRETOS:
 * - tracking: dominio
 * - repositorio: persistencia de registros
 * - categorias: clasificación y auto-descubrimiento
 * - notificacion: alertas visuales + callbacks en tiempo real
 * - killer: cierre de procesos/pestañas
 * - rendimiento: métricas de CPU y RAM en tiempo real (OSHI)
 * - focoActivo: puerto de consulta del foco actual (FIX 2.2: domain.port.out)
 *
 * ARQUITECTURA:
 * TimeTrackingService implementa FocoActivoPort (puerto de salida).
 * La UI consume FocoActivoPort sin saber que detrás está TimeTrackingService.
 * El polimorfismo por interfaces mantiene la capa de presentación desacoplada.
 */
public record AppContext(
    TimeTrackingService tracking,
    RegistroRepositoryPort repositorio,
    CategoriaRepositoryPort categorias,
    NotificacionPort notificacion,
    KillerPort killer,
    RendimientoSistemaPort rendimiento,
    FocoActivoPort focoActivo
) {}