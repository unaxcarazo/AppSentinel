package org.appsentinel.domain.service;

import org.appsentinel.domain.model.Categoria;
import org.appsentinel.domain.model.Registro;
import org.appsentinel.domain.port.in.BrowserEventPort;
import org.appsentinel.domain.port.in.MonitorPort;
import org.appsentinel.domain.port.out.KillerPort;
import org.appsentinel.domain.port.out.NotificacionPort;
import org.appsentinel.domain.port.out.RegistroRepositoryPort;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.*;
import java.util.regex.Pattern;

/**
 * TimeTrackingService: Orquestador principal.
 * 
 * Correcciones industriales:
 * - Clave Web unificada por dominio (evita fugas de memoria por URL única).
 * - Patterns compilados estáticamente para extracción de dominio.
 * - Heartbeat por timestamp para detección de cierre manual.
 * - Constantes de dominio en lugar de literales.
 * - Sincronización estricta bajo lockLimpieza para thread-safety entre escáner y scheduler.
 * - Purga de acumulado al expirar inactividad (reset de contador entre sesiones).
 * - Máquina de estados secuencial: INICIADA → AVISO_PREVENTIVO → BLOQUEO_SESION → PAUSA_REENFOQUE.
 */
public class TimeTrackingService implements MonitorPort, BrowserEventPort {

    private final DistractionDetector detector;
    private final RegistroRepositoryPort repository;
    private final NotificacionPort notificacion;
    private KillerPort killer;

    private final int segundosAviso;
    private final int segundosBloqueo;
    private final int segundosPausa;
    private final boolean modoEstricto;
    private final String usuario;

