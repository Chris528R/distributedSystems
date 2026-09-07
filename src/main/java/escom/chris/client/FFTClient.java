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

        // Lectura del archivo WAV local
        long tReadStart = System.nanoTime();
        WavReader.WavData wav = new WavReader().read(wavPath);
        long tReadEnd = System.nanoTime();
        double readMs = (tReadEnd - tReadStart) / 1_000_000.0;
        System.out.printf("[1/4] Audio leído: %d muestras, %d Hz, %d canal(es) [%.2f ms]%n",
                wav.samples.length, wav.sampleRate, wav.channels, readMs);

        // Conexión y transmisión de muestras al servidor
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

            // Recepción de la matriz del espectrograma
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

        // Exportación a archivo CSV
        if (outputCsv != null && !outputCsv.isEmpty()) {
            long tExportStart = System.nanoTime();
            resultMatrix.exportToCsv(outputCsv);
            long tExportEnd = System.nanoTime();
            double exportMs = (tExportEnd - tExportStart) / 1_000_000.0;
            System.out.printf("[4/4] Espectrograma exportado a '%s' [%.2f ms]%n%n", outputCsv, exportMs);
        }

        double totalMs = readMs + netTotalMs;
        System.out.println("==========================================================");
        System.out.printf("Proceso completado exitosamente. Tiempo total cliente: %.2f ms%n", totalMs);
        System.out.println("==========================================================");
    }
}

