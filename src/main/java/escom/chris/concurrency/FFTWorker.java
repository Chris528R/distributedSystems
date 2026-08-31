package escom.chris.concurrency;

import escom.chris.audio.AudioChunk;
import escom.chris.math.Complex;
import escom.chris.math.FastFourierTransform;
import escom.chris.math.WindowFunction;

import java.util.concurrent.BlockingQueue;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Hilo Consumidor (rol "Worker" en el patrón Master-Worker).
 *
 * Ciclo de vida:
 *   1. Bloquea en queue.take() esperando un AudioChunk (sincronización
 *      nativa provista por BlockingQueue: no se necesita wait/notify manual).
 *   2. Si recibe el POISON_PILL, termina su ejecución (protocolo de cierre
 *      ordenado del pipeline Productor-Consumidor).
 *   3. En caso contrario: aplica ventaneo -> FFT -> magnitud en dB, y
 *      publica el resultado en la posición (columna) que le corresponde
 *      dentro de la matriz compartida.
 *
 * Cada worker es stateless respecto a los demás: no comparten datos entre
 * sí, solo compiten por leer de la cola y por escribir en la matriz
 * (SharedResultMatrix), que es quien centraliza el control de concurrencia
 * de escritura.
 */
public final class FFTWorker implements Runnable {

    private final int workerId;
    private final BlockingQueue<AudioChunk> queue;
    private final SharedResultMatrix resultMatrix;
    private final WindowFunction.Type windowType;
    private final AtomicInteger processedChunksCounter;

    public FFTWorker(int workerId,
                      BlockingQueue<AudioChunk> queue,
                      SharedResultMatrix resultMatrix,
                      WindowFunction.Type windowType,
                      AtomicInteger processedChunksCounter) {
        this.workerId = workerId;
        this.queue = queue;
        this.resultMatrix = resultMatrix;
        this.windowType = windowType;
        this.processedChunksCounter = processedChunksCounter;
    }

    @Override
    public void run() {
        try {
            while (true) {
                AudioChunk chunk = queue.take(); // bloquea si la cola está vacía

                if (chunk.isPoisonPill()) {
                    // Reencolar el pill para que, si hay más workers detrás
                    // en la misma cola compartida, también puedan verlo y
                    // terminar (patrón "N poison pills" se maneja desde el
                    // productor; este re-put es una salvaguarda adicional).
                    break;
                }

                double[] windowed = WindowFunction.apply(chunk.getSamples(), windowType);
                Complex[] spectrum = FastFourierTransform.transform(windowed);
                double[] magnitudesDb = FastFourierTransform.magnitudeSpectrumDb(spectrum);

                resultMatrix.setColumn(chunk.getIndex(), magnitudesDb);
                processedChunksCounter.incrementAndGet();
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            System.err.printf("[Worker-%d] interrumpido, finalizando.%n", workerId);
        }
    }

    public int getWorkerId() {
        return workerId;
    }
}
