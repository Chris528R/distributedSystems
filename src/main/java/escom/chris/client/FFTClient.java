package escom.chris.client;

import escom.chris.audio.WavReader;
import escom.chris.concurrency.SharedResultMatrix;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.net.Socket;

/**
 * Cliente TCP para el procesamiento distribuido de la FFT.
 * Lee un archivo .WAV local, envía las muestras y configuración al servidor
 * mediante el protocolo binario, recibe la matriz del espectrograma y la renderiza/exporta.
 */
public final class FFTClient {

    private static final int BUFFER_SIZE = 65536; // 64 KB

    public static void main(String[] args) throws Exception {
        if (args.length < 3) {
            System.out.println("Uso: java escom.chris.client.FFTClient <host> <puerto> <archivo.wav> [windowSize] [hopSize] [salida.csv]");
            System.out.println("Ejemplo: java escom.chris.client.FFTClient localhost 5000 src/main/resources/audio.wav 1024 512 client_spectrogram.csv");
            return;
        }

        String host = args[0];
        int port = Integer.parseInt(args[1]);
        String wavPath = args[2];
        int windowSize = args.length > 3 ? Integer.parseInt(args[3]) : 1024;
        int hopSize = args.length > 4 ? Integer.parseInt(args[4]) : windowSize / 2;
        String outputCsv = args.length > 5 ? args[5] : "client_spectrogram.csv";

        System.out.println("==========================================================");
        System.out.println("         CLIENTE FFT DISTRIBUIDO (Sockets TCP)            ");
        System.out.println("==========================================================");
        System.out.printf("Destino: %s:%d%n", host, port);
        System.out.printf("Audio: %s | Ventana: %d | Hop: %d%n%n", wavPath, windowSize, hopSize);

        // 1. Lectura del archivo WAV local
        long tReadStart = System.nanoTime();
        WavReader.WavData wav = new WavReader().read(wavPath);
        long tReadEnd = System.nanoTime();
        double readMs = (tReadEnd - tReadStart) / 1_000_000.0;
        System.out.printf("[1/4] Audio leído: %d muestras, %d Hz, %d canal(es) [%.2f ms]%n",
                wav.samples.length, wav.sampleRate, wav.channels, readMs);

        // 2. Conexión y transmisión de muestras al servidor
        System.out.printf("[2/4] Conectando a %s:%d... ", host, port);
        long tNetStart = System.nanoTime();
        SharedResultMatrix resultMatrix;

        try (Socket socket = new Socket(host, port);
             DataOutputStream dos = new DataOutputStream(new BufferedOutputStream(socket.getOutputStream(), BUFFER_SIZE));
             DataInputStream dis = new DataInputStream(new BufferedInputStream(socket.getInputStream(), BUFFER_SIZE))) {

            System.out.println("¡Conexión establecida!");

            // Enviar protocolo: [windowSize (int), hopSize (int), sampleCount (int), samples (double[])]
            long tSendStart = System.nanoTime();
            dos.writeInt(windowSize);
            dos.writeInt(hopSize);
            dos.writeInt(wav.samples.length);
            for (double sample : wav.samples) {
                dos.writeDouble(sample);
            }
            dos.flush();
            long tSendEnd = System.nanoTime();
            double sendMs = (tSendEnd - tSendStart) / 1_000_000.0;
            System.out.printf("      Datos enviados: %d bytes en %.2f ms (%.1f MB/s)%n",
                    12 + wav.samples.length * 8, sendMs, (wav.samples.length * 8.0 / (1024 * 1024)) / (sendMs / 1000.0));

            // 3. Recepción de la matriz del espectrograma
            System.out.print("[3/4] Esperando cómputo del servidor y recibiendo espectrograma... ");
            long tRecvStart = System.nanoTime();
            resultMatrix = SharedResultMatrix.readFromBinaryStream(dis);
            long tRecvEnd = System.nanoTime();
            double recvMs = (tRecvEnd - tRecvStart) / 1_000_000.0;
            System.out.printf("¡Recibido! [%.2f ms]%n", recvMs);

        }
        long tNetEnd = System.nanoTime();
        double netTotalMs = (tNetEnd - tNetStart) / 1_000_000.0;
        System.out.printf("      Tiempo total de interacción de red (RTT): %.2f ms%n", netTotalMs);
        System.out.printf("      Dimensiones recibidas: %d frames temporales x %d bins de frecuencia%n%n",
                resultMatrix.getNumChunks(), resultMatrix.getNumBins());

        // 4. Exportación a archivo CSV
        if (outputCsv != null && !outputCsv.isEmpty()) {
            long tExportStart = System.nanoTime();
            resultMatrix.exportToCsv(outputCsv);
            long tExportEnd = System.nanoTime();
            double exportMs = (tExportEnd - tExportStart) / 1_000_000.0;
            System.out.printf("[4/4] Espectrograma exportado a '%s' [%.2f ms]%n%n", outputCsv, exportMs);
        }

        // 5. Renderizado visual en consola (Vista previa ASCII del espectrograma)
        renderAsciiSpectrogram(resultMatrix, wav.sampleRate, 12, 50);

        double totalMs = readMs + netTotalMs;
        System.out.println("==========================================================");
        System.out.printf("Proceso completado exitosamente. Tiempo total cliente: %.2f ms%n", totalMs);
        System.out.println("==========================================================");
    }

