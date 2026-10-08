package escom.chris;

import escom.chris.audio.AudioChunk;
import escom.chris.audio.WavReader;
import escom.chris.concurrency.FFTWorker;
import escom.chris.concurrency.SharedResultMatrix;
import escom.chris.concurrency.WavProducerTask;
import escom.chris.math.WindowFunction;

import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Orquestador del pipeline FFT (Productor-Consumidor / Master-Worker).
 */
public final class FFTPipelineManager {

    public record PipelineMetrics(
            double readTimeMs,
            double computeTimeMs,
            double exportTimeMs,
            double totalTimeMs,
            int chunksProcessed,
            double chunksPerSecond
    ) {}

    private final String wavPath;
    private final int numWorkers;
    private final int windowSize;
    private final int hopSize;
    private final String outputCsv;

    public FFTPipelineManager(String wavPath, int numWorkers, int windowSize, int hopSize, String outputCsv) {
        this.wavPath = wavPath;
        this.numWorkers = numWorkers;
        this.windowSize = windowSize;
        this.hopSize = hopSize;
        this.outputCsv = outputCsv;
    }

    public void run() throws Exception {
        execute(true, true);
    }

    public PipelineMetrics execute(boolean verbose, boolean exportCsv) throws Exception {
        if (verbose) {
            System.out.println("=== FFT Concurrente: Productor-Consumidor / Master-Worker ===");
            System.out.printf("Archivo: %s | Workers: %d | Ventana: %d | Hop: %d%n",
                    wavPath, numWorkers, windowSize, hopSize);
        }

        long tReadStart = System.nanoTime();
        WavReader.WavData wav = new WavReader().read(wavPath);
        long tReadEnd = System.nanoTime();
        double readMs = (tReadEnd - tReadStart) / 1_000_000.0;

        if (verbose) {
            System.out.printf("Lectura WAV: %d muestras, %d Hz, %d canal(es) [%.2f ms]%n",
                    wav.samples.length, wav.sampleRate, wav.channels, readMs);
        }

        int numChunks = Math.max(1, (wav.samples.length - windowSize) / hopSize + 1);

        BlockingQueue<AudioChunk> queue = new ArrayBlockingQueue<>(numWorkers * 4);
        SharedResultMatrix resultMatrix = new SharedResultMatrix(windowSize / 2, numChunks);
        AtomicInteger processedChunks = new AtomicInteger(0);

        Thread producer = new Thread(
                new WavProducerTask(wav.samples, windowSize, hopSize, numChunks, numWorkers, queue),
                "Producer-WavChunker");

        ExecutorService workerPool = Executors.newFixedThreadPool(numWorkers);
        for (int i = 0; i < numWorkers; i++) {
            workerPool.submit(new FFTWorker(i, queue, resultMatrix, WindowFunction.Type.HANN, processedChunks));
        }

        long tComputeStart = System.nanoTime();
        producer.start();
        producer.join();

        workerPool.shutdown();
        boolean finished = workerPool.awaitTermination(5, TimeUnit.MINUTES);
        long tComputeEnd = System.nanoTime();

        if (!finished && verbose) {
            System.err.println("ADVERTENCIA: los workers no terminaron dentro del tiempo límite.");
        }

        double computeMs = (tComputeEnd - tComputeStart) / 1_000_000.0;
        double chunksPerSec = processedChunks.get() / (computeMs / 1000.0);

        if (verbose) {
            System.out.printf("Chunks procesados: %d / %d [%.2f ms, %.1f chunks/seg]%n",
                    processedChunks.get(), numChunks, computeMs, chunksPerSec);
        }

        double exportMs = 0.0;
        if (exportCsv && outputCsv != null && !outputCsv.isEmpty()) {
            long tExportStart = System.nanoTime();
            resultMatrix.exportToCsv(outputCsv);
            long tExportEnd = System.nanoTime();
            exportMs = (tExportEnd - tExportStart) / 1_000_000.0;

            if (verbose) {
                System.out.printf("Espectrograma exportado a '%s' [%.2f ms]%n", outputCsv, exportMs);
            }
        }

        double totalMs = readMs + computeMs + exportMs;
        if (verbose) {
            System.out.printf("Tiempo total del pipeline: %.2f ms%n", totalMs);
        }

        return new PipelineMetrics(readMs, computeMs, exportMs, totalMs, processedChunks.get(), chunksPerSec);
    }

    /**
     * Ejecuta el pipeline concurrente de cálculo FFT sobre un arreglo de muestras en memoria.
     * Utilizado por el servidor REST al recibir las muestras en la petición.
     */
    public static SharedResultMatrix compute(double[] samples, int windowSize, int hopSize, int numWorkers) throws Exception {
        int numChunks = Math.max(1, (samples.length - windowSize) / hopSize + 1);

        BlockingQueue<AudioChunk> queue = new ArrayBlockingQueue<>(numWorkers * 4);
        SharedResultMatrix resultMatrix = new SharedResultMatrix(windowSize / 2, numChunks);
        AtomicInteger processedChunks = new AtomicInteger(0);

        Thread producer = new Thread(
                new WavProducerTask(samples, windowSize, hopSize, numChunks, numWorkers, queue),
                "Producer-WavChunker");

        ExecutorService workerPool = Executors.newFixedThreadPool(numWorkers);
        for (int i = 0; i < numWorkers; i++) {
            workerPool.submit(new FFTWorker(i, queue, resultMatrix, WindowFunction.Type.HANN, processedChunks));
        }

        producer.start();
        producer.join();

        workerPool.shutdown();
        boolean finished = workerPool.awaitTermination(5, TimeUnit.MINUTES);
        if (!finished) {
            System.err.println("ADVERTENCIA: los workers no terminaron dentro del tiempo límite.");
        }

        return resultMatrix;
    }
}

