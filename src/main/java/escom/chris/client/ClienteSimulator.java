package escom.chris.client;

import escom.chris.audio.WavReader;
import escom.chris.concurrency.SharedResultMatrix;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.net.Socket;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.DoubleAdder;

/**
 * Simulador de carga multicliiente para medir escalabilidad del servidor.
 * Dispara C clientes simultáneos (C = {1,2,4,8,16,32}) y reporta:
 * latencia promedio, throughput y tasa de rechazo.
 */
public final class ClienteSimulator {

    private static final int BUFFER_SIZE = 65536;
    private static final int[] CLIENT_COUNTS = {1, 2, 4, 8, 16, 32};
    private static final int CONNECT_TIMEOUT_MS = 3_000;
    private static final int SO_TIMEOUT_MS = 120_000;

    public static void main(String[] args) throws Exception {
        if (args.length < 3) {
            System.out.println("Uso: java escom.chris.client.ClienteSimulator <host> <puerto> <archivo.wav> [windowSize] [hopSize]");
            System.out.println("Ejemplo: java escom.chris.client.ClienteSimulator localhost 5000 src/main/resources/audio.wav 1024 512");
            return;
        }

        String host = args[0];
        int port = Integer.parseInt(args[1]);
        String wavPath = args[2];
        int windowSize = args.length > 3 ? Integer.parseInt(args[3]) : 1024;
        int hopSize = args.length > 4 ? Integer.parseInt(args[4]) : windowSize / 2;

        System.out.println();
        System.out.println("      SIMULADOR MULTICLIENTE FFT (escalabilidad)           ");
        System.out.println();
        System.out.printf("Destino: %s:%d | Audio: %s%n", host, port, wavPath);
        System.out.printf("Ventana: %d | Hop: %d%n", windowSize, hopSize);
        System.out.println("Cargas: C = {1, 2, 4, 8, 16, 32}\n");

        WavReader.WavData wav = new WavReader().read(wavPath);
        System.out.printf("WAV precargado: %d muestras (%.1f KB de doubles)%n%n",
                wav.samples.length, wav.samples.length * 8.0 / 1024.0);

        System.out.printf("%-6s %-14s %-16s %-14s %-12s%n",
                "C", "Latencia avg", "Throughput", "Exitosos", "Rechazo %");
        System.out.println("------------------------------------------------------------------");

        for (int c : CLIENT_COUNTS) {
            RunResult result = runWave(host, port, wav.samples, windowSize, hopSize, c);
            System.out.printf("%-6d %-14s %-16s %-14s %-12s%n",
                    c,
                    String.format("%.2f ms", result.avgLatencyMs),
                    String.format("%.2f req/s", result.throughputReqPerSec),
                    result.successes + "/" + c,
                    String.format("%.1f%%", result.rejectionRatePct));
            // Breve pausa entre oleadas para no mezclar carga residual
            Thread.sleep(500);
        }

        System.out.println();
        System.out.println("Simulación completada.");
    }

    private static RunResult runWave(String host, int port, double[] samples,
                                     int windowSize, int hopSize, int clientCount)
            throws InterruptedException {

        ExecutorService clients = Executors.newFixedThreadPool(clientCount);
        CountDownLatch startGate = new CountDownLatch(1);
        AtomicInteger successes = new AtomicInteger(0);
        AtomicInteger rejections = new AtomicInteger(0);
        DoubleAdder latencySumMs = new DoubleAdder();
        List<Future<?>> futures = new ArrayList<>(clientCount);

        for (int i = 0; i < clientCount; i++) {
            futures.add(clients.submit(() -> {
                try {
                    startGate.await();
                    long t0 = System.nanoTime();
                    requestFft(host, port, samples, windowSize, hopSize);
                    long t1 = System.nanoTime();
                    latencySumMs.add((t1 - t0) / 1_000_000.0);
                    successes.incrementAndGet();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    rejections.incrementAndGet();
                } catch (IOException e) {
                    // connection refused / timeout / reset → saturación o rechazo
                    rejections.incrementAndGet();
                } catch (Exception e) {
                    rejections.incrementAndGet();
                }
            }));
        }

        long wallStart = System.nanoTime();
        startGate.countDown();
        for (Future<?> f : futures) {
            try {
                f.get();
            } catch (Exception ignored) {
                // ya contado en el runnable
            }
        }
        long wallEnd = System.nanoTime();
        clients.shutdown();
        clients.awaitTermination(2, TimeUnit.MINUTES);

        int ok = successes.get();
        int rejected = rejections.get();
        double wallSec = (wallEnd - wallStart) / 1_000_000_000.0;
        double avgLatency = ok > 0 ? latencySumMs.sum() / ok : 0.0;
        double throughput = wallSec > 0 ? ok / wallSec : 0.0;
        double rejectionPct = clientCount > 0 ? (100.0 * rejected) / clientCount : 0.0;

        return new RunResult(avgLatency, throughput, ok, rejectionPct);
    }

    private static void requestFft(String host, int port, double[] samples,
                                   int windowSize, int hopSize) throws IOException {
        try (Socket socket = new Socket()) {
            socket.connect(new java.net.InetSocketAddress(host, port), CONNECT_TIMEOUT_MS);
            socket.setSoTimeout(SO_TIMEOUT_MS);

            try (DataOutputStream dos = new DataOutputStream(
                    new BufferedOutputStream(socket.getOutputStream(), BUFFER_SIZE));
                 DataInputStream dis = new DataInputStream(
                         new BufferedInputStream(socket.getInputStream(), BUFFER_SIZE))) {

                dos.writeInt(windowSize);
                dos.writeInt(hopSize);
                dos.writeInt(samples.length);
                for (double sample : samples) {
                    dos.writeDouble(sample);
                }
                dos.flush();

                SharedResultMatrix.readFromBinaryStream(dis);
            }
        }
    }

    private record RunResult(
            double avgLatencyMs,
            double throughputReqPerSec,
            int successes,
            double rejectionRatePct
    ) {}
}