    /**
     * Renderiza una vista previa del espectrograma en consola usando caracteres ASCII/Unicode.
     * Muestra frecuencias altas arriba y bajas abajo, con escala de intensidad en dB.
     */
    private static void renderAsciiSpectrogram(SharedResultMatrix matrix, int sampleRate, int displayRows, int displayCols) {
        int totalBins = matrix.getNumBins();
        int totalFrames = matrix.getNumChunks();

        char[] intensityScale = {' ', '.', ':', '-', '=', '#', '@'};
        double minDb = -80.0;
        double maxDb = 0.0;

        System.out.println("--- RENDERIZADO VISUAL DEL ESPECTROGRAMA RECIBIDO ---");
        System.out.printf("Frecuencia (Hz) | Tiempo transcurrido -> (%d frames totales)%n", totalFrames);
        System.out.println("----------------+---------------------------------------------------");

        // Recorrer filas desde altas frecuencias hasta bajas frecuencias
        for (int r = displayRows - 1; r >= 0; r--) {
            int binStart = r * totalBins / displayRows;
            int binEnd = Math.min(totalBins, (r + 1) * totalBins / displayRows);
            double centerFreq = ((binStart + binEnd) / 2.0) * (sampleRate / 2.0) / totalBins;

            System.out.printf("%6.0f Hz | ", centerFreq);

            for (int c = 0; c < displayCols; c++) {
                int frameStart = c * totalFrames / displayCols;
                int frameEnd = Math.min(totalFrames, (c + 1) * totalFrames / displayCols);

                // Promedio de magnitud en el bloque visual
                double sum = 0.0;
                int count = 0;
                for (int b = binStart; b < binEnd; b++) {
                    for (int f = frameStart; f < frameEnd; f++) {
                        sum += matrix.get(b, f);
                        count++;
                    }
                }
                double avgDb = count > 0 ? sum / count : minDb;

                // Mapeo de [minDb, maxDb] a índice de escala de intensidad
                double norm = (avgDb - minDb) / (maxDb - minDb);
                norm = Math.max(0.0, Math.min(1.0, norm));
                int charIdx = (int) (norm * (intensityScale.length - 1));

                System.out.print(intensityScale[charIdx]);
            }
            System.out.println();
        }

        System.out.println("----------------+---------------------------------------------------");
        System.out.println("Escala de dB:   [Silencio/Bajo: ' ']  .  :  -  =  #  @  [Intenso/Alto]");
        System.out.println();
    }
}

