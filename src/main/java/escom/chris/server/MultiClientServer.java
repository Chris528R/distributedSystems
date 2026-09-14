package escom.chris.server;

import escom.chris.concurrency.GlobalFftWorkerPool;

import java.io.IOException;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Servidor multicliiente con arquitectura desacoplada en dos etapas:
 * 1) I/O Connection Pool — lectura/escritura de sockets.
 * 2) Global FFT Worker Pool — cómputo compartido por todos los clientes.
 */
public final class MultiClientServer {

    public static void main(String[] args) {
        int port = args.length > 0 ? Integer.parseInt(args[0]) : 5000;
        int ioPoolSize = args.length > 1 ? Integer.parseInt(args[1]) : 8;
        int computePoolSize = args.length > 2
                ? Integer.parseInt(args[2])
                : Runtime.getRuntime().availableProcessors();
        int backlog = args.length > 3 ? Integer.parseInt(args[3]) : 50;
        int computeQueueCapacity = Math.max(computePoolSize * 64, 256);
        int ioQueueCapacity = Math.max(ioPoolSize * 8, 32);

        System.out.println();
        System.out.println("     SERVIDOR FFT MULTICLIENTE (I/O Pool + Compute Pool)   ");
        System.out.println();
        System.out.printf("Puerto: %d | backlog ServerSocket: %d%n", port, backlog);
        System.out.printf("I/O Connection Pool: %d hilos (cola=%d)%n", ioPoolSize, ioQueueCapacity);
        System.out.printf("Global FFT Worker Pool: %d hilos (cola=%d)%n", computePoolSize, computeQueueCapacity);
        System.out.println("Esperando conexiones de clientes...\n");

        AtomicLong acceptedConnections = new AtomicLong(0);
        AtomicLong rejectedIoHandlers = new AtomicLong(0);

        try (GlobalFftWorkerPool sharedComputePool = new GlobalFftWorkerPool(computePoolSize, computeQueueCapacity);
             ServerSocket serverSocket = new ServerSocket(port, backlog);
             ThreadPoolExecutor ioPool = new ThreadPoolExecutor(
                     ioPoolSize,
                     ioPoolSize,
                     0L,
                     TimeUnit.MILLISECONDS,
                     new ArrayBlockingQueue<>(ioQueueCapacity),
                     r -> {
                         Thread t = new Thread(r, "IoConnection-Worker");
                         t.setDaemon(false);
                         return t;
                     },
                     (r, ex) -> {
                         rejectedIoHandlers.incrementAndGet();
                         throw new RejectedExecutionException("I/O pool saturado");
                     })) {

            Runtime.getRuntime().addShutdownHook(new Thread(() -> {
                System.out.println("\n[Servidor] Apagando...");
                System.out.printf("Conexiones aceptadas: %d | Handlers I/O rechazados: %d | Tareas FFT rechazadas: %d%n",
                        acceptedConnections.get(),
                        rejectedIoHandlers.get(),
                        sharedComputePool.getRejectedTaskCount());
                ioPool.shutdownNow();
            }));

            while (true) {
                Socket clientSocket = serverSocket.accept();
                acceptedConnections.incrementAndGet();
                try {
                    ioPool.execute(new ClienteTaskHandler(clientSocket, sharedComputePool));
                } catch (RejectedExecutionException e) {
                    System.err.printf("[-] I/O pool saturado; cerrando conexión de %s%n",
                            clientSocket.getRemoteSocketAddress());
                    try {
                        clientSocket.close();
                    } catch (IOException ignored) {
                        // ya saturado; nada más que hacer
                    }
                }
            }
        } catch (IOException e) {
            System.err.printf("[Servidor] Error crítico en ServerSocket: %s%n", e.getMessage());
        }
    }
}