    private final ConcurrentHashMap<String, SeguimientoActividad> activas = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Long> acumulado = new ConcurrentHashMap<>();
    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "TimeTracking-Scheduler");
        t.setDaemon(true);
        return t;
    });

    private final Object lockLimpieza = new Object();
    private static final int GRACIA_ESTRICTO_SEG = 10;
    private static final int SEGUNDOS_SIN_RASTRO = 15;

    private static final Pattern PROTOCOLO_WWW = Pattern.compile("https?://(www\\.)?");
    private static final Pattern PATH_QUERY = Pattern.compile("/.*");

    public TimeTrackingService(DistractionDetector detector,
                               RegistroRepositoryPort repository,
                               NotificacionPort notificacion,
                               KillerPort killer,
                               int segundosAviso,
                               int segundosBloqueo,
                               int segundosPausa,
                               boolean modoEstricto,
                               String usuario) {
        this.detector = detector;
        this.repository = repository;
        this.notificacion = notificacion;
        this.killer = killer;
        this.segundosAviso = segundosAviso;
        this.segundosBloqueo = segundosBloqueo;
        this.segundosPausa = segundosPausa;
        this.modoEstricto = modoEstricto;
        this.usuario = usuario;

        scheduler.scheduleAtFixedRate(this::cicloDeMantenimiento, 10, 10, TimeUnit.SECONDS);

        System.out.println("TimeTrackingService iniciado — usuario: " + usuario);
        System.out.println("   Modo: " + (modoEstricto ? "ESTRICTO" : "NORMAL"));
    }

    @Override
    public void reportarActividadSistema(String proceso, String titulo) {
        procesarActividad("SYS|" + proceso, proceso, detector.clasificar(proceso), titulo, -1);
    }

    @Override
    public void reportarEventoNavegador(String url, String titulo, int tabId) {
        String dominio = extraerDominio(url);
        procesarActividad(
            "WEB|" + dominio,
            "Web: " + (titulo != null ? titulo : dominio),
            detector.clasificarUrl(url),
            dominio,
            tabId
        );
    }

    private void procesarActividad(String clave, String nombre, String categoria, String detalle, int tabId) {
        synchronized (lockLimpieza) {
            LocalDateTime ahora = LocalDateTime.now();

            activas.computeIfAbsent(clave, k -> {
                acumulado.putIfAbsent(clave, 0L);
                System.out.println("[" + categoria + "] " + nombre);
                if (Categoria.SIN_CLASIFICAR.equals(categoria)) {
                    notificacion.notificarAppSinClasificar(nombre, detalle);
                }
                return new SeguimientoActividad(nombre, detalle, categoria, ahora, tabId);
            });

            SeguimientoActividad seg = activas.get(clave);
            if (seg != null) {
                seg.ultimaVezVista = ahora;
                if (tabId > 0) {
                    seg.tabId = tabId;
                }
            }
        }
    }

    private void cicloDeMantenimiento() {
        synchronized (lockLimpieza) {
            rotarYPersistir();
            verificarLimites();
        }
    }

    private void rotarYPersistir() {
        if (activas.isEmpty()) return;

        List<String> clavesAEliminar = new ArrayList<>();
        LocalDateTime ahora = LocalDateTime.now();

        for (Map.Entry<String, SeguimientoActividad> entry : activas.entrySet()) {
            String clave = entry.getKey();
            SeguimientoActividad seg = entry.getValue();

            long segsDesdeInicio = Duration.between(seg.inicio, ahora).getSeconds();
            long segsSinRastro = Duration.between(seg.ultimaVezVista, ahora).getSeconds();

            if (segsSinRastro > SEGUNDOS_SIN_RASTRO) {
                if (segsDesdeInicio > 0) {
                    acumulado.merge(clave, segsDesdeInicio, Long::sum);
                    persistirChunk(seg, segsDesdeInicio, ahora);
                }
                clavesAEliminar.add(clave);
                acumulado.remove(clave); // PURGA: resetea contador para nueva sesión
                System.out.println("[MANTENIMIENTO] Removido inactivo: " + seg.nombre);
                continue;
            }

            if (segsDesdeInicio > 5) {
                acumulado.merge(clave, segsDesdeInicio, Long::sum);
                persistirChunk(seg, segsDesdeInicio, ahora);
                seg.inicio = ahora;
            }
        }

        clavesAEliminar.forEach(activas::remove);
    }

    private void persistirChunk(SeguimientoActividad seg, long segs, LocalDateTime ahora) {
        Registro reg = new Registro();
        reg.setUsuarioSistema(usuario);
        reg.setNombreActividad(seg.nombre);
        reg.setCategoria(seg.categoria);
        reg.setDetalle(seg.detalle);
        reg.setDuracionSeg(segs);
        reg.setFechaRegistro(ahora);
        repository.guardar(reg);
    }

    private void verificarLimites() {
        activas.forEach((clave, seg) -> {
            if (!Categoria.DISTRACCION.equals(seg.categoria)) return;

            long segsActivos = Duration.between(seg.inicio, LocalDateTime.now()).getSeconds();
            long total = acumulado.getOrDefault(clave, 0L) + segsActivos;

            if (modoEstricto) {
                aplicarModoEstricto(clave, seg, total);
            } else {
                aplicarNiveles(clave, seg, total);
            }
        });
    }

    private void aplicarModoEstricto(String clave, SeguimientoActividad seg, long total) {
        if (total >= segundosAviso && seg.estado == EstadoDistraccion.INICIADA) {
            seg.estado = EstadoDistraccion.AVISO_PREVENTIVO;
            notificacion.mostrarAlertaDistraccion(
                "MODO ESTRICTO — cierre en " + GRACIA_ESTRICTO_SEG + " s", seg.nombre
            );
        }
        if (total >= segundosAviso + GRACIA_ESTRICTO_SEG && seg.estado == EstadoDistraccion.AVISO_PREVENTIVO) {
            seg.estado = EstadoDistraccion.PAUSA_REENFOQUE;
            notificacion.mostrarAlertaBloqueo(seg.nombre, total);
            cerrar(clave, seg);
        }
    }

    private void aplicarNiveles(String clave, SeguimientoActividad seg, long total) {
        if (seg.estado == EstadoDistraccion.INICIADA && total >= segundosAviso) {
            seg.estado = EstadoDistraccion.AVISO_PREVENTIVO;
            notificacion.mostrarAlertaDistraccion(
                "Llevas " + fmt(total) + " en " + seg.nombre, seg.nombre
            );
        } else if (seg.estado == EstadoDistraccion.AVISO_PREVENTIVO && total >= segundosBloqueo) {
            seg.estado = EstadoDistraccion.BLOQUEO_SESION;
            notificacion.mostrarAlertaBloqueo(seg.nombre, total);
        } else if (seg.estado == EstadoDistraccion.BLOQUEO_SESION && total >= segundosPausa) {
            seg.estado = EstadoDistraccion.PAUSA_REENFOQUE;
            notificacion.mostrarAlertaBloqueo(seg.nombre, total);
            cerrar(clave, seg);
        }
    }

    private void cerrar(String clave, SeguimientoActividad seg) {
        if (killer == null) return;

        if (clave.startsWith("SYS|")) {
            String nombreProceso = clave.substring(4);
            killer.cerrarProceso(nombreProceso);
        } else if (clave.startsWith("WEB|")) {
            if (seg.tabId > 0) {
                killer.cerrarPestañaNavegador(seg.tabId);
            } else {
                System.out.println("[KILL] Solicitando cierre de pestaña web (sin tabId): " + seg.detalle);
            }
        }
    }

    public void finalizar() {
        scheduler.shutdown();
        try {
            if (!scheduler.awaitTermination(5, TimeUnit.SECONDS)) {
                scheduler.shutdownNow();
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            scheduler.shutdownNow();
        }
        synchronized (lockLimpieza) {
            rotarYPersistir();
        }
        System.out.println("[FIN] Seguimiento finalizado");
    }

    public void setKiller(KillerPort killer) {
        this.killer = killer;
    }

    private String fmt(long s) {
        return (s / 60) + "m " + (s % 60) + "s";
    }

    private String extraerDominio(String url) {
        if (url == null) return "Desconocido";
        String sinProtocolo = PROTOCOLO_WWW.matcher(url).replaceAll("");
        return PATH_QUERY.matcher(sinProtocolo).replaceAll("").toLowerCase();
    }

    public enum EstadoDistraccion {
        INICIADA, AVISO_PREVENTIVO, BLOQUEO_SESION, PAUSA_REENFOQUE
    }

    private static class SeguimientoActividad {
        String nombre, detalle, categoria;
        LocalDateTime inicio;
        LocalDateTime ultimaVezVista;
        int tabId;
        EstadoDistraccion estado = EstadoDistraccion.INICIADA;

        SeguimientoActividad(String nombre, String detalle, String categoria, LocalDateTime inicio, int tabId) {
            this.nombre = nombre;
            this.detalle = detalle;
            this.categoria = categoria;
            this.inicio = inicio;
            this.ultimaVezVista = inicio;
            this.tabId = tabId;
        }
    }
}