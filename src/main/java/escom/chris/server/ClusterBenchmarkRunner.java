package escom.chris.server;

import escom.chris.audio.WavReader;
import escom.chris.concurrency.SharedResultMatrix;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.net.Socket;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.*;

/**
 * Ejecutor automatizado del experimento de escalabilidad horizontal para el clúster FFT.
 * Evalúa combinaciones de clúster con K in {1, 2, 4} servidores backend
 * y cargas concurrentes de C in {16, 32} clientes simultáneos.
 */
public final class ClusterBenchmarkRunner {

    private static final int BROKER_PORT = 5000;
    private static final int BASE_WORKER_PORT = 5001;

    public record TestResult(
            int numWorkers,
            int concurrency,
            double throughputReqSec,
            double avgLatencyMs,
            double minLatencyMs,
            double maxLatencyMs,
            double p95LatencyMs,
            int successfulRequests
    ) {}

    public static void main(String[] args) throws Exception {
        String wavPath = args.length > 0 ? args[0] : "src/main/resources/audio.wav";
        int windowSize = 1024;
        int hopSize = 512;

        System.out.println("===============================================================================");
        System.out.println("          BENCHMARK DE ESCALABILIDAD HORIZONTAL: CLÚSTER FFT                   ");
        System.out.println("===============================================================================");
        System.out.printf("Audio de prueba: %s%n", wavPath);

        // Pre-cargar audio en memoria para medir pura red + cómputo concurrente
        WavReader.WavData wav = new WavReader().read(wavPath);
        System.out.printf("Audio cargado: %d muestras (~%.2f s)%n%n",
                wav.samples.length, wav.samples.length / (double) wav.sampleRate);

        int[] clusterSizes = {1, 2, 4};
        int[] clientLoads = {16, 32};
        List<TestResult> allResults = new ArrayList<>();

        for (int k : clusterSizes) {
            System.out.printf(">>> INICIANDO PRUEBAS CON CLÚSTER DE K = %d SERVIDORES <<<%n", k);

            // 1. Iniciar K worker nodes
            List<WorkerNodeServer> workerInstances = new ArrayList<>();
            List<FFTLoadBalancer.WorkerNode> workerEndpoints = new ArrayList<>();

            for (int i = 0; i < k; i++) {
                int port = BASE_WORKER_PORT + i;
                WorkerNodeServer worker = new WorkerNodeServer(port, 4);
                workerInstances.add(worker);
                workerEndpoints.add(new FFTLoadBalancer.WorkerNode("localhost", port));

                new Thread(() -> {
                    try {
                        worker.start();
                    } catch (Exception ignored) {}
                }, "WorkerNodeRunner-" + port).start();
            }

            // Esperar arranque de workers
            Thread.sleep(500);

            // 2. Iniciar Load Balancer
            FFTLoadBalancer broker = new FFTLoadBalancer(BROKER_PORT, workerEndpoints, FFTLoadBalancer.Strategy.ROUND_ROBIN);
            new Thread(() -> {
                try {
                    broker.start();
                } catch (Exception ignored) {}
            }, "BrokerRunner").start();

            // Esperar arranque del broker
            Thread.sleep(500);

            // 3. Ejecutar cargas para C in {16, 32}
            for (int c : clientLoads) {
                System.out.printf("  -> Ejecutando carga con C = %2d clientes concurrentes... ", c);
                TestResult result = executeConcurrentLoad(c, k, wav.samples, windowSize, hopSize);
                allResults.add(result);
                System.out.printf("Throughput: %6.2f req/s | Latencia Promedio: %6.1f ms (P95: %6.1f ms)%n",
                        result.throughputReqSec(), result.avgLatencyMs(), result.p95LatencyMs());
                Thread.sleep(500);
            }

            // 4. Detener broker y workers
            broker.stop();
            for (WorkerNodeServer worker : workerInstances) {
                worker.stop();
            }
            Thread.sleep(800);
            System.out.println();
        }

        // 5. Imprimir tabla de resultados consolidados
        System.out.println("======================================================================================================");
        System.out.println("                          TABLA CONSOLIDADA DE RESULTADOS DEL CLÚSTER                                 ");
        System.out.println("======================================================================================================");
        System.out.printf("%-10s | %-10s | %-16s | %-14s | %-12s | %-12s | %-10s%n",
                "Servidores(K)", "Clientes(C)", "Throughput (req/s)", "Lat. Prom (ms)", "P95 Lat (ms)", "Max Lat (ms)", "Éxito");
        System.out.println("------------------------------------------------------------------------------------------------------");
        for (TestResult r : allResults) {
            System.out.printf("%-13d | %-11d | %18.2f | %14.1f | %12.1f | %12.1f | %9d/%d%n",
                    r.numWorkers(), r.concurrency(), r.throughputReqSec(), r.avgLatencyMs(), r.p95LatencyMs(), r.maxLatencyMs(), r.successfulRequests(), r.concurrency());
        }
        System.out.println("======================================================================================================");
    }

    private static TestResult executeConcurrentLoad(int concurrency, int numWorkers, double[] samples,
                                                    int windowSize, int hopSize) throws Exception {
        ExecutorService clientExecutor = Executors.newFixedThreadPool(concurrency);
        CyclicBarrier barrier = new CyclicBarrier(concurrency);
        List<Double> latencies = Collections.synchronizedList(new ArrayList<>());
        CountDownLatch latch = new CountDownLatch(concurrency);

        long tGlobalStart = System.nanoTime();

        for (int i = 0; i < concurrency; i++) {
            clientExecutor.submit(() -> {
                try {
                    barrier.await(); // Disparo sincronizado de todos los clientes
                    long tStart = System.nanoTime();

                    try (Socket socket = new Socket("localhost", BROKER_PORT);
                         DataOutputStream dos = new DataOutputStream(new BufferedOutputStream(socket.getOutputStream(), 65536));
                         DataInputStream dis = new DataInputStream(new BufferedInputStream(socket.getInputStream(), 65536))) {

                        dos.writeInt(windowSize);
                        dos.writeInt(hopSize);
                        dos.writeInt(samples.length);
                        for (double s : samples) {
                            dos.writeDouble(s);
                        }
                        dos.flush();

                        SharedResultMatrix matrix = SharedResultMatrix.readFromBinaryStream(dis);
                        if (matrix.getNumChunks() > 0) {
                            long tEnd = System.nanoTime();
                            latencies.add((tEnd - tStart) / 1_000_000.0);
                        }
                    }
                } catch (Exception e) {
                    System.err.printf("[Simulador] Error en cliente: %s%n", e.getMessage());
                } finally {
                    latch.countDown();
                }
            });
        }

        latch.await();
        long tGlobalEnd = System.nanoTime();
        clientExecutor.shutdown();

        double totalDurationSec = (tGlobalEnd - tGlobalStart) / 1_000_000_000.0;
        int successful = latencies.size();
        double throughput = successful / totalDurationSec;

        Collections.sort(latencies);
        double sum = 0.0;
        for (double l : latencies) sum += l;
        double avg = successful > 0 ? sum / successful : 0;
        double min = successful > 0 ? latencies.get(0) : 0;
        double max = successful > 0 ? latencies.get(successful - 1) : 0;
        int p95Idx = (int) Math.ceil(0.95 * successful) - 1;
        double p95 = (successful > 0 && p95Idx >= 0 && p95Idx < successful) ? latencies.get(p95Idx) : max;

        return new TestResult(numWorkers, concurrency, throughput, avg, min, max, p95, successful);
    }
}
