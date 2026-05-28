package org.appsentinel.domain.service;

import org.appsentinel.domain.model.Categoria;
import org.appsentinel.domain.model.Registro;
import org.appsentinel.domain.port.in.BrowserEventPort;
import org.appsentinel.domain.port.in.MonitorPort;
import org.appsentinel.domain.port.out.FocoActivoPort;
import org.appsentinel.domain.port.out.KillerPort;
import org.appsentinel.domain.port.out.NotificacionPort;
import org.appsentinel.domain.port.out.RegistroRepositoryPort;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.*;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.regex.Pattern;

/**
 * TimeTrackingService: Orquestador principal del seguimiento de actividad.
 *
 * FIX: Añadido método actualizarTabId() para manejar cambio de pestaña
 * dentro del mismo dominio (misma sesión, tabId diferente).
 * WebSocketAdapter detecta cambio de pestaña y envía evento al dominio;
 * TimeTrackingService actualiza el tabId sin reiniciar tiempo acumulado.
 *
 * REGLAS DE NEGOCIO:
 * 1. TODAS las apps detectadas tienen un reloj de existencia continuo (sesiones).
 * 2. Solo la app con FOCO ACTIVO puede ser evaluada para bloqueo.
 * 3. Las apps en segundo plano se registran como "BACKGROUND_X" → nunca se bloquean.
 * 4. El bloqueo se dispara solo cuando una DISTRACCION supera segundosBloqueo con foco activo.
 */
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

    // Reloj de existencia: todas las apps detectadas, con o sin foco.
    private final ConcurrentHashMap<String, SesionExistencia> sesiones = new ConcurrentHashMap<>();

    // Reloj de foco: máximo una entrada. La clave es idéntica a la de 'sesiones'.
    private final ConcurrentHashMap<String, SeguimientoActividad> activas = new ConcurrentHashMap<>();

    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "TimeTracking-Scheduler");
        t.setDaemon(true);
        return t;
    });

    private final Object lockLimpieza = new Object();

    private static final int GRACIA_ESTRICTO_SEG = 10;
    private static final int SEGUNDOS_SIN_RASTRO = 120;

    private static final Pattern PROTOCOLO_WWW = Pattern.compile("https?://(www\\.)?");
    private static final Pattern PATH_QUERY    = Pattern.compile("/.*");

    // Navegadores conocidos para resolución jerárquica de foco (WEB prioriza sobre SYS).
    private static final Set<String> NAVEGADORES = Set.of(
        "chrome", "msedge", "firefox", "opera", "brave", "vivaldi",
        "chromium", "safari", "arc", "electron"
    );

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

        LOGGER.log(Level.INFO,
            "TimeTrackingService iniciado — usuario: {0} | Modo: {1} | Persistencia UPSERT: una fila por app por día",
            new Object[]{usuario, modoEstricto ? "ESTRICTO" : "NORMAL"});

        scheduler.scheduleAtFixedRate(this::cicloDeMantenimiento, 10, 10, TimeUnit.SECONDS);
    }

    // -------------------------------------------------------------------------
    // Puertos de entrada
    // -------------------------------------------------------------------------

    @Override
    public void reportarActividadSistema(String proceso, String titulo, int pid) {
        procesarActividad("SYS|" + proceso, proceso, detector.clasificar(proceso), titulo, -1, pid);
    }

    @Override
