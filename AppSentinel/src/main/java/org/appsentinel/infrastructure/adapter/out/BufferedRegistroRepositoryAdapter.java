package org.appsentinel.infrastructure.adapter.out;

import org.appsentinel.domain.model.Registro;
import org.appsentinel.domain.model.ResumenActividad;
import org.appsentinel.domain.port.out.RegistroRepositoryPort;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * BufferedRegistroRepositoryAdapter: Decorador de SALIDA que desacopla
 * escritura de lectura mediante un buffer en memoria + worker dedicado.
 *
 * ARQUITECTURA HEXAGONAL:
 * - Implementa RegistroRepositoryPort, por lo que el dominio (TimeTrackingService)
 *   lo consume sin saber que hay un buffer detrás.
 * - Las escrituras (guardar) son NON-BLOCKING: encolan en LinkedBlockingQueue.
 * - Las lecturas (obtener*, obtenerResumenPorApp) pasan DIRECTO al adaptador subyacente
 *   (PostgreSQL), sin interferir con el buffer de escritura.
 * - Un PersistenceWorker interno consume la cola y hace batch INSERTs.
 *
 * SEPARACION ESCRITURA/LECTURA:
 * - Escáner / Dominio → guardar() → Buffer en memoria → Worker → Batch INSERT
 * - UI JavaFX → obtenerResumenPorApp() → PostgreSQL directo (lectura fresca, agrupada)
 *
 * ANTI-SATURACION:
 * - En lugar de N INSERTs sueltos por ciclo de escaneo, se acumulan en memoria
 *   y se flushean en batches de 50 registros cada 5 segundos.
 * - Si el buffer se llena (capacidad configurable), los registros excedentes se
 *   descartan con log de advertencia. Esto evita OutOfMemoryError bajo estrés.
 *
 * FIX 1.1: PersistenceWorker implementado como clase Runnable interna.
 * - Hilo daemon dedicado que consume la cola via take() bloqueante.
 * - Flush por batch size (50), timeout (5s), o shutdown.
 * - No es un record: requiere estado mutable (running flag) y un Thread.
 *
 * FIX 1.3: Shutdown graceful con poll manual de residuales.
 * - El worker hace join(5s) y flush de su batch interno.
 * - Después, el adaptador principal hace poll() manual de la cola restante
 *   y flushea directamente al delegate por si el worker murió antes de vaciar todo.
 * - Garantiza zero data loss en apagado normal.
 */
public class BufferedRegistroRepositoryAdapter implements RegistroRepositoryPort {

    private static final Logger LOGGER = Logger.getLogger(BufferedRegistroRepositoryAdapter.class.getName());

    // FIX 1.1: Constantes de configuración del worker
    private static final int BATCH_SIZE = 50;
    private static final int FLUSH_TIMEOUT_MS = 5000;

    private final RegistroRepositoryPort delegate;
    private final BlockingQueue<Registro> buffer;
    private final PersistenceWorker worker;

    /**
     * @param delegate  Adaptador real de persistencia (ej: PostgreSQLRepositoryAdapter)
     * @param capacidad Máximo de registros en memoria antes de descartar
     */
    public BufferedRegistroRepositoryAdapter(RegistroRepositoryPort delegate, int capacidad) {
        this.delegate = delegate;
        this.buffer = new LinkedBlockingQueue<>(capacidad);
        this.worker = new PersistenceWorker(buffer, delegate);
        this.worker.start();
    }

    /**
     * Escritura NON-BLOCKING. Encola el registro en memoria.
     * El hilo de escaneo/dominio nunca espera a PostgreSQL.
     */
    @Override
    public void guardar(Registro registro) {
        if (registro == null) return;
        if (!buffer.offer(registro)) {
            LOGGER.log(Level.WARNING,
                "[BUFFER] Cola llena ({0} registros). Descartando registro de {1}. " +
                "Aumentar capacidad o revisar latencia de red con PostgreSQL.",
                new Object[]{buffer.size(), registro.getNombreActividad()});
        }
    }

    /**
     * Batch insert via buffer. Cada registro se encola individualmente;
     * el worker los agrupa automáticamente.
     */
    @Override
    public void guardarBatch(List<Registro> registros) {
        if (registros == null) return;
        for (Registro r : registros) {
            guardar(r);
        }
    }

    /**
     * Lectura DIRECTA. La UI consulta PostgreSQL sin pasar por el buffer.
     * Esto garantiza que el dashboard muestre datos frescos y no se bloquee
     * con el hilo de escritura.
     */
    @Override
    public List<Registro> obtenerTodosHoy(String usuario) {
        return delegate.obtenerTodosHoy(usuario);
    }

    @Override
    public List<Registro> obtenerPorCategoria(String usuario, String categoria) {
        return delegate.obtenerPorCategoria(usuario, categoria);
    }

    /**
     * FIX UI: Resumen agrupado por app. delegación directa a PostgreSQL.
     *
     * No pasa por el buffer de escritura. Va directo al adaptador crudo
     * que ejecuta el GROUP BY en la base de datos.
     */
    @Override
    public Map<String, ResumenActividad> obtenerResumenPorApp(String usuario) {
        return delegate.obtenerResumenPorApp(usuario);
    }

