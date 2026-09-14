package escom.chris.server;

import escom.chris.concurrency.GlobalFftWorkerPool;
import escom.chris.concurrency.SharedResultMatrix;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.net.Socket;
import java.util.concurrent.RejectedExecutionException;

/**
 * Manejador I/O por cliente: lee el socket, envía chunks al pool FFT global
 * y escribe la respuesta. No crea un pool de cómputo propio.
 */
public final class ClienteTaskHandler implements Runnable {

    private static final int BUFFER_SIZE = 65536; // 64 KB

    private final Socket socket;
    private final GlobalFftWorkerPool sharedComputePool;

    public ClienteTaskHandler(Socket socket, GlobalFftWorkerPool sharedComputePool) {
        this.socket = socket;
        this.sharedComputePool = sharedComputePool;
    }

    @Override
    public void run() {
        String clientAddress = socket.getRemoteSocketAddress().toString();
        System.out.printf("[+] Cliente conectado: %s%n", clientAddress);

        try (socket;
             DataInputStream dis = new DataInputStream(new BufferedInputStream(socket.getInputStream(), BUFFER_SIZE));
             DataOutputStream dos = new DataOutputStream(new BufferedOutputStream(socket.getOutputStream(), BUFFER_SIZE))) {

            long tStart = System.nanoTime();

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

            long tComputeStart = System.nanoTime();
            SharedResultMatrix resultMatrix = sharedComputePool.compute(samples, windowSize, hopSize);
            long tComputeEnd = System.nanoTime();
            double computeMs = (tComputeEnd - tComputeStart) / 1_000_000.0;

            System.out.printf("  [%s] Cómputo (pool global): %d frames x %d bins [%.2f ms]%n",
                    clientAddress, resultMatrix.getNumChunks(), resultMatrix.getNumBins(), computeMs);

            long tSendStart = System.nanoTime();
            resultMatrix.writeToBinaryStream(dos);
            long tSendEnd = System.nanoTime();
            double sendMs = (tSendEnd - tSendStart) / 1_000_000.0;

            System.out.printf("  [%s] Matriz enviada en %.2f ms. Total: %.2f ms%n%n",
                    clientAddress, sendMs, (tSendEnd - tStart) / 1_000_000.0);

        } catch (RejectedExecutionException e) {
            System.err.printf("  [-] Cola de cómputo saturada para %s: %s%n", clientAddress, e.getMessage());
        } catch (IOException e) {
            System.err.printf("  [-] Error de I/O con cliente %s: %s%n", clientAddress, e.getMessage());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            System.err.printf("  [-] Handler interrumpido para %s%n", clientAddress);
        } catch (Exception e) {
            System.err.printf("  [-] Error procesando petición de %s: %s%n", clientAddress, e.getMessage());
        }
    }
}
