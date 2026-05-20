package org.appsentinel.infrastructure.adapter.out;

import org.appsentinel.domain.model.Registro;
import org.appsentinel.domain.port.out.RegistroRepositoryPort;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * BufferedRegistroRepositoryAdapter: Decorador de SALIDA que desacopla
 * escritura de lectura mediante un buffer en memoria + worker dedicado.
 */
public class BufferedRegistroRepositoryAdapter implements RegistroRepositoryPort {

    private static final Logger LOGGER = Logger.getLogger(BufferedRegistroRepositoryAdapter.class.getName());

    private final RegistroRepositoryPort delegate;
    private final BlockingQueue<Registro> buffer;
    private final PersistenceWorker worker;

    public BufferedRegistroRepositoryAdapter(RegistroRepositoryPort delegate, int capacidad) {
        this.delegate = delegate;
        this.buffer = new LinkedBlockingQueue<>(capacidad);
        this.worker = new PersistenceWorker(buffer, delegate);
        this.worker.start();
    }

    @Override
    public void guardar(Registro registro) {
        if (registro == null) return;
        if (!buffer.offer(registro)) {
            LOGGER.log(Level.WARNING,
                "[BUFFER] Cola llena ({0} registros). Descartando registro de {1}.",
                new Object[]{buffer.size(), registro.getNombreActividad()});
        }
    }

    @Override
    public void guardarBatch(List<Registro> registros) {
        if (registros == null) return;
        for (Registro r : registros) {
            guardar(r);
        }
    }

    @Override
    public List<Registro> obtenerTodosHoy(String usuario) {
        return delegate.obtenerTodosHoy(usuario);
    }

    @Override
    public List<Registro> obtenerPorCategoria(String usuario, String categoria) {
        return delegate.obtenerPorCategoria(usuario, categoria);
    }

    public void shutdown() {
        LOGGER.log(Level.INFO, "[BUFFER] Iniciando shutdown del buffer de persistencia...");
        worker.shutdown();
        LOGGER.log(Level.INFO, "[BUFFER] Buffer detenido. Registros restantes en cola: {0}", buffer.size());
    }

    // =========================================================================
    // PERSISTENCE WORKER — Clase privada interna (no es adaptador, no sale de aquí)
    // =========================================================================

    /**
     * Worker dedicado que drena la cola de registros y los persiste en batch.
     * Vida útil atada exclusivamente a BufferedRegistroRepositoryAdapter.
     */
    private static class PersistenceWorker implements Runnable {

        private static final int BATCH_SIZE = 50;
        private static final long FLUSH_INTERVAL_MS = 5000L;

        private final BlockingQueue<Registro> buffer;
        private final RegistroRepositoryPort delegate;
        private final Thread thread;
        private volatile boolean running = true;

        PersistenceWorker(BlockingQueue<Registro> buffer, RegistroRepositoryPort delegate) {
            this.buffer = buffer;
            this.delegate = delegate;
            this.thread = new Thread(this, "PersistenceWorker");
            this.thread.setDaemon(true);
        }

        void start() {
            thread.start();
            LOGGER.log(Level.INFO, "[WORKER] Iniciado. Batch={0}, Intervalo={1}ms",
                new Object[]{BATCH_SIZE, FLUSH_INTERVAL_MS});
        }

        void shutdown() {
            running = false;
            thread.interrupt();
            try {
                thread.join(10_000L);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }

        @Override
        public void run() {
            List<Registro> batch = new ArrayList<>(BATCH_SIZE);
            long lastFlush = System.currentTimeMillis();

            while (running) {
                try {
                    Registro reg = buffer.poll(FLUSH_INTERVAL_MS, TimeUnit.MILLISECONDS);
                    if (reg != null) {
                        batch.add(reg);
                    }

                    long now = System.currentTimeMillis();
                    boolean debeFlush = batch.size() >= BATCH_SIZE
                            || (!running && !batch.isEmpty())
                            || (reg == null && !batch.isEmpty() && (now - lastFlush) >= FLUSH_INTERVAL_MS);

                    if (debeFlush) {
                        flush(batch);
                        batch.clear();
                        lastFlush = now;
                    }

                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    LOGGER.log(Level.FINE, "[WORKER] Interrumpido, saliendo del loop principal.");
                    break;
                }
            }

            buffer.drainTo(batch);
            if (!batch.isEmpty()) {
                LOGGER.log(Level.INFO, "[WORKER] Flush final de cierre: {0} registros pendientes.", batch.size());
                flush(batch);
            }

            LOGGER.log(Level.INFO, "[WORKER] Detenido.");
        }

        private void flush(List<Registro> batch) {
            try {
                delegate.guardarBatch(batch);
            } catch (Exception e) {
                LOGGER.log(Level.SEVERE, "[WORKER] Fallo al flushear batch de {0} registros. Se perderán.", batch.size());
                LOGGER.log(Level.SEVERE, "[WORKER] Causa: {0}", e.getMessage());
            }
        }
    }
}