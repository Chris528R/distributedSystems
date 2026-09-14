package escom.chris.concurrency;

import escom.chris.audio.AudioChunk;
import escom.chris.math.Complex;
import escom.chris.math.FastFourierTransform;
import escom.chris.math.WindowFunction;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Future;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Pool global de cómputo FFT compartido por todos los clientes.
 * Tamaño fijo acotado al hardware; las tareas se desacoplan vía cola acotada.
 */
public final class GlobalFftWorkerPool implements AutoCloseable {

    private final ThreadPoolExecutor executor;
    private final AtomicLong rejectedTasks = new AtomicLong(0);

    public GlobalFftWorkerPool(int poolSize, int queueCapacity) {
        if (poolSize < 1) {
            throw new IllegalArgumentException("poolSize debe ser >= 1");
        }
        if (queueCapacity < 1) {
            throw new IllegalArgumentException("queueCapacity debe ser >= 1");
        }
        this.executor = new ThreadPoolExecutor(
                poolSize,
                poolSize,
                0L,
                TimeUnit.MILLISECONDS,
                new ArrayBlockingQueue<>(queueCapacity),
                r -> {
                    Thread t = new Thread(r, "GlobalFFT-Worker");
                    t.setDaemon(false);
                    return t;
                },
                // Backpressure: el hilo I/O espera hueco en la cola en vez de abortar.
                // El rechazo real se mide en ServerSocket backlog / I/O pool.
                (r, ex) -> {
                    try {
                        if (ex.isShutdown()) {
                            rejectedTasks.incrementAndGet();
                            throw new RejectedExecutionException("Pool FFT cerrado");
                        }
                        ex.getQueue().put(r);
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        rejectedTasks.incrementAndGet();
                        throw new RejectedExecutionException("Interrumpido esperando cola FFT", ie);
                    }
                }
        );
    }

    public int getPoolSize() {
        return executor.getCorePoolSize();
    }

    public long getRejectedTaskCount() {
        return rejectedTasks.get();
    }

    public int getQueueSize() {
        return executor.getQueue().size();
    }

    /**
     * Trocea la señal, envía cada chunk al pool compartido y espera con CountDownLatch.
     */
    public SharedResultMatrix compute(double[] samples, int windowSize, int hopSize)
            throws InterruptedException {
        int numChunks = Math.max(1, (samples.length - windowSize) / hopSize + 1);
        SharedResultMatrix resultMatrix = new SharedResultMatrix(windowSize / 2, numChunks);
        CountDownLatch latch = new CountDownLatch(numChunks);
        List<Future<?>> futures = new ArrayList<>(numChunks);

        for (int c = 0; c < numChunks; c++) {
            int start = c * hopSize;
            int end = Math.min(start + windowSize, samples.length);
            double[] windowSamples = new double[windowSize];
            System.arraycopy(samples, start, windowSamples, 0, end - start);
            AudioChunk chunk = new AudioChunk(c, windowSamples, start);

            Future<?> future = executor.submit(() -> {
                try {
                    processChunk(chunk, resultMatrix);
                } finally {
                    latch.countDown();
                }
            });
            futures.add(future);
        }

        latch.await();

        for (Future<?> future : futures) {
            try {
                future.get();
            } catch (Exception e) {
                throw new IllegalStateException("Fallo en worker FFT compartido", e);
            }
        }

        return resultMatrix;
    }

    private static void processChunk(AudioChunk chunk, SharedResultMatrix resultMatrix) {
        double[] windowed = WindowFunction.apply(chunk.getSamples(), WindowFunction.Type.HANN);
        Complex[] spectrum = FastFourierTransform.transform(windowed);
        double[] magnitudesDb = FastFourierTransform.magnitudeSpectrumDb(spectrum);
        resultMatrix.setColumn(chunk.getIndex(), magnitudesDb);
    }

    @Override
    public void close() {
        executor.shutdown();
        try {
            if (!executor.awaitTermination(30, TimeUnit.SECONDS)) {
                executor.shutdownNow();
            }
        } catch (InterruptedException e) {
            executor.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }
}
