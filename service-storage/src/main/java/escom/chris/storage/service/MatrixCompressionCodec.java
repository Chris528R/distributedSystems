package escom.chris.storage.service;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;

/**
 * Comprime y descomprime matrices de espectrograma {@code double[frames][bins]}
 * en formato binario {@code float32} + GZIP para almacenamiento eficiente en PostgreSQL.
 */
public final class MatrixCompressionCodec {

    private MatrixCompressionCodec() {
    }

    public static byte[] compress(double[][] magnitudes, int frames, int bins) throws IOException {
        ByteArrayOutputStream baos = new ByteArrayOutputStream(frames * bins * 2);
        try (GZIPOutputStream gzip = new GZIPOutputStream(baos);
             DataOutputStream dos = new DataOutputStream(gzip)) {
            for (int f = 0; f < frames; f++) {
                double[] row = magnitudes[f];
                for (int b = 0; b < bins; b++) {
                    dos.writeFloat((float) row[b]);
                }
            }
        }
        return baos.toByteArray();
    }

    public static double[][] decompress(byte[] compressed, int frames, int bins) throws IOException {
        double[][] magnitudes = new double[frames][bins];
        try (GZIPInputStream gzip = new GZIPInputStream(new ByteArrayInputStream(compressed));
             DataInputStream dis = new DataInputStream(gzip)) {
            for (int f = 0; f < frames; f++) {
                double[] row = magnitudes[f];
                for (int b = 0; b < bins; b++) {
                    row[b] = dis.readFloat();
                }
            }
        }
        return magnitudes;
    }
}