public void reportarEventoNavegador(String url, String titulo, int tabId) {
    // url ya viene recortada por WebSocketAdapter (dominio sin path/query)
    // extraerDominio() es idempotente para seguridad defensiva
    String dominio = extraerDominio(url);
    
    String nombreLegible;
    String tituloLimpio = (titulo != null) ? titulo.trim() : "";

    if (!tituloLimpio.isEmpty()) {
        // 1. DETECCIÓN DE URL FALSA PRIMERO (Sobre el texto original completo e intacto)
        boolean esUrlFalsa = tituloLimpio.startsWith("http://") 
                || tituloLimpio.startsWith("https://") 
                || tituloLimpio.startsWith("www.")
                || tituloLimpio.startsWith("file://")
                || tituloLimpio.contains("://")
                || tituloLimpio.equalsIgnoreCase(dominio)
                || tituloLimpio.equalsIgnoreCase(url)
                || tituloLimpio.matches(".*\\.[a-z]{2,6}(/|\\?).*");

        if (esUrlFalsa) {
            nombreLegible = "Web: " + dominio;
        } else {
            // 2. TRUNCADO DEFENSIVO SÓLO EN EL FLUJO DE NOMBRE LEGIBLE
            final int MAX_TITULO_LEN = 500;
            if (tituloLimpio.length() > MAX_TITULO_LEN) {
                String originalLen = String.valueOf(tituloLimpio.length());
                tituloLimpio = tituloLimpio.substring(0, MAX_TITULO_LEN);
                LOGGER.log(Level.WARNING, 
                    "[TITULO] Truncado de {0} a {1} caracteres para {2}",
                    new Object[]{originalLen, MAX_TITULO_LEN, dominio});
            }

            // Sanitizar caracteres de control sobre la cadena final acotada
            nombreLegible = "Web: " + tituloLimpio.replaceAll("[\\p{Cntrl}]", "").trim();
        }
    } else {
        nombreLegible = "Web: " + dominio;
    }
    
    procesarActividad(
        "WEB|" + dominio,
        nombreLegible,
        detector.clasificarUrl(url), // url ya recortada, clasificarUrl es idempotente
        dominio,
        tabId,
        -1
    );
}




    // -------------------------------------------------------------------------
    // NUEVO: Actualizar tabId en sesión existente (cambio de pestaña, mismo dominio)
    // -------------------------------------------------------------------------

    /**
     * Actualiza el tabId de una sesión activa existente sin reiniciar
     * el tiempo acumulado ni crear una nueva sesión.
     *
     * Invocado por WebSocketAdapter cuando detecta que el usuario navegó
     * al mismo dominio en una pestaña diferente (mismo dominio, tabId diferente).
     *
     * Si la sesión no existe o no está activa, no hace nada (el evento
     * normal de reportarEventoNavegador() manejará la creación).
     *
     * @param clave Clave de la sesión (ej: "WEB|youtube.com")
     * @param nuevoTabId Nuevo identificador de pestaña del navegador
     */
    public void actualizarTabId(String clave, int nuevoTabId) {
        if (nuevoTabId <= 0) return;

        synchronized (lockLimpieza) {
            SeguimientoActividad seg = activas.get(clave);
            if (seg != null && seg.tabId != nuevoTabId) {
                int tabIdAnterior = seg.tabId;
                seg.tabId = nuevoTabId;
                seg.ultimaVezVista = LocalDateTime.now();
                LOGGER.log(Level.FINE,
                    "[TABID] Actualizado {0}: {1} → {2} (misma sesión, tiempo preservado)",
                    new Object[]{clave, tabIdAnterior, nuevoTabId});
            }
        }
    }
    
        /**
     * Sincroniza la categoría de una sesión activa con el valor actual de la BD.
     *
     * Invocado por AppBlockerController cuando el usuario reclasifica una app.
     * Si la app tiene el foco activo o existe en sesiones, actualiza la categoría
     * inmediatamente para que el próximo ciclo de evaluación use la nueva clasificación.
     *
     * @param clave Clave de sesión (ej: "SYS|discord" o "WEB|youtube.com")
     * @param nuevaCategoria Categoría del dominio (PRODUCTIVO, NEUTRAL, DISTRACCION)
     */
    public void sincronizarCategoria(String clave, String nuevaCategoria) {
        if (clave == null || nuevaCategoria == null) return;

        synchronized (lockLimpieza) {
            // Actualizar sesión de existencia (si existe)
            SesionExistencia sesion = sesiones.get(clave);
            if (sesion != null && !nuevaCategoria.equals(sesion.categoria)) {
                sesion.categoria = nuevaCategoria;
                LOGGER.log(Level.INFO,
                    "[RECLASIFICACION] Sesión de existencia actualizada: {0} → {1}",
                    new Object[]{clave, nuevaCategoria});
            }

            // Actualizar foco activo (si existe)
            SeguimientoActividad seg = activas.get(clave);
            if (seg != null && !nuevaCategoria.equals(seg.categoria)) {
                seg.categoria = nuevaCategoria;
                // Resetear estado de distracción si cambia de DISTRACCION a no-DISTRACCION
                if (!Categoria.DISTRACCION.equals(nuevaCategoria)) {
                    seg.estado = EstadoDistraccion.INICIADA;
                }
                LOGGER.log(Level.INFO,
                    "[RECLASIFICACION] Foco activo actualizado: {0} → {1}",
                    new Object[]{clave, nuevaCategoria});
            }
        }
    }

    // -------------------------------------------------------------------------
    // FocoActivoPort — consulta del estado actual de foco
    // -------------------------------------------------------------------------

    @Override
    public int getPidFocoActivo() {
        return activas.values().stream()
            .findFirst()
            .map(seg -> seg.pid)
            .orElse(-1);
    }

    @Override
    public String getNombreFocoActivo() {
        return activas.values().stream()
            .findFirst()
            .map(seg -> seg.nombre)
            .orElse(null);
    }

    @Override
    public boolean esFocoWeb() {
        return activas.keySet().stream()
            .findFirst()
            .map(clave -> clave.startsWith("WEB|"))
            .orElse(false);
    }

    // -------------------------------------------------------------------------
    // Utilidades de dominio: jerarquía de foco
    // -------------------------------------------------------------------------

    private boolean esProcesoNavegador(String nombreProceso) {
        if (nombreProceso == null) return false;
        String n = nombreProceso.toLowerCase().replace(".exe", "");
        return NAVEGADORES.contains(n);
    }

    private String claveWebActivaReciente(LocalDateTime ahora) {
        Map.Entry<String, SeguimientoActividad> entry = activas.entrySet()
            .stream().findFirst().orElse(null);

        if (entry == null || !entry.getKey().startsWith("WEB|")) return null;

        long segundos = Duration.between(entry.getValue().ultimaVezVista, ahora).getSeconds();
        return segundos < 60 ? entry.getKey() : null;
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
                LOGGER.log(Level.FINE, "[EXISTENCIA] Nueva app detectada: {0} ({1})",
                    new Object[]{nombre, clave});
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

            // --- Foco activo con resolución jerárquica ---
            boolean esMismaApp    = activas.containsKey(clave);
            boolean esNavegadorSO = clave.startsWith("SYS|") && esProcesoNavegador(nombre);

            if (!esMismaApp) {
                // FIX UPSERT: Flush del foco anterior con upsert (acumula en BD)
                flushFocoActivo(ahora);

                String claveWebActiva = esNavegadorSO ? claveWebActivaReciente(ahora) : null;

                if (claveWebActiva != null) {
                    SeguimientoActividad segWeb = activas.get(claveWebActiva);
                    if (segWeb != null) {
                        segWeb.ultimaVezVista = ahora;
                    }

                    SesionExistencia sesionWeb = sesiones.get(claveWebActiva);
                    if (sesionWeb != null) {
                        sesionWeb.ultimaVezVista = ahora;
                    }

                    LOGGER.log(Level.FINE,
                        "[FOCO] Navegador ({0}) en primer plano. Web mantiene foco.",
                        nombre);
                    return;
                }

                // Nueva app toma el foco
                activas.clear();
                activas.put(clave, new SeguimientoActividad(nombre, detalle, categoria, ahora, tabId, pid));
                LOGGER.log(Level.FINE, "[FOCO] {0}", nombre);

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
    // Flush del foco activo (UPSERT — acumula en una sola fila)
    // -------------------------------------------------------------------------

    private void flushFocoActivo(LocalDateTime ahora) {
        if (activas.isEmpty()) return;

        Map.Entry<String, SeguimientoActividad> entry = activas.entrySet()
            .stream().findFirst().orElse(null);
        if (entry == null) return;

        String clave = entry.getKey();
        SeguimientoActividad seg = entry.getValue();

        long segsFoco = Duration.between(seg.inicio, ahora).getSeconds();
        long totalAFlush = seg.segundosAcumulados + segsFoco;

        if (totalAFlush > 0) {
            acumularChunkFoco(clave, seg, totalAFlush, ahora);
            LOGGER.log(Level.FINE, "[FLUSH] {0} → {1} acumulados via UPSERT",
                new Object[]{seg.nombre, fmt(totalAFlush)});
        }

        seg.segundosAcumulados = 0;
        seg.inicio = ahora;
    }

    // -------------------------------------------------------------------------
    // Ciclo de mantenimiento
    // -------------------------------------------------------------------------

    private void cicloDeMantenimiento() {
        try {
            synchronized (lockLimpieza) {
                avanzarRelojesYVerificar();
                purgarSesionesMuertas();
            }
        } catch (Exception e) {
            LOGGER.log(Level.SEVERE, "[MANTENIMIENTO] Error en ciclo de mantenimiento", e);
        }
    }

    private void avanzarRelojesYVerificar() {
        LocalDateTime ahora = LocalDateTime.now();

        // --- Segundo plano: acumula via UPSERT cada 10s ---
        for (SesionExistencia sesion : sesiones.values()) {
            long segsDesdeInicio = Duration.between(sesion.inicio, ahora).getSeconds();
            if (segsDesdeInicio > 5) {
                if (!activas.containsKey(sesion.clave)) {
                    acumularChunkExistencia(sesion, segsDesdeInicio, ahora);
                }
                sesion.inicio = ahora;
            }
        }

        // --- Foco activo: acumula en memoria, evalúa bloqueo ---
        activas.forEach((clave, seg) -> {
            long segsFoco = Duration.between(seg.inicio, ahora).getSeconds();

            if (segsFoco > 5) {
                seg.segundosAcumulados += segsFoco;
                seg.inicio = ahora;
            }

            // Lógica de bloqueo con tiempo total (memoria + histórico)
            if (Categoria.DISTRACCION.equals(seg.categoria)) {
                long focoHistorico  = obtenerFocoAcumulado(clave);
                long segsFocoActual = Duration.between(seg.inicio, ahora).getSeconds();
                long totalFoco      = focoHistorico + seg.segundosAcumulados + segsFocoActual;

                if (modoEstricto) {
                    aplicarModoEstricto(clave, seg, totalFoco);
                } else {
                    aplicarNiveles(clave, seg, totalFoco);
                }
            }
        });
    }

    // -------------------------------------------------------------------------
    // Persistencia UPSERT
    // -------------------------------------------------------------------------

    private void acumularChunkExistencia(SesionExistencia sesion, long segs, LocalDateTime ahora) {
        Registro reg = new Registro();
        reg.setUsuarioSistema(usuario);
        reg.setNombreActividad(sesion.nombre);
        reg.setCategoria("BACKGROUND_" + sesion.categoria);
        reg.setDetalle("Segundo plano");
        reg.setDuracionSeg(segs);
        reg.setFechaRegistro(ahora);
        repository.guardarOActualizar(reg);
    }

    private void acumularChunkFoco(String clave, SeguimientoActividad seg,
                                    long segs, LocalDateTime ahora) {
        Registro reg = new Registro();
        reg.setUsuarioSistema(usuario);
        reg.setNombreActividad(seg.nombre);
        reg.setCategoria(seg.categoria);
        reg.setDetalle("Foco activo: " + seg.detalle);
        reg.setDuracionSeg(segs);
        reg.setFechaRegistro(ahora);
        repository.guardarOActualizar(reg);

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
                long segsFoco = Duration.between(seg.inicio, ahora).getSeconds();
                long totalAFlush = seg.segundosAcumulados + segsFoco;
                if (totalAFlush > 0) {
                    acumularChunkFoco(clave, seg, totalAFlush, ahora);
                }
            } else {
                long segsDesdeInicio = Duration.between(sesion.inicio, ahora).getSeconds();
                if (segsDesdeInicio > 0) {
                    acumularChunkExistencia(sesion, segsDesdeInicio, ahora);
                }
            }

            clavesAEliminar.add(clave);
            LOGGER.log(Level.FINE, "[MANTENIMIENTO] Proceso cerrado/desaparecido: {0}", sesion.nombre);
        }

        clavesAEliminar.forEach(sesiones::remove);
        activas.keySet().removeIf(clave -> !sesiones.containsKey(clave));
    }

    // -------------------------------------------------------------------------
    // Ciclo de vida del servicio
    // -------------------------------------------------------------------------

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

            for (Map.Entry<String, SeguimientoActividad> entry : activas.entrySet()) {
                SeguimientoActividad seg = entry.getValue();
                long segsFoco = Duration.between(seg.inicio, ahora).getSeconds();
                long totalAFlush = seg.segundosAcumulados + segsFoco;
                if (totalAFlush > 0) {
                    acumularChunkFoco(entry.getKey(), seg, totalAFlush, ahora);
                }
            }

            for (SesionExistencia sesion : sesiones.values()) {
                if (!activas.containsKey(sesion.clave)) {
                    long segs = Duration.between(sesion.inicio, ahora).getSeconds();
                    if (segs > 0) {
                        acumularChunkExistencia(sesion, segs, ahora);
                    }
                }
            }
        }

        LOGGER.log(Level.INFO, "[FIN] Seguimiento finalizado — flush completo realizado via UPSERT.");
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
        long segundosAcumulados = 0;

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