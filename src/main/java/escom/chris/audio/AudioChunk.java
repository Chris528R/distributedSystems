package escom.chris.audio;

/**
 * DTO inmutable que representa una ventana (chunk) de muestras de audio
 * junto con su índice temporal. Es la unidad de trabajo que viaja por la
 * BlockingQueue entre el productor (WavReader) y los consumidores (FFTWorker).
 *
 * También define el "poison pill": un chunk centinela que, al ser
 * detectado por un worker, le indica que debe terminar su ciclo de vida.
 * Es el mecanismo estándar en Java para cerrar ordenadamente un pipeline
 * Productor-Consumidor basado en BlockingQueue.
 */
public final class AudioChunk {

    public static final AudioChunk POISON_PILL = new AudioChunk(-1, null, -1);

    private final int index;               // columna que ocupará en el espectrograma
    private final double[] samples;        // muestras normalizadas [-1, 1]
    private final long sampleOffset;       // posición de la primera muestra en la señal original

    public AudioChunk(int index, double[] samples, long sampleOffset) {
        this.index = index;
        this.samples = samples;
        this.sampleOffset = sampleOffset;
    }

    public boolean isPoisonPill() {
        return this.index == -1;
    }

    public int getIndex() { return index; }
    public double[] getSamples() { return samples; }
    public long getSampleOffset() { return sampleOffset; }
}
