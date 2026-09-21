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
 * Servidor Worker Backend para la arquitectura Scatter-Gather.
 * Escucha en un puerto TCP específico (ej. 5001, 5002, 5003), recibe una
 * porción de frames asignada por el Broker, procesa la FFT en paralelo
 * con su propio pool de hilos y devuelve las magnitudes calculadas.
 */
public final class WorkerNodeServer {

    private static final int BUFFER_SIZE = 65536; // 64 KB

    private final int port;
    private final int numThreads;
    private volatile boolean running = true;
    private ServerSocket serverSocket;
    private ExecutorService threadPool;

    public WorkerNodeServer(int port, int numThreads) {
        this.port = port;
        this.numThreads = numThreads;
    }

    public void start() throws IOException {
        serverSocket = new ServerSocket(port);
        threadPool = Executors.newCachedThreadPool();

        System.out.printf("[WorkerNode-%d] Iniciado en puerto %d con %d hilos de cómputo local.%n",
                port, port, numThreads);

        while (running) {
            try {
                Socket clientSocket = serverSocket.accept();
                threadPool.submit(() -> handleBrokerTask(clientSocket));
            } catch (IOException e) {
                if (!running) break;
                System.err.printf("[WorkerNode-%d] Error aceptando conexión: %s%n", port, e.getMessage());
            }
        }
    }

    public void stop() {
        running = false;
        try {
            if (serverSocket != null && !serverSocket.isClosed()) {
                serverSocket.close();
            }
        } catch (IOException ignored) {}
        if (threadPool != null) {
            threadPool.shutdown();
        }
    }

    private void handleBrokerTask(Socket socket) {
        try (socket;
             DataInputStream dis = new DataInputStream(new BufferedInputStream(socket.getInputStream(), BUFFER_SIZE));
             DataOutputStream dos = new DataOutputStream(new BufferedOutputStream(socket.getOutputStream(), BUFFER_SIZE))) {

            long tStart = System.nanoTime();

            // 1. Leer cabecera interna: [startFrame (int), frameCount (int), windowSize (int), hopSize (int), rawSamples]
            int startFrame = dis.readInt();
            int frameCount = dis.readInt();
            int windowSize = dis.readInt();
            int hopSize = dis.readInt();

            int sampleCount = (frameCount <= 0) ? 0 : ((frameCount - 1) * hopSize + windowSize);
            double[] samples = new double[sampleCount];
            for (int i = 0; i < sampleCount; i++) {
                samples[i] = dis.readDouble();
            }

            long tRecv = System.nanoTime();

            // 2. Calcular la FFT de la porción asignada
            SharedResultMatrix partialMatrix = FFTPipelineManager.compute(samples, windowSize, hopSize, numThreads);
            long tCompute = System.nanoTime();

            // 3. Enviar respuesta: [startFrame (int), frameCount (int), data (frameCount * freqBins * 8 bytes)]
            int freqBins = windowSize / 2;
            dos.writeInt(startFrame);
            dos.writeInt(frameCount);
            for (int f = 0; f < frameCount; f++) {
                for (int b = 0; b < freqBins; b++) {
                    dos.writeDouble(partialMatrix.get(b, f));
                }
            }
            dos.flush();
            long tEnd = System.nanoTime();

            double recvMs = (tRecv - tStart) / 1_000_000.0;
            double computeMs = (tCompute - tRecv) / 1_000_000.0;
            double sendMs = (tEnd - tCompute) / 1_000_000.0;

            System.out.printf("[WorkerNode-%d] Frames [%d..%d] (%d frames) | Recv: %.1fms, Calc: %.1fms, Send: %.1fms%n",
                    port, startFrame, startFrame + frameCount - 1, frameCount, recvMs, computeMs, sendMs);

        } catch (Exception e) {
            System.err.printf("[WorkerNode-%d] Error procesando subtarea: %s%n", port, e.getMessage());
        }
    }

    public static void main(String[] args) throws Exception {
        int port = args.length > 0 ? Integer.parseInt(args[0]) : 5001;
        int threads = args.length > 1 ? Integer.parseInt(args[1]) : Runtime.getRuntime().availableProcessors();

        System.out.println("==========================================================");
        System.out.printf("          NODO WORKER FFT - PUERTO %d                     %n", port);
        System.out.println("==========================================================");

        WorkerNodeServer server = new WorkerNodeServer(port, threads);
        server.start();
    }
}
