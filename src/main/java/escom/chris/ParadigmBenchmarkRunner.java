package escom.chris;

import com.distribuidos.fft.rmi.IFFTService;
import com.distribuidos.fft.rmi.RMIServer;
import escom.chris.audio.WavReader;
import escom.chris.concurrency.SharedResultMatrix;
import escom.chris.server.FFTLoadBalancer;
import escom.chris.server.MultiClientServer;
import escom.chris.server.WorkerNodeServer;

import java.io.*;
import java.net.Socket;
import java.rmi.registry.LocateRegistry;
import java.rmi.registry.Registry;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.*;

public class ParadigmBenchmarkRunner {

    public static void main(String[] args) throws Exception {
        String wavPath = args.length > 0 ? args[0] : "src/main/resources/audio.wav";
        WavReader.WavData wav = new WavReader().read(wavPath);
        double[] samples = wav.samples;

        System.out.println("===============================================================================");
        System.out.println("          SUITE DE BENCHMARKS COMPARATIVOS DE SISTEMAS DISTRIBUIDOS            ");
        System.out.println("===============================================================================");
        System.out.printf("Audio: %s (%d muestras, %.2f s)%n%n", wavPath, samples.length, samples.length / (double) wav.sampleRate);

        runTest1(samples);
        runTest2(samples);
        runTest3(samples);
        runTest4(samples);

        System.out.println("[BENCHMARK COMPLETO] Finalizado con éxito.");
        System.exit(0);
    }

    // -------------------------------------------------------------------------------------------------
    // TEST 1: Sockets TCP puros vs. Java RMI
    // -------------------------------------------------------------------------------------------------
    private static void runTest1(double[] samples) throws Exception {
        System.out.println(">>> 1. COMPARATIVA DE PARADIGMA: SOCKETS TCP PUROS VS. JAVA RMI <<<");

        // Iniciar MultiClientServer TCP en puerto 5000
        Thread tcpThread = new Thread(() -> MultiClientServer.main(new String[]{"5000"}));
        tcpThread.setDaemon(true);
        tcpThread.start();
        Thread.sleep(600);

        // Iniciar RMIServer en puerto 1099
        Thread rmiThread = new Thread(() -> RMIServer.main(new String[0]));
        rmiThread.setDaemon(true);
        rmiThread.start();
        Thread.sleep(600);

        int totalIter = 20;
        int warmup = 5;

        // --- Benchmark TCP ---
        List<Double> tcpRtts = new ArrayList<>();
        long tcpPayloadSent = 0;
        long tcpPayloadRecv = 0;

        for (int i = 0; i < totalIter; i++) {
            long t0 = System.nanoTime();
            try (Socket socket = new Socket("localhost", 5000);
                 DataOutputStream dos = new DataOutputStream(new BufferedOutputStream(socket.getOutputStream(), 65536));
                 DataInputStream dis = new DataInputStream(new BufferedInputStream(socket.getInputStream(), 65536))) {

                dos.writeInt(1024);
                dos.writeInt(512);
                dos.writeInt(samples.length);
                for (double s : samples) dos.writeDouble(s);
                dos.flush();

                SharedResultMatrix matrix = SharedResultMatrix.readFromBinaryStream(dis);
                long t1 = System.nanoTime();
                double rtt = (t1 - t0) / 1_000_000.0;

                if (i >= warmup) {
                    tcpRtts.add(rtt);
                }

                if (i == 0) {
                    tcpPayloadSent = 12L + samples.length * 8L;
                    tcpPayloadRecv = 8L + (long) matrix.getNumChunks() * (1024 / 2) * 8L;
                }
            }
        }

        // --- Benchmark RMI ---
        Registry registry = LocateRegistry.getRegistry("localhost", 1099);
        IFFTService rmiService = (IFFTService) registry.lookup("FFTService");
        List<Double> rmiRtts = new ArrayList<>();

        ByteArrayOutputStream baosArg = new ByteArrayOutputStream();
        ObjectOutputStream oosArg = new ObjectOutputStream(baosArg);
        oosArg.writeObject(samples);
        oosArg.writeInt(1024);
        oosArg.writeInt(512);
        oosArg.flush();
        long rmiPayloadSent = baosArg.toByteArray().length;

        double[][] testSpec = rmiService.computeSpectrogram(samples, 1024, 512);
        ByteArrayOutputStream baosRes = new ByteArrayOutputStream();
        ObjectOutputStream oosRes = new ObjectOutputStream(baosRes);
        oosRes.writeObject(testSpec);
        oosRes.flush();
        long rmiPayloadRecv = baosRes.toByteArray().length;

        for (int i = 0; i < totalIter; i++) {
            long t0 = System.nanoTime();
            double[][] spec = rmiService.computeSpectrogram(samples, 1024, 512);
            long t1 = System.nanoTime();
            double rtt = (t1 - t0) / 1_000_000.0;

            if (i >= warmup) {
                rmiRtts.add(rtt);
            }
        }

        printTest1Results(tcpRtts, rmiRtts, tcpPayloadSent, tcpPayloadRecv, rmiPayloadSent, rmiPayloadRecv);
    }

