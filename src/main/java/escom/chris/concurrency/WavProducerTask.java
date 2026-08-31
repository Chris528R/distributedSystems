package escom.chris.concurrency;

import escom.chris.audio.AudioChunk;
import java.util.concurrent.BlockingQueue;

/**
 * Tarea del hilo Productor: trocea la señal en ventanas y las publica
 * en la cola compartida. Al finalizar, inserta poison pills para los workers.
 */
public final class WavProducerTask implements Runnable {
    private final double[] samples;
    private final int windowSize;
    private final int hopSize;
    private final int numChunks;
    private final int numWorkers;
    private final BlockingQueue<AudioChunk> queue;

    public WavProducerTask(double[] samples, int windowSize, int hopSize,
                           int numChunks, int numWorkers, BlockingQueue<AudioChunk> queue) {
        this.samples = samples;
        this.windowSize = windowSize;
        this.hopSize = hopSize;
        this.numChunks = numChunks;
        this.numWorkers = numWorkers;
        this.queue = queue;
    }

    @Override
    public void run() {
        try {
            for (int c = 0; c < numChunks; c++) {
                int start = c * hopSize;
                int end = Math.min(start + windowSize, samples.length);
                double[] windowSamples = new double[windowSize];
                System.arraycopy(samples, start, windowSamples, 0, end - start);

                queue.put(new AudioChunk(c, windowSamples, start));
            }
            for (int i = 0; i < numWorkers; i++) {
                queue.put(AudioChunk.POISON_PILL);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            System.err.println("[Producer] Interrumpido antes de completar el encolado.");
        }
    }
}
