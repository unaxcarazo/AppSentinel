package org.appsentinel.domain.service;

import org.appsentinel.domain.model.Categoria;
import org.appsentinel.domain.model.Registro;
import org.appsentinel.domain.port.in.BrowserEventPort;
import org.appsentinel.domain.port.in.MonitorPort;

import org.appsentinel.domain.port.out.KillerPort;
import org.appsentinel.domain.port.out.NotificacionPort;
import org.appsentinel.domain.port.out.RegistroRepositoryPort;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.regex.Pattern;
import org.appsentinel.domain.port.out.FocoActivoPort;

public class TimeTrackingService implements MonitorPort, BrowserEventPort, FocoActivoPort {

    private static final Logger LOGGER = Logger.getLogger(TimeTrackingService.class.getName());

    private final DistractionDetector detector;
    private final RegistroRepositoryPort repository;
    private final NotificacionPort notificacion;
    private KillerPort killer;

    private final int segundosAviso;
    private final int segundosBloqueo;
    private final boolean modoEstricto;
    private final String usuario;

    private final ConcurrentHashMap<String, SesionExistencia> sesiones = new ConcurrentHashMap<>();

    private final AtomicReference<Map<String, SeguimientoActividad>> activasRef =
        new AtomicReference<>(Map.of());

    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "TimeTracking-Scheduler");
        t.setDaemon(true);
        return t;
    });

    private final Object lockLimpieza = new Object();

    private static final int GRACIA_ESTRICTO_SEG            = 10;
    private static final int SEGUNDOS_AUSENCIA_USUARIO      = 300;
    private static final int SEGUNDOS_SIN_RASTRO            = 120;
    private static final int SEGUNDOS_FLUSH_BG              = 60;
    private static final int UMBRAL_MINIMO_PERSISTENCIA_SEG = 5;

    private static final Pattern PROTOCOLO_WWW = Pattern.compile("https?://(www\\.)?");
    private static final Pattern PATH_QUERY    = Pattern.compile("/.*");

    private static final Set<String> NAVEGADORES = Set.of(
        "chrome", "msedge", "firefox", "opera", "brave", "vivaldi",
        "chromium", "safari", "arc", "electron"
    );

    private volatile LocalDateTime ultimoInputUsuario = LocalDateTime.now();

    public TimeTrackingService(DistractionDetector detector,
                               RegistroRepositoryPort repository,
                               NotificacionPort notificacion,
                               KillerPort killer,
                               int segundosAviso,
                               int segundosBloqueo,
                               int segundosPausa,
                               boolean modoEstricto,
                               String usuario) {
        this.detector        = detector;
        this.repository      = repository;
        this.notificacion    = notificacion;
        this.killer          = killer;
        this.segundosAviso   = segundosAviso;
        this.segundosBloqueo = segundosBloqueo;
        this.modoEstricto    = modoEstricto;
        this.usuario         = usuario;

        scheduler.scheduleAtFixedRate(this::cicloDeMantenimiento, 10, 10, TimeUnit.SECONDS);

        LOGGER.log(Level.INFO, "TimeTrackingService iniciado — usuario: {0} | Modo: {1}",
            new Object[]{usuario, modoEstricto ? "ESTRICTO" : "NORMAL"});
        LOGGER.log(Level.INFO, "Persistencia: buffer non-blocking, acumulación BG cada {0}s", SEGUNDOS_FLUSH_BG);
        LOGGER.log(Level.INFO, "[DEBUG-CONFIG] segundosAviso={0} segundosBloqueo={1} modoEstricto={2}",
            new Object[]{segundosAviso, segundosBloqueo, modoEstricto});
    }

    // -------------------------------------------------------------------------
    // Puertos de entrada
    // -------------------------------------------------------------------------

    @Override
    public void reportarActividadSistema(String proceso, String titulo, int pid, Instant startInstant) {
        ultimoInputUsuario = LocalDateTime.now();
        List<Registro> pendientes = new ArrayList<>();
        synchronized (lockLimpieza) {
            procesarActividad("SYS|" + proceso, proceso, detector.clasificar(proceso),
                              titulo, -1, pid, startInstant, pendientes);
        }
        flushearPendientes(pendientes);
    }

    @Override
    public void reportarEventoNavegador(String url, String titulo, int tabId) {
        ultimoInputUsuario = LocalDateTime.now();
        String dominio = extraerDominio(url);
        List<Registro> pendientes = new ArrayList<>();
        synchronized (lockLimpieza) {
            procesarActividad(
                "WEB|" + dominio,
                "Web: " + (titulo != null ? titulo : dominio),
                detector.clasificarUrl(url),
                dominio, tabId, -1, null, pendientes
            );
        }
        flushearPendientes(pendientes);
    }

    private void flushearPendientes(List<Registro> pendientes) {
        for (Registro r : pendientes) {
            repository.guardar(r);
        }
    }

    // -------------------------------------------------------------------------
    // FocoActivoPort
    // -------------------------------------------------------------------------

    @Override
    public int getPidFocoActivo() {
        return activasRef.get().values().stream().findFirst().map(seg -> seg.pid).orElse(-1);
    }

    @Override
    public String getNombreFocoActivo() {
        return activasRef.get().values().stream().findFirst().map(seg -> seg.nombre).orElse(null);
    }

    @Override
    public boolean esFocoWeb() {
        return activasRef.get().keySet().stream().findFirst()
            .map(clave -> clave.startsWith("WEB|")).orElse(false);
    }

    // -------------------------------------------------------------------------
    // Lógica principal
    // -------------------------------------------------------------------------

    private void procesarActividad(String clave, String nombre, String categoria,
                                   String detalle, int tabId, int pid,
                                   Instant startInstant,
                                   List<Registro> pendientes) {
        LocalDateTime ahora = LocalDateTime.now();

        sesiones.computeIfAbsent(clave, k -> {
            LOGGER.log(Level.INFO, "[EXISTENCIA] Nueva app detectada: {0}", nombre);
            return new SesionExistencia(clave, nombre, categoria, ahora);
        });

        SesionExistencia sesion = sesiones.get(clave);
        if (sesion != null) {
            sesion.ultimaVezVista = ahora;
            sesion.categoria = categoria;

            if (Categoria.SIN_CLASIFICAR.equals(categoria) && !sesion.notificadaSinClasificar) {
                notificacion.notificarAppSinClasificar(nombre, detalle);
                sesion.notificadaSinClasificar = true;
            }
        }

        Map<String, SeguimientoActividad> activas = activasRef.get();
        boolean esMismaApp    = activas.containsKey(clave);
        boolean esNavegadorSO = clave.startsWith("SYS|") && esProcesoNavegador(nombre);

        if (!esMismaApp) {
            Registro flushAnterior = prepararFlushFocoActivo(ahora);
            if (flushAnterior != null) pendientes.add(flushAnterior);

            String claveWebActiva = esNavegadorSO ? claveWebActivaReciente(activas, ahora) : null;
            if (claveWebActiva != null) {
                SesionExistencia sesionWeb = sesiones.get(claveWebActiva);
                if (sesionWeb != null) sesionWeb.ultimaVezVista = ahora;
                LOGGER.log(Level.FINE, "[FOCO] Navegador ({0}) en primer plano. Web mantiene foco.", nombre);
                return;
            }

            Map<String, SeguimientoActividad> nuevoMapa = new ConcurrentHashMap<>(2);
            nuevoMapa.put(clave, new SeguimientoActividad(nombre, detalle, categoria, ahora, tabId, pid, startInstant));
            activasRef.set(nuevoMapa);
            LOGGER.log(Level.INFO, "[FOCO] {0} categoria={1} pid={2} (startInstant={3})",
                new Object[]{nombre, categoria, pid, startInstant});

        } else {
            SeguimientoActividad seg = activas.get(clave);
            if (seg != null) {
                if (tabId > 0) seg.tabId = tabId;
                if (pid   > 0) seg.pid   = pid;
            }
        }
    }

    private Registro prepararFlushFocoActivo(LocalDateTime ahora) {
        Map<String, SeguimientoActividad> activas = activasRef.get();
        if (activas.isEmpty()) return null;

        Map.Entry<String, SeguimientoActividad> entry = activas.entrySet().stream().findFirst().orElse(null);
        if (entry == null) return null;

        String clave = entry.getKey();
        SeguimientoActividad seg = entry.getValue();

        long segsFoco    = Duration.between(seg.inicio, ahora).getSeconds();
        long totalAFlush = seg.segundosAcumulados + segsFoco;

        if (totalAFlush <= 0) {
            seg.inicio = ahora;
            return null;
        }

        Registro reg = encolarChunkFoco(seg, totalAFlush, ahora);

        seg.segundosAcumulados = 0;
        seg.inicio = ahora;
        seg.estado = EstadoDistraccion.INICIADA;

        SesionExistencia sesion = sesiones.get(clave);
        if (sesion != null) sesion.segundosFocoAcumulado += totalAFlush;

        LOGGER.log(Level.INFO, "[FLUSH-PREP] {0} → {1}s listo para persistir",
            new Object[]{seg.nombre, totalAFlush});
        return reg;
    }

    // -------------------------------------------------------------------------
    // Ciclo de mantenimiento
    // -------------------------------------------------------------------------

    private void cicloDeMantenimiento() {
        List<Registro> pendientes = new ArrayList<>();
        synchronized (lockLimpieza) {
            if (detectarAusenciaUsuario()) {
                LOGGER.log(Level.FINE, "[AUSENCIA] Usuario ausente. Relojes congelados.");
            } else {
                avanzarRelojesYVerificar(pendientes);
            }
            purgarSesionesMuertas(pendientes);
        }
        flushearPendientes(pendientes);
    }

    private boolean detectarAusenciaUsuario() {
        return Duration.between(ultimoInputUsuario, LocalDateTime.now()).getSeconds() > SEGUNDOS_AUSENCIA_USUARIO;
    }

    private void avanzarRelojesYVerificar(List<Registro> pendientes) {
        LocalDateTime ahora = LocalDateTime.now();

        for (SesionExistencia sesion : sesiones.values()) {
            if (!activasRef.get().containsKey(sesion.clave)) {
                long segs = Duration.between(sesion.inicio, ahora).getSeconds();
                if (segs >= UMBRAL_MINIMO_PERSISTENCIA_SEG) {
                    sesion.segundosBackgroundAcumulados += segs;
                    sesion.inicio = ahora;
                }
                if (sesion.segundosBackgroundAcumulados >= SEGUNDOS_FLUSH_BG) {
                    pendientes.add(encolarChunkExistencia(sesion, sesion.segundosBackgroundAcumulados, ahora));
                    sesion.segundosBackgroundAcumulados = 0;
                }
            }
        }

        activasRef.get().forEach((clave, seg) -> {
            long segsFoco = Duration.between(seg.inicio, ahora).getSeconds();
            if (segsFoco >= UMBRAL_MINIMO_PERSISTENCIA_SEG) {
                seg.segundosAcumulados += segsFoco;
                seg.inicio = ahora;
            }

            if (Categoria.DISTRACCION.equals(seg.categoria)) {
                long totalFoco = obtenerFocoAcumulado(clave) + seg.segundosAcumulados
                               + Duration.between(seg.inicio, ahora).getSeconds();

                // DEBUG: log en cada ciclo para apps de distracción
                LOGGER.log(Level.INFO,
                    "[DEBUG-NIVELES] app={0} totalFoco={1}s aviso={2}s bloqueo={3}s estado={4} killer={5}",
                    new Object[]{seg.nombre, totalFoco, segundosAviso, segundosBloqueo,
                                 seg.estado, killer != null ? "OK" : "NULL"});

                if (modoEstricto) aplicarModoEstricto(clave, seg, totalFoco);
                else aplicarNiveles(clave, seg, totalFoco);
            }
        });
    }

    // -------------------------------------------------------------------------
    // Encolado de Persistencia
    // -------------------------------------------------------------------------

    private Registro encolarChunkExistencia(SesionExistencia sesion, long segs, LocalDateTime ahora) {
        return Registro.builder()
            .usuarioSistema(usuario)
            .nombreActividad(sesion.nombre)
            .categoria("BACKGROUND_" + sesion.categoria)
            .detalle("Segundo plano")
            .duracionSeg(segs)
            .fechaRegistro(ahora)
            .build();
    }

    private Registro encolarChunkFoco(SeguimientoActividad seg, long segs, LocalDateTime ahora) {
        return Registro.builder()
            .usuarioSistema(usuario)
            .nombreActividad(seg.nombre)
            .categoria(seg.categoria)
            .detalle("Foco activo: " + seg.detalle)
            .duracionSeg(segs)
            .fechaRegistro(ahora)
            .build();
    }

    // -------------------------------------------------------------------------
    // Cierre forzoso, Purga y Limpieza
    // -------------------------------------------------------------------------

    private void purgarSesionesMuertas(List<Registro> pendientes) {
        List<String> clavesAEliminar = new ArrayList<>();
        LocalDateTime ahora = LocalDateTime.now();
        Map<String, SeguimientoActividad> activas = activasRef.get();

        for (Map.Entry<String, SesionExistencia> entry : sesiones.entrySet()) {
            String clave            = entry.getKey();
            SesionExistencia sesion = entry.getValue();

            if (Duration.between(sesion.ultimaVezVista, ahora).getSeconds() <= SEGUNDOS_SIN_RASTRO) continue;

            if (sesion.segundosBackgroundAcumulados > 0) {
                pendientes.add(encolarChunkExistencia(sesion, sesion.segundosBackgroundAcumulados, ahora));
            }

            SeguimientoActividad seg = activas.get(clave);
            if (seg != null) {
                Registro reg = prepararRegistroFocoPurge(seg, ahora);
                if (reg != null) pendientes.add(reg);
            }

            clavesAEliminar.add(clave);
            LOGGER.log(Level.INFO, "[MANTENIMIENTO] Sesión expirada: {0}", sesion.nombre);
        }

        clavesAEliminar.forEach(sesiones::remove);

        Map<String, SeguimientoActividad> activasActual = activasRef.get();
        if (clavesAEliminar.stream().anyMatch(activasActual::containsKey)) {
            Map<String, SeguimientoActividad> nuevoMapa = new ConcurrentHashMap<>(activasActual);
            clavesAEliminar.forEach(nuevoMapa::remove);
            activasRef.set(nuevoMapa);
        }
    }

    private Registro prepararRegistroFocoPurge(SeguimientoActividad seg, LocalDateTime ahora) {
        long segsFoco    = Duration.between(seg.inicio, ahora).getSeconds();
        long totalAFlush = seg.segundosAcumulados + segsFoco;
        if (totalAFlush <= 0) return null;
        return encolarChunkFoco(seg, totalAFlush, ahora);
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

        List<Registro> pendientes = new ArrayList<>();
        synchronized (lockLimpieza) {
            LocalDateTime ahora = LocalDateTime.now();
            Map<String, SeguimientoActividad> activas = activasRef.get();

            activas.forEach((clave, seg) -> {
                Registro reg = prepararRegistroFocoPurge(seg, ahora);
                if (reg != null) pendientes.add(reg);
            });

            sesiones.values().stream()
                .filter(s -> !activas.containsKey(s.clave))
                .forEach(s -> {
                    if (s.segundosBackgroundAcumulados > 0) {
                        pendientes.add(encolarChunkExistencia(s, s.segundosBackgroundAcumulados, ahora));
                    }
                });
        }
        flushearPendientes(pendientes);

        LOGGER.log(Level.INFO, "[FIN] Seguimiento finalizado. {0} registros pendientes flusheados al buffer.",
            pendientes.size());
    }

    // -------------------------------------------------------------------------
    // Métodos Auxiliares
    // -------------------------------------------------------------------------

    private boolean esProcesoNavegador(String nombreProceso) {
        if (nombreProceso == null) return false;
        return NAVEGADORES.contains(nombreProceso.toLowerCase().replace(".exe", ""));
    }

    private String claveWebActivaReciente(Map<String, SeguimientoActividad> activas, LocalDateTime ahora) {
        return activas.entrySet().stream()
            .filter(e -> e.getKey().startsWith("WEB|"))
            .filter(e -> {
                SesionExistencia sesion = sesiones.get(e.getKey());
                return sesion != null &&
                    Duration.between(sesion.ultimaVezVista, ahora).getSeconds() < 60;
            })
            .map(Map.Entry::getKey)
            .findFirst()
            .orElse(null);
    }

    private long obtenerFocoAcumulado(String clave) {
        SesionExistencia sesion = sesiones.get(clave);
        return sesion != null ? sesion.segundosFocoAcumulado : 0L;
    }

    private void aplicarModoEstricto(String clave, SeguimientoActividad seg, long totalFoco) {
        if (totalFoco >= segundosAviso && seg.estado == EstadoDistraccion.INICIADA) {
            seg.estado = EstadoDistraccion.AVISO_PREVENTIVO;
            notificacion.mostrarAlertaDistraccion(
                "MODO ESTRICTO: cierre en " + GRACIA_ESTRICTO_SEG + " s", seg.nombre);
        }
        if (totalFoco >= segundosAviso + GRACIA_ESTRICTO_SEG
                && seg.estado == EstadoDistraccion.AVISO_PREVENTIVO) {
            seg.estado = EstadoDistraccion.BLOQUEO_SESION;
            notificacion.mostrarAlertaBloqueo(seg.nombre, totalFoco);
            cerrar(clave, seg);
        }
    }

    private void aplicarNiveles(String clave, SeguimientoActividad seg, long totalFoco) {
        if (seg.estado == EstadoDistraccion.INICIADA && totalFoco >= segundosAviso) {
            seg.estado = EstadoDistraccion.AVISO_PREVENTIVO;
            notificacion.mostrarAlertaDistraccion(
                "Llevas " + fmt(totalFoco) + " con foco en " + seg.nombre, seg.nombre);
        } else if (seg.estado == EstadoDistraccion.AVISO_PREVENTIVO && totalFoco >= segundosBloqueo) {
            seg.estado = EstadoDistraccion.BLOQUEO_SESION;
            notificacion.mostrarAlertaBloqueo(seg.nombre, totalFoco);
            cerrar(clave, seg);
        }
    }

    private void cerrar(String clave, SeguimientoActividad seg) {
        // DEBUG: log siempre que se intente cerrar
        LOGGER.log(Level.INFO,
            "[DEBUG-CERRAR] killer={0} clave={1} pid={2} tabId={3} startInstant={4}",
            new Object[]{killer != null ? "OK" : "NULL", clave, seg.pid, seg.tabId, seg.startInstant});

        if (killer == null) return;
        if (clave.startsWith("SYS|") && seg.pid > 0) {
            killer.cerrarProceso(seg.nombre, seg.pid, seg.startInstant);
        } else if (clave.startsWith("WEB|") && seg.tabId > 0) {
            killer.cerrarPestañaNavegador(seg.tabId);
        }
    }

    public void setKiller(KillerPort killer) { this.killer = killer; }

    private String fmt(long s) { return (s / 60) + "m " + (s % 60) + "s"; }

    private String extraerDominio(String url) {
        if (url == null) return "Desconocido";
        String sinProtocolo = PROTOCOLO_WWW.matcher(url).replaceAll("");
        return PATH_QUERY.matcher(sinProtocolo).replaceAll("").toLowerCase();
    }

    // -------------------------------------------------------------------------
    // Enumeraciones y clases internas
    // -------------------------------------------------------------------------

    public enum EstadoDistraccion { INICIADA, AVISO_PREVENTIVO, BLOQUEO_SESION, PAUSA_REENFOQUE }

    private static class SeguimientoActividad {
        String nombre, detalle, categoria;
        LocalDateTime inicio;
        int tabId, pid;
        Instant startInstant;
        EstadoDistraccion estado = EstadoDistraccion.INICIADA;
        long segundosAcumulados = 0;

        SeguimientoActividad(String nombre, String detalle, String categoria,
                             LocalDateTime inicio, int tabId, int pid, Instant startInstant) {
            this.nombre       = nombre;
            this.detalle      = detalle;
            this.categoria    = categoria;
            this.inicio       = inicio;
            this.tabId        = tabId;
            this.pid          = pid;
            this.startInstant = startInstant;
        }
    }

    private static class SesionExistencia {
        final String clave, nombre;
        String categoria;
        LocalDateTime inicio, ultimaVezVista;
        long segundosFocoAcumulado = 0L;
        long segundosBackgroundAcumulados = 0L;
        boolean notificadaSinClasificar = false;

        SesionExistencia(String clave, String nombre, String categoria, LocalDateTime inicio) {
            this.clave          = clave;
            this.nombre         = nombre;
            this.categoria      = categoria;
            this.inicio         = inicio;
            this.ultimaVezVista = inicio;
        }
    }
}