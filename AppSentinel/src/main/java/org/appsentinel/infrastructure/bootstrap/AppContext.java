package org.appsentinel.infrastructure.bootstrap;

import org.appsentinel.domain.port.out.FocoActivoPort;
import org.appsentinel.domain.port.out.KillerPort;
import org.appsentinel.domain.port.out.NotificacionPort;
import org.appsentinel.domain.port.out.RendimientoSistemaPort;
import org.appsentinel.domain.service.MantenimientoDiarioService;
import org.appsentinel.domain.service.ReporteDiarioService;
import org.appsentinel.domain.service.TimeTrackingService;
import org.appsentinel.infrastructure.adapter.out.PostgreSQLCategoriaAdapter;
import org.appsentinel.infrastructure.adapter.out.PostgreSQLRepositoryAdapter;

/**
 * AppContext: Record inmutable que expone los componentes cableados por AppWiring.
 *
 * FIX: Añadido ReporteDiarioService para evitar que AppSentinel instancie
 * infraestructura directamente (new PostgreSQLRepositoryAdapter, new HtmlReportAdapter).
 * AppSentinel consume servicios del contexto, no crea adaptadores.
 */
public record AppContext(
    TimeTrackingService tracking,
    PostgreSQLRepositoryAdapter repo,
    PostgreSQLCategoriaAdapter categorias,
    NotificacionPort notificacion,
    KillerPort killer,
    RendimientoSistemaPort rendimiento,
    FocoActivoPort focoActivo,
    MantenimientoDiarioService mantenimiento,
    ReporteDiarioService reporteService
) {}