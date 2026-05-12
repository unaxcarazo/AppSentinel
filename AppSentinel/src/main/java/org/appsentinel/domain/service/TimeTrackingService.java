package org.appsentinel.domain.service;

import org.appsentinel.domain.model.Registro;
import org.appsentinel.domain.port.in.BrowserEventPort;
import org.appsentinel.domain.port.in.MonitorPort;
import org.appsentinel.domain.port.out.KillerPort;
import org.appsentinel.domain.port.out.NotificacionPort;
import org.appsentinel.domain.port.out.RegistroRepositoryPort;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.Map;
import java.util.concurrent.*;

public class TimeTrackingService implements MonitorPort, BrowserEventPort {

    // ── Dependencias ──────────────────────────────────────────────
    private final DistractionDetector    detector;
    private final RegistroRepositoryPort repository;
    private final NotificacionPort       notificacion;
    private KillerPort             killer; // Quitamos 'final' para que funcione el setter

    // ── Configuración (viene por constructor, no de AppConfig) ────
    private final int     segundosAviso;
    private final int     segundosBloqueo;
    private final int     segundosPausa;
    private final boolean modoEstricto;
    private final String  usuario;

    // ── Estado interno ────────────────────────────────────────────
    private static final int INICIADA = 0, AVISO = 1, BLOQUEO = 2, PAUSA = 3;

    private final Map<String, SeguimientoActividad> activas =
            new ConcurrentHashMap<>();
    private final Map<String, Long> acumulado =
            new ConcurrentHashMap<>();
    private final ScheduledExecutorService scheduler =
            Executors.newSingleThreadScheduledExecutor();

    // ── Constructor ───────────────────────────────────────────────
    public TimeTrackingService(DistractionDetector    detector,
                               RegistroRepositoryPort repository,
                               NotificacionPort       notificacion,
                               KillerPort             killer,
                               int                    segundosAviso,
                               int                    segundosBloqueo,
                               int                    segundosPausa,
                               boolean                modoEstricto,
                               String                 usuario) {
        this.detector        = detector;
        this.repository      = repository;
        this.notificacion    = notificacion;
        this.killer          = killer;
        this.segundosAviso   = segundosAviso;
        this.segundosBloqueo = segundosBloqueo;
        this.segundosPausa   = segundosPausa;
        this.modoEstricto    = modoEstricto;
        this.usuario         = usuario;

        scheduler.scheduleAtFixedRate(
            this::verificarLimites, 10, 10, TimeUnit.SECONDS
        );

        System.out.println("TimeTrackingService iniciado — usuario: " + usuario);
        System.out.println("Modo: " + (modoEstricto ? "ESTRICTO" : "NORMAL"));
    }

    // ── Puertos de entrada ────────────────────────────────────────

    @Override
    public void reportarActividadSistema(String proceso, String titulo) {
        procesarActividad(
            "SYS|" + proceso,
            proceso,
            detector.clasificar(proceso),
            titulo
        );
    }

    @Override
    public void reportarEventoNavegador(String url, String titulo) {
        procesarActividad(
            "WEB|" + url,
            "Web: " + (titulo != null ? titulo : url),
            detector.clasificarUrl(url),
            url
        );
    }

    // ── Lógica central ────────────────────────────────────────────

    private synchronized void procesarActividad(String clave, String nombre,
                                                String categoria, String detalle) {
        if (!activas.containsKey(clave)) {
            persistirYLimpiar();
            activas.put(clave,
                new SeguimientoActividad(nombre, detalle, categoria,
                                         LocalDateTime.now()));
            acumulado.putIfAbsent(clave, 0L);
            System.out.println("[" + categoria + "] " + nombre);
        }
    }

    private synchronized void persistirYLimpiar() {
        activas.forEach((clave, seg) -> {
            long segs  = Duration.between(seg.inicio, LocalDateTime.now())
                                 .getSeconds();
            long total = acumulado.getOrDefault(clave, 0L) + segs;
            acumulado.put(clave, total);

            if (total > 5) {
                repository.guardar(new Registro(
                    null, usuario, seg.nombre,
                    seg.categoria, seg.detalle,
                    total, LocalDateTime.now()
                ));
            }
        });
        activas.clear();
    }

    private synchronized void verificarLimites() {
        activas.forEach((clave, seg) -> {
            if (!"DISTRACCION".equals(seg.categoria)) return;

            long segs  = Duration.between(seg.inicio, LocalDateTime.now())
                                 .getSeconds();
            long total = acumulado.getOrDefault(clave, 0L) + segs;

            if (modoEstricto) aplicarModoEstricto(clave, seg, total);
            else               aplicarNiveles(clave, seg, total);
        });
    }

    private void aplicarModoEstricto(String clave,
                                     SeguimientoActividad seg, long total) {
        if (total >= segundosAviso && seg.estado < AVISO) {
            seg.estado = AVISO;
            notificacion.mostrarAlertaDistraccion(
                "MODO ESTRICTO — cierre en 10 s", seg.nombre);
        }
        if (total >= segundosAviso + 10 && seg.estado < PAUSA) {
            seg.estado = PAUSA;
            notificacion.mostrarAlertaBloqueo(seg.nombre, total);
            cerrar(clave, seg);
        }
    }

    private void aplicarNiveles(String clave,
                                SeguimientoActividad seg, long total) {
        if (total >= segundosPausa && seg.estado < PAUSA) {
            seg.estado = PAUSA;
            notificacion.mostrarAlertaBloqueo(seg.nombre, total);
            cerrar(clave, seg);
        } else if (total >= segundosBloqueo && seg.estado < BLOQUEO) {
            seg.estado = BLOQUEO;
            notificacion.mostrarAlertaBloqueo(seg.nombre, total);
        } else if (total >= segundosAviso && seg.estado < AVISO) {
            seg.estado = AVISO;
            notificacion.mostrarAlertaDistraccion(
                "Llevas " + fmt(total) + " en " + seg.nombre, seg.nombre);
        }
    }

    private void cerrar(String clave, SeguimientoActividad seg) {
        if (clave.startsWith("SYS|")) {
            killer.cerrarProceso(seg.nombre);
        }
    }

    public synchronized void finalizar() {
        scheduler.shutdown();
        persistirYLimpiar();
        System.out.println("Seguimiento finalizado — datos guardados.");
    }

    private String fmt(long s) {
        return (s / 60) + "m " + (s % 60) + "s";
    }
    
    // Setter corregido en su sitio
    public void setKiller(KillerPort killer) {
        this.killer = killer;
    }

    // ── Clase interna ─────────────────────────────────────────────

    private static class SeguimientoActividad {
        String        nombre, detalle, categoria;
        LocalDateTime inicio;
        int           estado = 0;

        SeguimientoActividad(String nombre, String detalle,
                             String categoria, LocalDateTime inicio) {
            this.nombre    = nombre;
            this.detalle   = detalle;
            this.categoria = categoria;
            this.inicio    = inicio;
        }
    }
}
