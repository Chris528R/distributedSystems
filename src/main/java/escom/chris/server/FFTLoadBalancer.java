package escom.chris.server;

import escom.chris.concurrency.SharedResultMatrix;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Load Balancer / Broker para el clúster de FFT (Arquitectura Scatter-Gather).
 * Actúa como Servidor frente a los clientes externos (puerto 5000)
 * y como Cliente frente a los nodos trabajadores (puertos 5001, 5002, etc.).
 */
public final class FFTLoadBalancer {

    private static final int BUFFER_SIZE = 65536; // 64 KB

    public record WorkerNode(String host, int port, AtomicInteger activeTasks) {
        public WorkerNode(String host, int port) {
            this(host, port, new AtomicInteger(0));
        }

        @Override
        public String toString() {
            return host + ":" + port;
        }
    }

    public enum Strategy {
        ROUND_ROBIN,
        LEAST_CONNECTIONS
    }

    private final int listenPort;
    private final List<WorkerNode> workers;
    private final Strategy strategy;
    private final ExecutorService clientPool;
    private final ExecutorService scatterPool;
    private final AtomicInteger roundRobinCounter = new AtomicInteger(0);
    private volatile boolean running = true;
    private ServerSocket serverSocket;

    public FFTLoadBalancer(int listenPort, List<WorkerNode> workers, Strategy strategy) {
        this.listenPort = listenPort;
        this.workers = new ArrayList<>(workers);
        this.strategy = strategy;
        this.clientPool = Executors.newCachedThreadPool();
        this.scatterPool = Executors.newCachedThreadPool();
    }

