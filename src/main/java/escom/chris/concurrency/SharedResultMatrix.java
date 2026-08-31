package escom.chris.concurrency;

import java.io.BufferedWriter;
import java.io.FileWriter;
import java.io.IOException;
import java.util.concurrent.locks.ReadWriteLock;
import java.util.concurrent.locks.ReentrantReadWriteLock;

/**
 * Estructura de memoria compartida entre todos los hilos FFTWorker.
 * Representa el espectrograma como matrix[binFrecuencia][indiceChunk].
 *
 * Control de concurrencia:
 * Aunque cada worker escribe en una COLUMNA distinta (no hay solapamiento
 * lógico de datos), sí existe una condición de carrera de "visibilidad de
 * memoria": sin sincronización, otro hilo (p.ej. el hilo principal, al
 * exportar a CSV) podría leer valores parcialmente escritos o "stale" por
 * culpa del cacheo de cada núcleo de CPU. Por eso se usa un
 * ReentrantReadWriteLock: múltiples workers podrían, en una versión más
 * fina, tomar el lock de escritura solo para su columna, pero aquí se
 * demuestra el patrón estándar de "exclusión mutua + lectura concurrente"
 * a nivel de estructura completa, que además protege contra escrituras
 * fuera de rango.
 */
public final class SharedResultMatrix {

    private final double[][] matrix; // [freqBin][chunkIndex]
    private final int numBins;
    private final int numChunks;
    private final ReadWriteLock lock = new ReentrantReadWriteLock();

    public SharedResultMatrix(int numBins, int numChunks) {
        this.numBins = numBins;
        this.numChunks = numChunks;
        this.matrix = new double[numBins][numChunks];
    }

    /** Invocado por cada FFTWorker al terminar de procesar su chunk. */
    public void setColumn(int chunkIndex, double[] magnitudes) {
        if (chunkIndex < 0 || chunkIndex >= numChunks) {
            throw new IndexOutOfBoundsException("chunkIndex fuera de rango: " + chunkIndex);
        }
        lock.writeLock().lock();
        try {
            int limit = Math.min(numBins, magnitudes.length);
            for (int bin = 0; bin < limit; bin++) {
                matrix[bin][chunkIndex] = magnitudes[bin];
            }
        } finally {
            lock.writeLock().unlock();
        }
    }

    public double get(int bin, int chunkIndex) {
        lock.readLock().lock();
        try {
            return matrix[bin][chunkIndex];
        } finally {
            lock.readLock().unlock();
        }
    }

    public int getNumBins() { return numBins; }
    public int getNumChunks() { return numChunks; }

    /** Exporta la matriz completa a CSV: cada fila es un bin de frecuencia, cada columna un instante temporal. */
    public void exportToCsv(String path) throws IOException {
        lock.readLock().lock();
        try (BufferedWriter writer = new BufferedWriter(new FileWriter(path))) {
            for (int bin = 0; bin < numBins; bin++) {
                StringBuilder sb = new StringBuilder();
                for (int chunk = 0; chunk < numChunks; chunk++) {
                    sb.append(String.format("%.3f", matrix[bin][chunk]));
                    if (chunk < numChunks - 1) sb.append(",");
                }
                writer.write(sb.toString());
                writer.newLine();
            }
        } finally {
            lock.readLock().unlock();
        }
    }
}
