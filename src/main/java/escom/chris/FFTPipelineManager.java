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
        System.out.println("=== FFT Concurrente: Productor-Consumidor / Master-Worker ===");
        System.out.printf("Archivo: %s | Workers: %d | Ventana: %d | Hop: %d%n",
                wavPath, numWorkers, windowSize, hopSize);

        long tReadStart = System.nanoTime();
        WavReader.WavData wav = new WavReader().read(wavPath);
        long tReadEnd = System.nanoTime();
        System.out.printf("Lectura WAV: %d muestras, %d Hz, %d canal(es) [%.2f ms]%n",
                wav.samples.length, wav.sampleRate, wav.channels,
                (tReadEnd - tReadStart) / 1_000_000.0);

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

        if (!finished) {
            System.err.println("ADVERTENCIA: los workers no terminaron dentro del tiempo límite.");
        }

        double computeMs = (tComputeEnd - tComputeStart) / 1_000_000.0;
        System.out.printf("Chunks procesados: %d / %d [%.2f ms, %.1f chunks/seg]%n",
                processedChunks.get(), numChunks, computeMs,
                processedChunks.get() / (computeMs / 1000.0));

        long tExportStart = System.nanoTime();
        resultMatrix.exportToCsv(outputCsv);
        long tExportEnd = System.nanoTime();
        System.out.printf("Espectrograma exportado a '%s' [%.2f ms]%n",
                outputCsv, (tExportEnd - tExportStart) / 1_000_000.0);

        double totalMs = (tReadEnd - tReadStart + tComputeEnd - tComputeStart + tExportEnd - tExportStart) / 1_000_000.0;
        System.out.printf("Tiempo total del pipeline: %.2f ms%n", totalMs);
    }
}
