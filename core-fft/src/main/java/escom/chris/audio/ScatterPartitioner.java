package escom.chris.audio;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Utilidad de particionado de señales de audio para distribución Scatter-Gather
 * entre múltiples instancias de {@code service-worker-fft}.
 *
 * <p>Al dividir los frames de un espectrograma entre varios nodos, el sub-bloque
 * de muestras que recibe cada worker para procesar {@code count} ventanas desde
 * {@code startFrame} hasta {@code startFrame + count - 1} incluye el margen derecho
 * necesario para completar la última ventana de tamaño {@code windowSize}:</p>
 *
 * <pre>
 *   startSample = startFrame * hopSize
 *   endSample   = startSample + (count - 1) * hopSize + windowSize
 * </pre>
 */
public final class ScatterPartitioner {

    private ScatterPartitioner() {
    }

    /**
     * Representa un segmento contiguo de ventanas asignado a un nodo Worker.
     *
     * @param partitionIndex índice de la partición (0 .. numPartitions-1)
     * @param startFrame     índice global del primer frame que calculará este worker
     * @param frameCount     cantidad de frames asignados a este worker
     * @param startSample    índice de la primera muestra en la señal original
     * @param endSample      índice exclusivo de la última muestra en la señal original
     * @param subSamples     sub-arreglo recortado {@code samples[startSample : endSample]}
     */
    public record PartitionSlice(
            int partitionIndex,
            int startFrame,
            int frameCount,
            int startSample,
            int endSample,
            double[] subSamples
    ) {
    }

    /**
     * Calcula el número total de ventanas completas que se generan sobre un arreglo de muestras.
     */
    public static int totalFrames(int sampleLength, int windowSize, int hopSize) {
        if (sampleLength < windowSize) {
            throw new IllegalArgumentException(
                    "Se requieren al menos " + windowSize + " muestras para windowSize=" + windowSize);
        }
        return Math.max(1, (sampleLength - windowSize) / hopSize + 1);
    }

    /**
     * Divide la señal {@code samples} en hasta {@code numWorkers} particiones equilibradas
     * respetando el solapamiento ({@code hopSize}) y el margen derecho de {@code windowSize}.
     */
    public static List<PartitionSlice> partition(double[] samples, int windowSize, int hopSize, int numWorkers) {
        if (samples == null || samples.length < windowSize) {
            throw new IllegalArgumentException("Señal insuficiente para windowSize=" + windowSize);
        }
        if (windowSize < 2 || Integer.bitCount(windowSize) != 1) {
            throw new IllegalArgumentException("windowSize debe ser potencia de 2");
        }
        if (hopSize <= 0 || hopSize > windowSize) {
            throw new IllegalArgumentException("hopSize debe estar en el rango [1, windowSize]");
        }

        int totalFrames = totalFrames(samples.length, windowSize, hopSize);
        int activeWorkers = Math.max(1, Math.min(numWorkers, totalFrames));

        int baseCount = totalFrames / activeWorkers;
        int remainder = totalFrames % activeWorkers;

        List<PartitionSlice> slices = new ArrayList<>(activeWorkers);
        int currentStartFrame = 0;

        for (int i = 0; i < activeWorkers; i++) {
            int count = baseCount + (i < remainder ? 1 : 0);
            int startSample = currentStartFrame * hopSize;
            int endSample = Math.min(samples.length, startSample + (count - 1) * hopSize + windowSize);
            double[] subSamples = Arrays.copyOfRange(samples, startSample, endSample);

            slices.add(new PartitionSlice(
                    i,
                    currentStartFrame,
                    count,
                    startSample,
                    endSample,
                    subSamples
            ));
            currentStartFrame += count;
        }

        return slices;
    }
}