    public void start() throws IOException {
        serverSocket = new ServerSocket(listenPort);
        System.out.println("==========================================================");
        System.out.println("     LOAD BALANCER / BROKER FFT (Scatter-Gather)          ");
        System.out.println("==========================================================");
        System.out.printf("Puerto maestro de escucha: %d%n", listenPort);
        System.out.printf("Estrategia de balanceo: %s%n", strategy);
        System.out.printf("Nodos trabajadores registrados (%d): %s%n", workers.size(), workers);
        System.out.println("Listo para recibir peticiones de clientes...\n");

        while (running) {
            try {
                Socket clientSocket = serverSocket.accept();
                clientPool.submit(() -> handleClientRequest(clientSocket));
            } catch (IOException e) {
                if (!running) break;
                System.err.printf("[Broker] Error aceptando cliente: %s%n", e.getMessage());
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
        clientPool.shutdown();
        scatterPool.shutdown();
    }

    private void handleClientRequest(Socket clientSocket) {
        String clientAddr = clientSocket.getRemoteSocketAddress().toString();

        try (clientSocket;
             DataInputStream dis = new DataInputStream(new BufferedInputStream(clientSocket.getInputStream(), BUFFER_SIZE));
             DataOutputStream dos = new DataOutputStream(new BufferedOutputStream(clientSocket.getOutputStream(), BUFFER_SIZE))) {

            long tStart = System.nanoTime();

            // 1. Leer petición del cliente: [windowSize, hopSize, sampleCount, samples]
            int windowSize = dis.readInt();
            int hopSize = dis.readInt();
            int sampleCount = dis.readInt();

            double[] samples = new double[sampleCount];
            for (int i = 0; i < sampleCount; i++) {
                samples[i] = dis.readDouble();
            }

            int totalFrames = Math.max(1, (sampleCount - windowSize) / hopSize + 1);
            int freqBins = windowSize / 2;
            SharedResultMatrix assembledMatrix = new SharedResultMatrix(freqBins, totalFrames);

            long tRecv = System.nanoTime();

            // 2. Scatter-Gather: dividir frames entre los nodos trabajadores
            int numNodes = workers.size();
            List<CompletableFuture<Void>> futures = new ArrayList<>();

            for (int k = 0; k < numNodes; k++) {
                int startFrame = k * totalFrames / numNodes;
                int endFrame = (k + 1) * totalFrames / numNodes;
                int frameCount = endFrame - startFrame;
                if (frameCount <= 0) continue;

                WorkerNode worker = selectWorker(k);

                // Subarreglo de muestras para este bloque
                int startSample = startFrame * hopSize;
                int neededSamples = (frameCount - 1) * hopSize + windowSize;
                double[] subSamples = new double[neededSamples];
                int copyLen = Math.min(neededSamples, samples.length - startSample);
                if (copyLen > 0) {
                    System.arraycopy(samples, startSample, subSamples, 0, copyLen);
                }

                int finalK = k;
                CompletableFuture<Void> future = CompletableFuture.runAsync(() -> {
                    worker.activeTasks().incrementAndGet();
                    try {
                        dispatchSubtask(worker, startFrame, frameCount, windowSize, hopSize, subSamples, assembledMatrix);
                    } finally {
                        worker.activeTasks().decrementAndGet();
                    }
                }, scatterPool);

                futures.add(future);
            }

            // Esperar a que todos los nodos completen su porción (Gather)
            CompletableFuture.allOf(futures.toArray(new CompletableFuture[0])).join();
            long tGather = System.nanoTime();

            // 3. Devolver la matriz ensamblada completa al cliente
            assembledMatrix.writeToBinaryStream(dos);
            dos.flush();
            long tEnd = System.nanoTime();

            double recvMs = (tRecv - tStart) / 1_000_000.0;
            double scatterGatherMs = (tGather - tRecv) / 1_000_000.0;
            double sendMs = (tEnd - tGather) / 1_000_000.0;
            double totalMs = (tEnd - tStart) / 1_000_000.0;

            System.out.printf("[Broker] Petición %s | %d frames divididos en %d nodos | Scatter-Gather: %.1fms | Total: %.1fms%n",
                    clientAddr, totalFrames, numNodes, scatterGatherMs, totalMs);

        } catch (Exception e) {
            System.err.printf("[Broker] Error procesando cliente %s: %s%n", clientAddr, e.getMessage());
        }
    }

    private WorkerNode selectWorker(int partitionIndex) {
        if (strategy == Strategy.ROUND_ROBIN || workers.isEmpty()) {
            int idx = Math.abs(partitionIndex % workers.size());
            return workers.get(idx);
        } else {
            // Least Connections
            WorkerNode best = workers.get(0);
            int minTasks = best.activeTasks().get();
            for (WorkerNode w : workers) {
                int active = w.activeTasks().get();
                if (active < minTasks) {
                    minTasks = active;
                    best = w;
                }
            }
            return best;
        }
    }

    private void dispatchSubtask(WorkerNode worker, int startFrame, int frameCount,
                                 int windowSize, int hopSize, double[] subSamples,
                                 SharedResultMatrix assembledMatrix) {
        try (Socket socket = new Socket(worker.host(), worker.port());
             DataOutputStream dos = new DataOutputStream(new BufferedOutputStream(socket.getOutputStream(), BUFFER_SIZE));
             DataInputStream dis = new DataInputStream(new BufferedInputStream(socket.getInputStream(), BUFFER_SIZE))) {

            // Protocolo [BROKER -> NODO WORKER]:
            // startFrame (int), frameCount (int), windowSize (int), hopSize (int), rawSamples (doubles)
            dos.writeInt(startFrame);
            dos.writeInt(frameCount);
            dos.writeInt(windowSize);
            dos.writeInt(hopSize);
            for (double s : subSamples) {
                dos.writeDouble(s);
            }
            dos.flush();

            // Protocolo [NODO WORKER -> BROKER]:
            // startFrame (int), frameCount (int), data (doubles)
            int respStart = dis.readInt();
            int respCount = dis.readInt();
            int freqBins = windowSize / 2;

            for (int f = 0; f < respCount; f++) {
                double[] col = new double[freqBins];
                for (int b = 0; b < freqBins; b++) {
                    col[b] = dis.readDouble();
                }
                assembledMatrix.setColumn(respStart + f, col);
            }

        } catch (IOException e) {
            System.err.printf("[Broker] Error en comunicación con nodo %s (frames [%d..%d]): %s%n",
                    worker, startFrame, startFrame + frameCount - 1, e.getMessage());
            throw new RuntimeException(e);
        }
    }

    public static void main(String[] args) throws Exception {
        int port = args.length > 0 ? Integer.parseInt(args[0]) : 5000;
        List<WorkerNode> nodes = new ArrayList<>();

        if (args.length > 1) {
            for (int i = 1; i < args.length; i++) {
                String[] parts = args[i].split(":");
                String host = parts[0];
                int p = Integer.parseInt(parts[1]);
                nodes.add(new WorkerNode(host, p));
            }
        } else {
            // Valores por defecto: 2 nodos locales
            nodes.add(new WorkerNode("localhost", 5001));
            nodes.add(new WorkerNode("localhost", 5002));
        }

        FFTLoadBalancer broker = new FFTLoadBalancer(port, nodes, Strategy.ROUND_ROBIN);
        broker.start();
    }
}
