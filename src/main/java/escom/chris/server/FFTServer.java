package escom.chris.server;

import escom.chris.FFTPipelineManager;
import escom.chris.concurrency.SharedResultMatrix;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Servidor TCP para el procesamiento distribuido de la FFT.
 * Escucha en un puerto TCP, deserializa los parámetros y muestras recibidas,
 * ejecuta el pipeline concurrente de cálculo y transmite la matriz resultante.
 */
public final class FFTServer {

    private static final int BUFFER_SIZE = 65536; // 64 KB

    public static void main(String[] args) {
        int port = args.length > 0 ? Integer.parseInt(args[0]) : 5000;
        int numWorkers = args.length > 1 ? Integer.parseInt(args[1]) : Runtime.getRuntime().availableProcessors();

        System.out.println();
        System.out.println("        SERVIDOR FFT CONCURRENTE (Sockets TCP)            ");
        System.out.println();
        System.out.printf("Puerto de escucha: %d%n", port);
        System.out.printf("Workers asignados por petición: %d%n", numWorkers);
        System.out.println("Esperando conexiones de clientes...\n");

        ExecutorService clientHandlerPool = Executors.newCachedThreadPool();

        try (ServerSocket serverSocket = new ServerSocket(port)) {
            Socket clientSocket = serverSocket.accept();
            clientHandlerPool.submit(() -> handleClient(clientSocket, numWorkers));
        } catch (IOException e) {
            System.err.printf("[Servidor] Error crítico en ServerSocket: %s%n", e.getMessage());
        } finally {
            clientHandlerPool.shutdown();
        }
    }

    private static void handleClient(Socket socket, int numWorkers) {
        String clientAddress = socket.getRemoteSocketAddress().toString();
        System.out.printf("[+] Cliente conectado: %s%n", clientAddress);

        try (socket;
             DataInputStream dis = new DataInputStream(new BufferedInputStream(socket.getInputStream(), BUFFER_SIZE));
             DataOutputStream dos = new DataOutputStream(new BufferedOutputStream(socket.getOutputStream(), BUFFER_SIZE))) {

            long tStart = System.nanoTime();

            // Deserializar flujo binario según el protocolo:
            // [windowSize (int), hopSize (int), sampleCount (int), samples (double[])]
            int windowSize = dis.readInt();
            int hopSize = dis.readInt();
            int sampleCount = dis.readInt();

            System.out.printf("  [%s] Recibiendo %d muestras (Ventana: %d, Hop: %d)...%n",
                    clientAddress, sampleCount, windowSize, hopSize);

            double[] samples = new double[sampleCount];
            for (int i = 0; i < sampleCount; i++) {
                samples[i] = dis.readDouble();
            }

            long tReceived = System.nanoTime();
            double recvMs = (tReceived - tStart) / 1_000_000.0;
            System.out.printf("  [%s] Muestras recibidas en %.2f ms.%n", clientAddress, recvMs);

            // Ejecutar cálculo FFT concurrente
            long tComputeStart = System.nanoTime();
            SharedResultMatrix resultMatrix = FFTPipelineManager.compute(samples, windowSize, hopSize, numWorkers);
            long tComputeEnd = System.nanoTime();
            double computeMs = (tComputeEnd - tComputeStart) / 1_000_000.0;

            System.out.printf("  [%s] Cómputo completado: %d frames x %d bins [%.2f ms]%n",
                    clientAddress, resultMatrix.getNumChunks(), resultMatrix.getNumBins(), computeMs);

            // Serializar y transmitir matriz resultante según el protocolo:
            // [totalFrames (int), freqBins (int), data (double[])]
            long tSendStart = System.nanoTime();
            resultMatrix.writeToBinaryStream(dos);
            long tSendEnd = System.nanoTime();
            double sendMs = (tSendEnd - tSendStart) / 1_000_000.0;

            System.out.printf("  [%s] Matriz enviada en %.2f ms. Total atendiendo cliente: %.2f ms%n%n",
                    clientAddress, sendMs, (tSendEnd - tStart) / 1_000_000.0);

        } catch (IOException e) {
            System.err.printf("  [-] Error de I/O con cliente %s: %s%n", clientAddress, e.getMessage());
        } catch (Exception e) {
            System.err.printf("  [-] Error procesando petición de %s: %s%n", clientAddress, e.getMessage());
        }
    }
}
