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
 * 1. TODAS las apps detectadas por el escáner tienen un reloj de existencia continuo,
 *    independientemente de si están clasificadas en la BD o no.
 * 2. Solo la app con FOCO ACTIVO puede ser evaluada para bloqueo.
 * 3. Las apps en segundo plano se registran con categoría "BACKGROUND_X" → nunca se bloquean.
 * 4. Si el usuario está AUSENTE (sin reportes en SEGUNDOS_AUSENCIA_USUARIO), los relojes se congelan.
 * 5. El bloqueo se dispara únicamente cuando una DISTRACCION supera segundosBloqueo con foco activo.
 *
 * ESTRUCTURA DE DATOS:
 * - 'sesiones' → una entrada por cada app detectada. Clave: "SYS|proceso" o "WEB|dominio".
 *               Sobrevive mientras el proceso exista en el sistema.
 *               FIX: SesionExistencia almacena su propia clave para evitar reconstrucciones
 *               que fallaban en versiones anteriores (especialmente para apps web).
 *
 * - 'activas'  → máximo UNA entrada: la app con foco en este momento.
 *               Usa la misma clave que 'sesiones' → búsqueda directa, sin ambigüedad.
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

    private static final int GRACIA_ESTRICTO_SEG       = 10;
    // Sin reportes del escáner durante este tiempo → usuario ausente → relojes congelados
    private static final int SEGUNDOS_AUSENCIA_USUARIO  = 300;
    // Sin reportes durante este tiempo → proceso considerado cerrado → sesión purgada
    private static final int SEGUNDOS_SIN_RASTRO        = 120;

    private static final Pattern PROTOCOLO_WWW = Pattern.compile("https?://(www\\.)?");
    private static final Pattern PATH_QUERY    = Pattern.compile("/.*");

    // Proxy de "usuario presente": se actualiza en cada reporte del escáner.
    private volatile LocalDateTime ultimoInputUsuario = LocalDateTime.now();

    public TimeTrackingService(DistractionDetector detector,
                               RegistroRepositoryPort repository,
                               NotificacionPort notificacion,
                               KillerPort killer,
                               int segundosAviso,
                               int segundosBloqueo,
                               int segundosPausa,   // No usado: cierre inmediato al superar segundosBloqueo
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
        System.out.println("   Regla: reloj continuo por app. Bloqueo solo con foco. Pausa en ausencia.");
    }

    // -------------------------------------------------------------------------
    // Puertos de entrada
    // -------------------------------------------------------------------------

    @Override
    public void reportarActividadSistema(String proceso, String titulo, int pid) {
        // Cada reporte del escáner confirma que el usuario está presente
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
            // Se crea la primera vez que el escáner detecta esta app.
            // FIX: guardamos 'clave' dentro de SesionExistencia para no tener
            // que reconstruirla después desde el nombre (fallaba para webs).
            sesiones.computeIfAbsent(clave, k -> {
                System.out.println("[EXISTENCIA] Nueva app detectada: " + nombre + " (" + clave + ")");
                return new SesionExistencia(clave, nombre, categoria, ahora);
            });

            SesionExistencia sesion = sesiones.get(clave);
            if (sesion != null) {
                sesion.ultimaVezVista = ahora;
                // La categoría puede actualizarse si se modifica la BD en caliente
                sesion.categoria = categoria;
            }

            // --- Foco activo ---
            // El escáner solo reporta la ventana en primer plano, así que esta app
            // ES la que tiene el foco. Si es distinta a la anterior, sustituimos.
            boolean esMismaApp = activas.containsKey(clave);
            if (!esMismaApp) {
                // FIX: sustituimos directamente en lugar de clear()+computeIfAbsent()
                // para que la operación sea más limpia dentro del bloque sincronizado.
                activas.clear();
                SeguimientoActividad nueva = new SeguimientoActividad(
                    nombre, detalle, categoria, ahora, tabId, pid
                );
                activas.put(clave, nueva);
                System.out.println("[FOCO] " + nombre);

                if (Categoria.SIN_CLASIFICAR.equals(categoria)) {
                    notificacion.notificarAppSinClasificar(nombre, detalle);
                }
            } else {
                // La misma app sigue en foco: actualizamos heartbeat y metadatos
                SeguimientoActividad seg = activas.get(clave);
                if (seg != null) {
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
                // FIX 1: No resetear inicios. Solo no avanzar relojes en este ciclo.
                // El tiempo de ausencia no se acumula, pero el contexto previo se preserva.
                // Cuando el usuario vuelve, el próximo ciclo continúa desde donde estaba.
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

        // --- Avanzar relojes de existencia (todas las apps, foco o no) ---
        for (Map.Entry<String, SesionExistencia> entry : sesiones.entrySet()) {
            String clave            = entry.getKey();
            SesionExistencia sesion = entry.getValue();

            long segsDesdeInicio = Duration.between(sesion.inicio, ahora).getSeconds();
            if (segsDesdeInicio > 5) {
                // FIX: usamos sesion.clave para determinar si tiene foco.
                // Antes se reconstruía la clave desde sesion.nombre, lo que fallaba
                // para webs porque el nombre es "Web: Título", no "WEB|dominio".
                boolean tieneFoco = activas.containsKey(sesion.clave);
                persistirChunkExistencia(sesion, segsDesdeInicio, ahora, tieneFoco);
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

            // Bloqueo solo si es distracción y tiene foco activo
            if (Categoria.DISTRACCION.equals(seg.categoria)) {
                // FIX 2: Calcular totalFoco consistentemente.
                // segsFoco ya contiene el tiempo desde el último inicio (incluyendo
                // el fragmento actual que aún no ha sido rotado). Si segsFoco > 5,
                // ya rotamos arriba y reseteamos inicio, por lo que segsFoco ahora
                // es ~0-10s. El focoHistorico ya incluye el chunk rotado.
                // Si segsFoco <= 5, no rotamos, y segsFoco es el tiempo real desde inicio.
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
     * Persiste un chunk de existencia.
     * Si la app no tiene foco en este momento, la categoría se prefija con "BACKGROUND_"
     * para que el reporte refleje que estuvo activa pero en segundo plano.
     */
    private void persistirChunkExistencia(SesionExistencia sesion, long segs,
                                          LocalDateTime ahora, boolean tieneFoco) {
        Registro reg = new Registro();
        reg.setUsuarioSistema(usuario);
        reg.setNombreActividad(sesion.nombre);
        reg.setCategoria(tieneFoco ? sesion.categoria : "BACKGROUND_" + sesion.categoria);
        reg.setDetalle(tieneFoco ? "Foco activo" : "Segundo plano");
        reg.setDuracionSeg(segs);
        reg.setFechaRegistro(ahora);
        repository.guardar(reg);
    }

    /**
     * Persiste un chunk de foco y acumula el tiempo en la sesión de existencia.
     *
     * FIX: recibe 'clave' directamente para buscar la sesión sin reconstruirla
     * desde el nombre. La reconstrucción fallaba para apps web porque seg.nombre
     * es "Web: Título de la página", no la clave "WEB|dominio".
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

        // FIX: búsqueda directa por clave. Siempre encuentra la sesión porque
        // 'clave' en activas es la misma que se usó al crear la entrada en 'sesiones'.
        SesionExistencia sesion = sesiones.get(clave);
        if (sesion != null) {
            sesion.segundosFocoAcumulado += segs;
        } else {
            LOGGER.log(Level.WARNING,
                "[FOCO] Sin sesión de existencia para clave {0}. Acumulado no guardado.", clave);
        }
    }

    /** Devuelve el tiempo de foco acumulado históricamente para una clave. */
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

        // Verificación defensiva: solo cerrar si la app aún tiene el foco
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
     * Elimina sesiones de apps que llevan más de SEGUNDOS_SIN_RASTRO sin reportarse.
     * Se considera que el proceso fue cerrado por el usuario o el SO.
     * Si la app purgada tenía el foco, también se limpia 'activas'.
     */
    private void purgarSesionesMuertas() {
        List<String> clavesAEliminar = new ArrayList<>();
        LocalDateTime ahora = LocalDateTime.now();

        for (Map.Entry<String, SesionExistencia> entry : sesiones.entrySet()) {
            String clave            = entry.getKey();
            SesionExistencia sesion = entry.getValue();

            long segsSinRastro = Duration.between(sesion.ultimaVezVista, ahora).getSeconds();
            if (segsSinRastro > SEGUNDOS_SIN_RASTRO) {
                long segsDesdeInicio = Duration.between(sesion.inicio, ahora).getSeconds();
                if (segsDesdeInicio > 0) {
                    boolean tieneFoco = activas.containsKey(sesion.clave);
                    persistirChunkExistencia(sesion, segsDesdeInicio, ahora, tieneFoco);
                }
                clavesAEliminar.add(clave);
                System.out.println("[MANTENIMIENTO] Proceso cerrado/desaparecido: " + sesion.nombre);
            }
        }

        clavesAEliminar.forEach(sesiones::remove);

        // Si la app con foco fue purgada, limpiar activas también
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
            // Persistir fragmentos pendientes de todas las sesiones de existencia
            for (SesionExistencia sesion : sesiones.values()) {
                long segs = Duration.between(sesion.inicio, ahora).getSeconds();
                if (segs > 0) {
                    boolean tieneFoco = activas.containsKey(sesion.clave);
                    persistirChunkExistencia(sesion, segs, ahora, tieneFoco);
                }
            }
            // Persistir fragmento de foco pendiente
            for (Map.Entry<String, SeguimientoActividad> entry : activas.entrySet()) {
                long segs = Duration.between(entry.getValue().inicio, ahora).getSeconds();
                if (segs > 0) {
                    persistirChunkFoco(entry.getKey(), entry.getValue(), segs, ahora);
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
        INICIADA, AVISO_PREVENTIVO, BLOQUEO_SESION
    }

    /*
     * Seguimiento de FOCO. Solo existe mientras la app es foreground.
     * Si pierde el foco se elimina; si lo recupera se crea una nueva instancia.
     * El histórico de tiempo de foco vive en SesionExistencia.segundosFocoAcumulado.
     */
    private static class SeguimientoActividad {
        String nombre, detalle, categoria;
        LocalDateTime inicio;
        int tabId;
        int pid;
        EstadoDistraccion estado = EstadoDistraccion.INICIADA;

        SeguimientoActividad(String nombre, String detalle, String categoria,
                             LocalDateTime inicio, int tabId, int pid) {
            this.nombre         = nombre;
            this.detalle        = detalle;
            this.categoria      = categoria;
            this.inicio         = inicio;
            this.tabId          = tabId;
            this.pid            = pid;
        }
    }

    /**
     * Sesión de EXISTENCIA. Sobrevive mientras el proceso exista en el sistema,
     * tenga foco o no.
     *
     * FIX clave: almacena su propia 'clave' ("SYS|proceso" o "WEB|dominio") para que
     * las búsquedas en 'sesiones' y 'activas' sean siempre directas. La versión
     * anterior reconstruía la clave desde sesion.nombre, lo que fallaba para apps
     * web porque el nombre es "Web: Título de la página", no el dominio.
     */
    private static class SesionExistencia {
        final String clave;         // "SYS|proceso" o "WEB|dominio" — nunca cambia
        final String nombre;        // Nombre legible para logs y BD — nunca cambia
        String categoria;           // Puede actualizarse si la BD cambia en caliente
        LocalDateTime inicio;       // Inicio del chunk actual (se resetea al rotar)
        LocalDateTime ultimaVezVista;
        long segundosFocoAcumulado; // Tiempo total con foco, acumulado históricamente

        SesionExistencia(String clave, String nombre, String categoria, LocalDateTime inicio) {
            this.clave                 = clave;
            this.nombre                = nombre;
            this.categoria             = categoria;
            this.inicio                = inicio;
            this.ultimaVezVista        = inicio;
            this.segundosFocoAcumulado = 0L;
        }
    }
}