    private static void printTest1Results(List<Double> tcp, List<Double> rmi,
                                          long tcpSent, long tcpRecv, long rmiSent, long rmiRecv) {
        double avgTcp = tcp.stream().mapToDouble(Double::doubleValue).average().orElse(0);
        double minTcp = Collections.min(tcp);
        double maxTcp = Collections.max(tcp);

        double avgRmi = rmi.stream().mapToDouble(Double::doubleValue).average().orElse(0);
        double minRmi = Collections.min(rmi);
        double maxRmi = Collections.max(rmi);

        System.out.println("---------------------------------------------------------------------------------------------------------");
        System.out.printf("%-20s | %-12s | %-12s | %-12s | %-18s | %-18s%n",
                "Paradigma", "RTT Prom (ms)", "RTT Min (ms)", "RTT Max (ms)", "Bytes Env (Sent)", "Bytes Recv (Recv)");
        System.out.println("---------------------------------------------------------------------------------------------------------");
        System.out.printf("%-20s | %12.2f | %12.2f | %12.2f | %18d | %18d%n",
                "Sockets TCP Puros", avgTcp, minTcp, maxTcp, tcpSent, tcpRecv);
        System.out.printf("%-20s | %12.2f | %12.2f | %12.2f | %18d | %18d%n",
                "Java RMI", avgRmi, minRmi, maxRmi, rmiSent, rmiRecv);
        System.out.println("---------------------------------------------------------------------------------------------------------\n");
    }