    /**
     * FIX 1.3: Shutdown graceful con doble garantía de flush.
     *
     * Paso 1: Detener el worker (interrupt + join + flush de batch interno).
     * Paso 2: Poll manual de la cola restante por si el worker murió
     *          antes de vaciar todo (timeout de join expirado).
     * Paso 3: Flush directo al delegate de cualquier residual.
     *
     * Garantiza zero data loss en apagado normal.
     */
    public void shutdown() {
        LOGGER.log(Level.INFO, "[BUFFER] Iniciando shutdown del buffer de persistencia...");

        // Paso 1: Detener worker (flush de su batch interno)
        worker.shutdown();

        // Paso 2: Poll manual de residuales en la cola
        List<Registro> residuales = new ArrayList<>();
        buffer.drainTo(residuales);

        // Paso 3: Flush directo de residuales al delegate
        if (!residuales.isEmpty()) {
            LOGGER.log(Level.INFO, "[BUFFER] Flush manual de {0} registros residuales", residuales.size());
            try {
                delegate.guardarBatch(residuales);
            } catch (Exception e) {
                LOGGER.log(Level.SEVERE, "[BUFFER] Error en flush manual de residuales: " + residuales.size() + " registros perdidos", e);
            }
        }

        LOGGER.log(Level.INFO, "[BUFFER] Buffer detenido. Cola final: {0} registros", buffer.size());
    }

    // =========================================================================
    // FIX 1.1: PersistenceWorker — Clase Runnable interna
    // =========================================================================

    /**
     * PersistenceWorker: Hilo dedicado que consume la cola de registros
     * y los persiste en batches via el adaptador subyacente.
     *
     * DISEÑO:
     * - Thread daemon para no bloquear cierre de JVM.
     * - Loop principal: poll() con timeout → acumular en lista → flush condicional.
     * - Flush triggers: batch size alcanzado, timeout expirado, o shutdown solicitado.
     * - Shutdown: interrumpe el hilo, hace flush final de registros pendientes.
     *
     * THREAD-SAFETY:
     * - La cola (LinkedBlockingQueue) es thread-safe por diseño.
     * - La lista de acumulación (batch) es local al hilo del worker.
     * - El flag 'running' es volatile para visibilidad cross-thread.
     */
    private static class PersistenceWorker implements Runnable {

        private final Thread hilo;
        private final BlockingQueue<Registro> cola;
        private final RegistroRepositoryPort delegate;
        private volatile boolean running = true;

        PersistenceWorker(BlockingQueue<Registro> cola, RegistroRepositoryPort delegate) {
            this.cola = cola;
            this.delegate = delegate;
            this.hilo = new Thread(this, "PersistenceWorker");
            this.hilo.setDaemon(true);
        }

        void start() {
            hilo.start();
            LOGGER.log(Level.INFO, "[WORKER] PersistenceWorker iniciado (batch={0}, timeout={1}ms)",
                new Object[]{BATCH_SIZE, FLUSH_TIMEOUT_MS});
        }

        void shutdown() {
            running = false;
            hilo.interrupt(); // Despertar si está bloqueado en poll()
            try {
                hilo.join(5000); // Esperar a que termine (max 5s)
                if (hilo.isAlive()) {
                    LOGGER.log(Level.WARNING, "[WORKER] Hilo no terminó a tiempo, forzando...");
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }

        @Override
        public void run() {
            List<Registro> batch = new ArrayList<>(BATCH_SIZE);
            long ultimoFlush = System.currentTimeMillis();

            while (running || !cola.isEmpty()) {
                try {
                    // poll() con timeout para permitir flush periódico
                    Registro reg = cola.poll(FLUSH_TIMEOUT_MS, TimeUnit.MILLISECONDS);

                    if (reg != null) {
                        batch.add(reg);
                    }

                    long ahora = System.currentTimeMillis();
                    boolean timeoutExpirado = (ahora - ultimoFlush) >= FLUSH_TIMEOUT_MS;
                    boolean batchCompleto = batch.size() >= BATCH_SIZE;
                    boolean shutdownPendiente = !running && !cola.isEmpty();

                    if (!batch.isEmpty() && (batchCompleto || timeoutExpirado || shutdownPendiente)) {
                        flush(batch);
                        batch.clear();
                        ultimoFlush = ahora;
                    }

                } catch (InterruptedException e) {
                    // Interrupción durante poll(): reevaluar condición de salida
                    Thread.currentThread().interrupt();
                    LOGGER.log(Level.FINE, "[WORKER] Interrumpido, reevaluando...");
                }
            }

            // Flush final de cualquier registro residual en el batch interno
            if (!batch.isEmpty()) {
                flush(batch);
            }

            LOGGER.log(Level.INFO, "[WORKER] PersistenceWorker finalizado.");
        }

        private void flush(List<Registro> batch) {
            try {
                delegate.guardarBatch(batch);
                LOGGER.log(Level.FINE, "[WORKER] Batch flush: {0} registros", batch.size());
            } catch (Exception e) {
                LOGGER.log(Level.SEVERE, "[WORKER] Error en batch flush de " + batch.size() + " registros", e);
            }
        }
    }
}