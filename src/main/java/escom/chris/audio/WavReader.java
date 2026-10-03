package escom.chris.audio;

import java.io.*;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/**
 * Lector de archivos .WAV en formato PCM (8/16/24 bits), sin dependencias
 * externas (no usa javax.sound). Hace un parseo manual de la cabecera
 * RIFF/WAVE, localiza el subchunk "fmt " y "data", y expone las muestras
 * como un arreglo de double normalizado en [-1, 1] (mono; si el audio es
 * estéreo, se hace downmix promediando canales).
 *
 * Esta clase se ejecuta en un único hilo (I/O secuencial): la lectura de
 * disco es la parte no paralelizable del pipeline, tal como indica el
 * diagrama de arquitectura ("I/O mono-hilo").
 */
public final class WavReader {

    public static final class WavData {
        public final double[] samples;
        public final int sampleRate;
        public final int channels;
        public final int bitsPerSample;

        public WavData(double[] samples, int sampleRate, int channels, int bitsPerSample) {
            this.samples = samples;
            this.sampleRate = sampleRate;
            this.channels = channels;
            this.bitsPerSample = bitsPerSample;
        }
    }

    public static double[] readMonoPcm(String path) throws IOException {
        return new WavReader().read(path).samples;
    }

    public WavData read(String path) throws IOException {
        try (InputStream rawIn = new BufferedInputStream(new FileInputStream(path))) {
            byte[] header = readNBytes(rawIn, 12);
            requireTag(header, 0, "RIFF");
            requireTag(header, 8, "WAVE");

            int sampleRate = 0, channels = 0, bitsPerSample = 0;
            byte[] pcmData = null;

            while (true) {
                byte[] chunkHeader = readNBytesOrNull(rawIn, 8);
                if (chunkHeader == null) break; // EOF

                String chunkId = new String(chunkHeader, 0, 4, "US-ASCII");
                int chunkSize = ByteBuffer.wrap(chunkHeader, 4, 4)
                        .order(ByteOrder.LITTLE_ENDIAN).getInt();

                if (chunkId.equals("fmt ")) {
                    byte[] fmt = readNBytes(rawIn, chunkSize);
                    ByteBuffer bb = ByteBuffer.wrap(fmt).order(ByteOrder.LITTLE_ENDIAN);
                    bb.getShort();                 // audioFormat (1 = PCM)
                    channels = bb.getShort();
                    sampleRate = bb.getInt();
                    bb.getInt();                   // byteRate
                    bb.getShort();                 // blockAlign
                    bitsPerSample = bb.getShort();
                } else if (chunkId.equals("data")) {
                    pcmData = readNBytes(rawIn, chunkSize);
                } else {
                    // Subchunk irrelevante (p.ej. "LIST"): se descarta.
                    skipNBytes(rawIn, chunkSize);
                }
                if (chunkSize % 2 == 1) rawIn.skip(1); // padding a byte par
            }

            if (pcmData == null || sampleRate == 0) {
                throw new IOException("Archivo WAV inválido o incompleto: " + path);
            }

            double[] samples = decodePcm(pcmData, channels, bitsPerSample);
            return new WavData(samples, sampleRate, channels, bitsPerSample);
        }
    }

    private double[] decodePcm(byte[] pcmData, int channels, int bitsPerSample) {
        int bytesPerSample = bitsPerSample / 8;
        int frameSize = bytesPerSample * channels;
        int numFrames = pcmData.length / frameSize;
        double[] mono = new double[numFrames];

        ByteBuffer bb = ByteBuffer.wrap(pcmData).order(ByteOrder.LITTLE_ENDIAN);
        double maxValue = Math.pow(2, bitsPerSample - 1);

        for (int frame = 0; frame < numFrames; frame++) {
            double acc = 0.0;
            for (int ch = 0; ch < channels; ch++) {
                int raw;
                if (bitsPerSample == 16) {
                    raw = bb.getShort();
                } else if (bitsPerSample == 8) {
                    raw = (bb.get() & 0xFF) - 128;
                } else if (bitsPerSample == 24) {
                    int b0 = bb.get() & 0xFF, b1 = bb.get() & 0xFF, b2 = bb.get();
                    raw = (b2 << 16) | (b1 << 8) | b0;
                } else {
                    throw new UnsupportedOperationException(
                            "Profundidad de bits no soportada: " + bitsPerSample);
                }
                acc += raw / maxValue;
            }
            mono[frame] = acc / channels; // downmix a mono
        }
        return mono;
    }

    private static void requireTag(byte[] buf, int offset, String tag) throws IOException {
        String actual = new String(buf, offset, 4);
        if (!actual.equals(tag)) {
            throw new IOException("Cabecera WAV inválida: se esperaba '" + tag + "', se encontró '" + actual + "'");
        }
    }

    private static byte[] readNBytes(InputStream in, int n) throws IOException {
        byte[] buf = new byte[n];
        int off = 0;
        while (off < n) {
            int read = in.read(buf, off, n - off);
            if (read == -1) throw new EOFException("Fin de archivo inesperado leyendo WAV");
            off += read;
        }
        return buf;
    }

    private static byte[] readNBytesOrNull(InputStream in, int n) throws IOException {
        byte[] buf = new byte[n];
        int off = 0;
        while (off < n) {
            int read = in.read(buf, off, n - off);
            if (read == -1) return off == 0 ? null : buf;
            off += read;
        }
        return buf;
    }

    private static void skipNBytes(InputStream in, int n) throws IOException {
        long skipped = 0;
        while (skipped < n) {
            long s = in.skip(n - skipped);
            if (s <= 0) break;
            skipped += s;
        }
    }
}