    // -------------------------------------------------------------------------------------------------
    // TEST 2: Escalabilidad Horizontal del Clúster (Scatter-Gather)
    // -------------------------------------------------------------------------------------------------
    private static void runTest2(double[] samples) throws Exception {
        System.out.println(">>> 2. ESCALABILIDAD HORIZONTAL DEL CLÚSTER (SCATTER-GATHER) <<<");

        int[] clusterSizes = {1, 2, 4};
        int[] clientLoads = {16, 32};

        System.out.println("------------------------------------------------------------------------------------------------------------------");
        System.out.printf("%-12s | %-12s | %-18s | %-16s | %-14s | %-12s%n",
                "Nodos (K)", "Clientes (C)", "Throughput (req/s)", "Lat. Prom (ms)", "Speedup (S_K)", "Éxito");
        System.out.println("------------------------------------------------------------------------------------------------------------------");

        double baseThroughputC16 = 0;
        double baseThroughputC32 = 0;

        for (int k : clusterSizes) {
            List<WorkerNodeServer> workers = new ArrayList<>();
            List<FFTLoadBalancer.WorkerNode> endpoints = new ArrayList<>();

            for (int i = 0; i < k; i++) {
                int port = 5001 + i;
                WorkerNodeServer w = new WorkerNodeServer(port, 4);
                workers.add(w);
                endpoints.add(new FFTLoadBalancer.WorkerNode("localhost", port));
                Thread wt = new Thread(() -> { try { w.start(); } catch (Exception ignored) {} });
                wt.setDaemon(true);
                wt.start();
            }
            Thread.sleep(400);

            FFTLoadBalancer broker = new FFTLoadBalancer(5000, endpoints, FFTLoadBalancer.Strategy.ROUND_ROBIN);
            Thread bt = new Thread(() -> { try { broker.start(); } catch (Exception ignored) {} });
            bt.setDaemon(true);
            bt.start();
            Thread.sleep(400);

            for (int c : clientLoads) {
                ExecutorService exec = Executors.newFixedThreadPool(c);
                CyclicBarrier barrier = new CyclicBarrier(c);
                CountDownLatch latch = new CountDownLatch(c);
                List<Double> lats = Collections.synchronizedList(new ArrayList<>());

                long tStart = System.nanoTime();
                for (int i = 0; i < c; i++) {
                    exec.submit(() -> {
                        try {
                            barrier.await();
                            long t0 = System.nanoTime();
                            try (Socket socket = new Socket("localhost", 5000);
                                 DataOutputStream dos = new DataOutputStream(new BufferedOutputStream(socket.getOutputStream(), 65536));
                                 DataInputStream dis = new DataInputStream(new BufferedInputStream(socket.getInputStream(), 65536))) {
                                dos.writeInt(1024);
                                dos.writeInt(512);
                                dos.writeInt(samples.length);
                                for (double s : samples) dos.writeDouble(s);
                                dos.flush();
                                SharedResultMatrix m = SharedResultMatrix.readFromBinaryStream(dis);
                                if (m.getNumChunks() > 0) {
                                    lats.add((System.nanoTime() - t0) / 1_000_000.0);
                                }
                            }
                        } catch (Exception ignored) {}
                        finally { latch.countDown(); }
                    });
                }

                latch.await();
                long tEnd = System.nanoTime();
                exec.shutdown();

                double durSec = (tEnd - tStart) / 1_000_000_000.0;
                double tp = lats.size() / durSec;
                double avgLat = lats.stream().mapToDouble(Double::doubleValue).average().orElse(0);

                if (k == 1 && c == 16) baseThroughputC16 = tp;
                if (k == 1 && c == 32) baseThroughputC32 = tp;

                double base = (c == 16) ? baseThroughputC16 : baseThroughputC32;
                double speedup = base > 0 ? tp / base : 1.0;

                System.out.printf("%-12d | %-12d | %18.2f | %16.1f | %14.2fx | %9d/%d%n",
                        k, c, tp, avgLat, speedup, lats.size(), c);

                Thread.sleep(300);
            }

            broker.stop();
            for (WorkerNodeServer w : workers) w.stop();
            Thread.sleep(500);
        }
        System.out.println("------------------------------------------------------------------------------------------------------------------\n");
    }

    // -------------------------------------------------------------------------------------------------
    // TEST 3: Impacto del Tamaño del Payload (Sensibilidad del Ventaneo FFT)
    // -------------------------------------------------------------------------------------------------
    private static void runTest3(double[] samples) throws Exception {
        System.out.println(">>> 3. IMPACTO DEL TAMAÑO DEL PAYLOAD (SENSIBILIDAD DEL VENTANEO FFT) <<<");

        int[][] configs = {
                {2048, 512}, // Fina
                {1024, 512}, // Estándar
                {512, 256}   // Gruesa
        };
        String[] names = {"Fina (N=2048, Hop=512)", "Estándar (N=1024, Hop=512)", "Gruesa (N=512, Hop=256)"};

        System.out.println("---------------------------------------------------------------------------------------------------------------");
        System.out.printf("%-26s | %-12s | %-16s | %-16s | %-18s | %-14s%n",
                "Configuración Ventaneo", "Matriz", "Envío Audio (ms)", "Cómputo/Red (ms)", "Descarga Matriz(ms)", "RTT Total (ms)");
        System.out.println("---------------------------------------------------------------------------------------------------------------");

        for (int c = 0; c < configs.length; c++) {
            int win = configs[c][0];
            int hop = configs[c][1];

            long t0 = System.nanoTime();
            Socket socket = new Socket("localhost", 5000);
            DataOutputStream dos = new DataOutputStream(new BufferedOutputStream(socket.getOutputStream(), 65536));
            DataInputStream dis = new DataInputStream(new BufferedInputStream(socket.getInputStream(), 65536));

            dos.writeInt(win);
            dos.writeInt(hop);
            dos.writeInt(samples.length);
            for (double s : samples) dos.writeDouble(s);
            dos.flush();
            long t1 = System.nanoTime();

            int totalFrames = dis.readInt();
            int freqBins = dis.readInt();

            long t2 = System.nanoTime();

            for (int f = 0; f < totalFrames; f++) {
                for (int b = 0; b < freqBins; b++) {
                    dis.readDouble();
                }
            }
            long t3 = System.nanoTime();
            socket.close();

            double tSend = (t1 - t0) / 1_000_000.0;
            double tServerComp = (t2 - t1) / 1_000_000.0;
            double tRecv = (t3 - t2) / 1_000_000.0;
            double tTotal = (t3 - t0) / 1_000_000.0;

            String dim = totalFrames + "x" + freqBins;

            System.out.printf("%-26s | %-12s | %16.2f | %16.2f | %18.2f | %14.2f%n",
                    names[c], dim, tSend, tServerComp, tRecv, tTotal);
        }
        System.out.println("---------------------------------------------------------------------------------------------------------------\n");
    }

