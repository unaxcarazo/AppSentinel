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
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.regex.Pattern;

/**
 * TimeTrackingService: Orquestador principal del seguimiento de actividad.
 *
 * REGLAS DE NEGOCIO:
 * 1. TODAS las apps detectadas tienen un reloj de existencia continuo (sesiones).
 * 2. Solo la app con FOCO ACTIVO puede ser evaluada para bloqueo.
 * 3. Las apps en segundo plano se registran como "BACKGROUND_X" → nunca se bloquean.
 * 4. Si el usuario está AUSENTE (sin reportes en SEGUNDOS_AUSENCIA_USUARIO), los relojes se congelan.
 * 5. El bloqueo se dispara solo cuando una DISTRACCION supera segundosBloqueo con foco activo.
 *
 * INVARIANTE DE PERSISTENCIA:
 * - Una app enfocada → persiste SOLO via persistirChunkFoco (nunca via persistirChunkExistencia).
 * - Una app en segundo plano → persiste SOLO via persistirChunkExistencia.
 * - Esta regla se aplica uniformemente en el ciclo normal, en la purga y en el cierre.
 */
public class TimeTrackingService implements MonitorPort, BrowserEventPort {

    private static final Logger LOGGER = Logger.getLogger(TimeTrackingService.class.getName());

    private final DistractionDetector detector;
    private final RegistroRepositoryPort repository;
    private final NotificacionPort notificacion;
    private KillerPort killer;

    private final int segundosAviso;
    private final int segundosBloqueo;
    private final boolean modoEstricto;
    private final String usuario;

    // Reloj de existencia: todas las apps que el escáner ha detectado, con o sin foco.
    private final ConcurrentHashMap<String, SesionExistencia> sesiones = new ConcurrentHashMap<>();

    // Reloj de foco: máximo una entrada. La clave es idéntica a la de 'sesiones'.
    private final ConcurrentHashMap<String, SeguimientoActividad> activas = new ConcurrentHashMap<>();

    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "TimeTracking-Scheduler");
        t.setDaemon(true);
        return t;
    });

    private final Object lockLimpieza = new Object();

    private static final int GRACIA_ESTRICTO_SEG      = 10;
    private static final int SEGUNDOS_AUSENCIA_USUARIO = 300;
    private static final int SEGUNDOS_SIN_RASTRO       = 120;

    private static final Pattern PROTOCOLO_WWW = Pattern.compile("https?://(www\\.)?");
    private static final Pattern PATH_QUERY    = Pattern.compile("/.*");

    private volatile LocalDateTime ultimoInputUsuario = LocalDateTime.now();

    public TimeTrackingService(DistractionDetector detector,
                               RegistroRepositoryPort repository,
                               NotificacionPort notificacion,
                               KillerPort killer,
                               int segundosAviso,
                               int segundosBloqueo,
                               int segundosPausa,   // Reservado, no usado actualmente
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

        System.out.println("TimeTrackingService iniciado — usuario: " + usuario);
        System.out.println("   Modo: " + (modoEstricto ? "ESTRICTO" : "NORMAL"));
    }

    // -------------------------------------------------------------------------
    // Puertos de entrada
    // -------------------------------------------------------------------------

    @Override
    public void reportarActividadSistema(String proceso, String titulo, int pid) {
        ultimoInputUsuario = LocalDateTime.now();
        procesarActividad("SYS|" + proceso, proceso, detector.clasificar(proceso), titulo, -1, pid);
    }

    @Override
    public void reportarEventoNavegador(String url, String titulo, int tabId) {
        ultimoInputUsuario = LocalDateTime.now();
        String dominio = extraerDominio(url);
        procesarActividad(
            "WEB|" + dominio,
            "Web: " + (titulo != null ? titulo : dominio),
            detector.clasificarUrl(url),
            dominio,
            tabId,
            -1
        );
    }

    // -------------------------------------------------------------------------
    // Procesamiento de actividad
    // -------------------------------------------------------------------------

    private void procesarActividad(String clave, String nombre, String categoria,
                                   String detalle, int tabId, int pid) {
        synchronized (lockLimpieza) {
            LocalDateTime ahora = LocalDateTime.now();

            // --- Sesión de existencia ---
            sesiones.computeIfAbsent(clave, k -> {
                System.out.println("[EXISTENCIA] Nueva app detectada: " + nombre + " (" + clave + ")");
                return new SesionExistencia(clave, nombre, categoria, ahora);
            });

            SesionExistencia sesion = sesiones.get(clave);
            if (sesion != null) {
                sesion.ultimaVezVista = ahora;
                sesion.categoria = categoria;

                // Notificar "sin clasificar" solo una vez por sesión de existencia
                if (Categoria.SIN_CLASIFICAR.equals(categoria) && !sesion.notificadaSinClasificar) {
                    notificacion.notificarAppSinClasificar(nombre, detalle);
                    sesion.notificadaSinClasificar = true;
                }
            }

            // --- Foco activo ---
            boolean esMismaApp = activas.containsKey(clave);
            if (!esMismaApp) {
                activas.clear();
                activas.put(clave, new SeguimientoActividad(nombre, detalle, categoria, ahora, tabId, pid));
                System.out.println("[FOCO] " + nombre);
            } else {
                SeguimientoActividad seg = activas.get(clave);
                if (seg != null) {
                    seg.ultimaVezVista = ahora;
                    if (tabId > 0) seg.tabId = tabId;
                    if (pid   > 0) seg.pid   = pid;
                }
            }
        }
    }

    // -------------------------------------------------------------------------
    // Ciclo de mantenimiento
    // -------------------------------------------------------------------------

    private void cicloDeMantenimiento() {
        synchronized (lockLimpieza) {
            if (detectarAusenciaUsuario()) {
                LOGGER.log(Level.FINE, "[AUSENCIA] Usuario ausente. Relojes congelados.");
            } else {
                avanzarRelojesYVerificar();
            }
            purgarSesionesMuertas();
        }
    }

    private boolean detectarAusenciaUsuario() {
        long segsDesdeUltimoInput = Duration.between(ultimoInputUsuario, LocalDateTime.now()).getSeconds();
        return segsDesdeUltimoInput > SEGUNDOS_AUSENCIA_USUARIO;
    }

    private void avanzarRelojesYVerificar() {
        LocalDateTime ahora = LocalDateTime.now();

        // --- Avanzar relojes de existencia (solo apps en segundo plano) ---
        // Las apps con foco delegan su persistencia al bloque de activas (invariante de persistencia).
        for (SesionExistencia sesion : sesiones.values()) {
            long segsDesdeInicio = Duration.between(sesion.inicio, ahora).getSeconds();
            if (segsDesdeInicio > 5) {
                if (!activas.containsKey(sesion.clave)) {
                    persistirChunkExistencia(sesion, segsDesdeInicio, ahora);
                }
                // El inicio se resetea siempre: evita doble conteo si la app gana foco
                // antes del siguiente ciclo, ya que el foco se cuenta desde seg.inicio.
                sesion.inicio = ahora;
            }
        }

        // --- Avanzar reloj de foco y evaluar bloqueo (solo la app activa) ---
        activas.forEach((clave, seg) -> {
            long segsFoco = Duration.between(seg.inicio, ahora).getSeconds();

            if (segsFoco > 5) {
                persistirChunkFoco(clave, seg, segsFoco, ahora);
                seg.inicio = ahora;
            }

            // Bloqueo solo si es distracción con foco activo
            if (Categoria.DISTRACCION.equals(seg.categoria)) {
                long focoHistorico  = obtenerFocoAcumulado(clave);
                long segsFocoActual = Duration.between(seg.inicio, ahora).getSeconds();
                long totalFoco      = focoHistorico + segsFocoActual;

                if (modoEstricto) {
                    aplicarModoEstricto(clave, seg, totalFoco);
                } else {
                    aplicarNiveles(clave, seg, totalFoco);
                }
            }
        });
    }

    // -------------------------------------------------------------------------
    // Persistencia
    // -------------------------------------------------------------------------

    /**
     * Persiste un chunk de SEGUNDO PLANO. Solo debe llamarse cuando la app NO tiene foco.
     * La categoría se prefija con "BACKGROUND_" para distinguirla del tiempo de foco.
     */
    private void persistirChunkExistencia(SesionExistencia sesion, long segs, LocalDateTime ahora) {
        Registro reg = new Registro();
        reg.setUsuarioSistema(usuario);
        reg.setNombreActividad(sesion.nombre);
        reg.setCategoria("BACKGROUND_" + sesion.categoria);
        reg.setDetalle("Segundo plano");
        reg.setDuracionSeg(segs);
        reg.setFechaRegistro(ahora);
        repository.guardar(reg);
    }

    /**
     * Persiste un chunk de FOCO ACTIVO y acumula los segundos en la sesión de existencia.
     * Es la única función que debe registrar tiempo de la app enfocada.
     */
    private void persistirChunkFoco(String clave, SeguimientoActividad seg,
                                    long segs, LocalDateTime ahora) {
        Registro reg = new Registro();
        reg.setUsuarioSistema(usuario);
        reg.setNombreActividad(seg.nombre);
        reg.setCategoria(seg.categoria);
        reg.setDetalle("Foco activo: " + seg.detalle);
        reg.setDuracionSeg(segs);
        reg.setFechaRegistro(ahora);
        repository.guardar(reg);

        SesionExistencia sesion = sesiones.get(clave);
        if (sesion != null) {
            sesion.segundosFocoAcumulado += segs;
        } else {
            LOGGER.log(Level.WARNING,
                "[FOCO] Sin sesión de existencia para {0}. Acumulado no guardado.", clave);
        }
    }

    private long obtenerFocoAcumulado(String clave) {
        SesionExistencia sesion = sesiones.get(clave);
        return sesion != null ? sesion.segundosFocoAcumulado : 0L;
    }

    // -------------------------------------------------------------------------
    // Verificación de límites de distracción
    // -------------------------------------------------------------------------

    private void aplicarModoEstricto(String clave, SeguimientoActividad seg, long totalFoco) {
        if (totalFoco >= segundosAviso && seg.estado == EstadoDistraccion.INICIADA) {
            seg.estado = EstadoDistraccion.AVISO_PREVENTIVO;
            notificacion.mostrarAlertaDistraccion(
                "MODO ESTRICTO: cierre en " + GRACIA_ESTRICTO_SEG + " s", seg.nombre
            );
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
                "Llevas " + fmt(totalFoco) + " con foco en " + seg.nombre, seg.nombre
            );
        } else if (seg.estado == EstadoDistraccion.AVISO_PREVENTIVO && totalFoco >= segundosBloqueo) {
            seg.estado = EstadoDistraccion.BLOQUEO_SESION;
            notificacion.mostrarAlertaBloqueo(seg.nombre, totalFoco);
            cerrar(clave, seg);
        }
    }

    // -------------------------------------------------------------------------
    // Cierre forzoso
    // -------------------------------------------------------------------------

    private void cerrar(String clave, SeguimientoActividad seg) {
        if (killer == null) return;

        if (!activas.containsKey(clave)) {
            LOGGER.log(Level.WARNING,
                "[KILL] Ignorado: {0} ya no tiene el foco al intentar cerrar.", seg.nombre);
            return;
        }

        if (clave.startsWith("SYS|")) {
            if (seg.pid > 0) {
                killer.cerrarProceso(seg.nombre, seg.pid);
            } else {
                LOGGER.log(Level.WARNING, "[KILL] PID no disponible para: {0}", seg.nombre);
            }
        } else if (clave.startsWith("WEB|")) {
            if (seg.tabId > 0) {
                killer.cerrarPestañaNavegador(seg.tabId);
            } else {
                LOGGER.log(Level.WARNING, "[KILL] Sin tabId para pestaña: {0}", seg.detalle);
            }
        }
    }

    // -------------------------------------------------------------------------
    // Purga de sesiones muertas
    // -------------------------------------------------------------------------

    /**
     * Elimina sesiones de apps que han desaparecido del sistema.
     *
     * CORRECCIÓN: antes de eliminar una sesión activa, se persiste su chunk de foco
     * (SeguimientoActividad.inicio) para no perder el tiempo transcurrido desde
     * el último flush del ciclo de mantenimiento.
     */
    private void purgarSesionesMuertas() {
        List<String> clavesAEliminar = new ArrayList<>();
        LocalDateTime ahora = LocalDateTime.now();

        for (Map.Entry<String, SesionExistencia> entry : sesiones.entrySet()) {
            String clave            = entry.getKey();
            SesionExistencia sesion = entry.getValue();

            long segsSinRastro = Duration.between(sesion.ultimaVezVista, ahora).getSeconds();
            if (segsSinRastro <= SEGUNDOS_SIN_RASTRO) continue;

            SeguimientoActividad seg = activas.get(clave);

            if (seg != null) {
                // App enfocada al purgar: persiste chunk de foco (invariante de persistencia).
                // No llamar a persistirChunkExistencia para evitar doble conteo.
                long segsFoco = Duration.between(seg.inicio, ahora).getSeconds();
                if (segsFoco > 0) {
                    persistirChunkFoco(clave, seg, segsFoco, ahora);
                }
            } else {
                // App en segundo plano: persiste chunk de existencia normalmente.
                long segsDesdeInicio = Duration.between(sesion.inicio, ahora).getSeconds();
                if (segsDesdeInicio > 0) {
                    persistirChunkExistencia(sesion, segsDesdeInicio, ahora);
                }
            }

            clavesAEliminar.add(clave);
            System.out.println("[MANTENIMIENTO] Proceso cerrado/desaparecido: " + sesion.nombre);
        }

        clavesAEliminar.forEach(sesiones::remove);
        activas.keySet().removeIf(clave -> !sesiones.containsKey(clave));
    }

    // -------------------------------------------------------------------------
    // Ciclo de vida del servicio
    // -------------------------------------------------------------------------

    /**
     * Cierra el servicio y persiste los chunks pendientes.
     *
     * CORRECCIÓN: se aplica el mismo invariante que en el ciclo normal:
     * - Las apps enfocadas se persisten SOLO via persistirChunkFoco.
     * - Las apps en segundo plano se persisten SOLO via persistirChunkExistencia.
     * El orden importa: activas primero para actualizar segundosFocoAcumulado
     * antes de iterar sesiones (aunque en el cierre no se usa, es más correcto).
     */
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
            LocalDateTime ahora = LocalDateTime.now();

            // 1. Flush de apps enfocadas (foco activo)
            for (Map.Entry<String, SeguimientoActividad> entry : activas.entrySet()) {
                long segs = Duration.between(entry.getValue().inicio, ahora).getSeconds();
                if (segs > 0) {
                    persistirChunkFoco(entry.getKey(), entry.getValue(), segs, ahora);
                }
            }

            // 2. Flush de apps en segundo plano (excluye las que tenían foco, ya persistidas)
            for (SesionExistencia sesion : sesiones.values()) {
                if (!activas.containsKey(sesion.clave)) {
                    long segs = Duration.between(sesion.inicio, ahora).getSeconds();
                    if (segs > 0) {
                        persistirChunkExistencia(sesion, segs, ahora);
                    }
                }
            }
        }

        System.out.println("[FIN] Seguimiento finalizado");
    }

    public void setKiller(KillerPort killer) {
        this.killer = killer;
    }

    // -------------------------------------------------------------------------
    // Utilidades
    // -------------------------------------------------------------------------

    private String fmt(long s) {
        return (s / 60) + "m " + (s % 60) + "s";
    }

    private String extraerDominio(String url) {
        if (url == null) return "Desconocido";
        String sinProtocolo = PROTOCOLO_WWW.matcher(url).replaceAll("");
        return PATH_QUERY.matcher(sinProtocolo).replaceAll("").toLowerCase();
    }

    // -------------------------------------------------------------------------
    // Tipos internos
    // -------------------------------------------------------------------------

    public enum EstadoDistraccion {
        INICIADA, AVISO_PREVENTIVO, BLOQUEO_SESION, PAUSA_REENFOQUE
    }

    private static class SeguimientoActividad {
        String nombre, detalle, categoria;
        LocalDateTime inicio;
        LocalDateTime ultimaVezVista;
        int tabId;
        int pid;
        EstadoDistraccion estado = EstadoDistraccion.INICIADA;

        SeguimientoActividad(String nombre, String detalle, String categoria,
                             LocalDateTime inicio, int tabId, int pid) {
            this.nombre         = nombre;
            this.detalle        = detalle;
            this.categoria      = categoria;
            this.inicio         = inicio;
            this.ultimaVezVista = inicio;
            this.tabId          = tabId;
            this.pid            = pid;
        }
    }

    private static class SesionExistencia {
        final String clave;
        final String nombre;
        String categoria;
        LocalDateTime inicio;
        LocalDateTime ultimaVezVista;
        long segundosFocoAcumulado;
        boolean notificadaSinClasificar;

        SesionExistencia(String clave, String nombre, String categoria, LocalDateTime inicio) {
            this.clave                   = clave;
            this.nombre                  = nombre;
            this.categoria               = categoria;
            this.inicio                  = inicio;
            this.ultimaVezVista          = inicio;
            this.segundosFocoAcumulado   = 0L;
            this.notificadaSinClasificar = false;
        }
    }
}