    // -------------------------------------------------------------------------------------------------
    // TEST 4: Prueba de Resiliencia / Tolerancia a Fallos (Broker Scatter-Gather)
    // -------------------------------------------------------------------------------------------------
    private static void runTest4(double[] samples) throws Exception {
        System.out.println(">>> 4. PRUEBA DE RESILIENCIA / TOLERANCIA A FALLOS (BROKER SCATTER-GATHER) <<<");

        WorkerNodeServer w1 = new WorkerNodeServer(5001, 4);
        WorkerNodeServer w2 = new WorkerNodeServer(5002, 4);

        Thread t1 = new Thread(() -> { try { w1.start(); } catch (Exception ignored) {} });
        Thread t2 = new Thread(() -> { try { w2.start(); } catch (Exception ignored) {} });
        t1.setDaemon(true);
        t2.setDaemon(true);
        t1.start();
        t2.start();
        Thread.sleep(400);

        List<FFTLoadBalancer.WorkerNode> nodes = new ArrayList<>();
        nodes.add(new FFTLoadBalancer.WorkerNode("localhost", 5001));
        nodes.add(new FFTLoadBalancer.WorkerNode("localhost", 5002));

        FFTLoadBalancer broker = new FFTLoadBalancer(5000, nodes, FFTLoadBalancer.Strategy.ROUND_ROBIN);
        Thread bt = new Thread(() -> { try { broker.start(); } catch (Exception ignored) {} });
        bt.setDaemon(true);
        bt.start();
        Thread.sleep(400);

        System.out.println("[Test 4] Estado Inicial: Broker iniciado con 2 Workers (puertos 5001 y 5002).");

        // Detener Worker 2 abruptamente para simular la caída inesperada
        System.out.println("[Test 4] SIMULANDO CAÍDA INESPERADA: Deteniendo Worker 5002 a mitad de operación...");
        w2.stop();
        Thread.sleep(200);

        System.out.println("[Test 4] Enviando solicitud cliente al Broker tras la caída del Worker...");
        long tStart = System.currentTimeMillis();
        boolean failed = false;
        String errorMsg = "";

        try (Socket socket = new Socket("localhost", 5000);
             DataOutputStream dos = new DataOutputStream(new BufferedOutputStream(socket.getOutputStream(), 65536));
             DataInputStream dis = new DataInputStream(new BufferedInputStream(socket.getInputStream(), 65536))) {

            dos.writeInt(1024);
            dos.writeInt(512);
            dos.writeInt(samples.length);
            for (double s : samples) dos.writeDouble(s);
            dos.flush();

            SharedResultMatrix m = SharedResultMatrix.readFromBinaryStream(dis);
            if (m.getNumChunks() == 0) {
                failed = true;
                errorMsg = "Respuesta vacía o incompleta recibida del Broker.";
            }
        } catch (Exception e) {
            failed = true;
            errorMsg = e.getClass().getName() + ": " + e.getMessage();
        }

        long tEnd = System.currentTimeMillis();
        System.out.printf("[Test 4] Tiempo transcurrido hasta fallo/respuesta: %d ms%n", (tEnd - tStart));
        System.out.printf("[Test 4] Resultado: %s (Detalle: %s)%n", failed ? "FALLO DETECTADO / EXCEPCIÓN CAPTURADA" : "ÉXITO", errorMsg);

        broker.stop();
        w1.stop();
        System.out.println("---------------------------------------------------------------------------------------------------------------\n");
    }
}
