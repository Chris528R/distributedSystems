package escom.chris.ingestion.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import escom.chris.FFTPipelineManager;
import escom.chris.api.dto.WorkerComputeRequest;
import escom.chris.api.dto.WorkerComputeResponse;
import escom.chris.audio.ScatterPartitioner;
import escom.chris.audio.ScatterPartitioner.PartitionSlice;
import escom.chris.concurrency.SharedResultMatrix;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.net.InetAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Despachador Scatter-Gather que divide las ventanas del audio entre las
 * instancias activas de {@code service-worker-fft} y ensambla la matriz consolidada.
 * Soporta descubrimiento dinámico por DNS de Docker Compose (--scale worker-fft=N).
 */
@Service
public class WorkerScatterGatherClient {

    private static final Logger log = LoggerFactory.getLogger(WorkerScatterGatherClient.class);

    private final List<String> configuredWorkerUrls;
    private final ObjectMapper objectMapper;
    private final HttpClient httpClient;
    private final ExecutorService dispatchExecutor;
    private final AtomicInteger roundRobinCounter = new AtomicInteger(0);

    public WorkerScatterGatherClient(
            @Value("${app.workers.urls:http://localhost:8082,http://localhost:8083}") String[] urls,
            ObjectMapper objectMapper
    ) {
        this.configuredWorkerUrls = Arrays.stream(urls)
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .map(s -> s.replaceAll("/+$", ""))
                .toList();
        this.objectMapper = objectMapper;
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .build();
        this.dispatchExecutor = Executors.newVirtualThreadPerTaskExecutor();
    }

    public record ScatterGatherResult(
            int totalFrames,
            int bins,
            double[][] magnitudes,
            long computeMs,
            int activeWorkersUsed
    ) {
    }

    /**
     * Expande los hostnames configurados mediante DNS (en Docker Compose,
     * {@code InetAddress.getAllByName("worker-fft")} devuelve todas las IPs
     * de las réplicas escaladas con {@code --scale worker-fft=N}).
     */
    public List<String> resolveActiveWorkerUrls() {
        List<String> resolved = new ArrayList<>();
        for (String rawUrl : configuredWorkerUrls) {
            try {
                URI uri = URI.create(rawUrl);
                String host = uri.getHost();
                int port = uri.getPort() != -1 ? uri.getPort() : 8082;
                String scheme = uri.getScheme() != null ? uri.getScheme() : "http";

                if ("localhost".equalsIgnoreCase(host) || "127.0.0.1".equals(host)) {
                    resolved.add(rawUrl);
                } else {
                    InetAddress[] addresses = InetAddress.getAllByName(host);
                    if (addresses.length > 1) {
                        for (InetAddress addr : addresses) {
                            resolved.add(scheme + "://" + addr.getHostAddress() + ":" + port);
                        }
                    } else {
                        resolved.add(rawUrl);
                    }
                }
            } catch (Exception e) {
                resolved.add(rawUrl);
            }
        }
        return resolved.isEmpty() ? List.of("http://localhost:8082") : resolved;
    }

    public ScatterGatherResult computeDistributed(
            String audioId,
            double[] samples,
            int windowSize,
            int hopSize
    ) {
        List<String> activeUrls = resolveActiveWorkerUrls();
        int numWorkerNodes = Math.max(1, activeUrls.size());
        List<PartitionSlice> slices = ScatterPartitioner.partition(samples, windowSize, hopSize, numWorkerNodes);

        int totalFrames = ScatterPartitioner.totalFrames(samples.length, windowSize, hopSize);
        int bins = windowSize / 2;
        int baseOffset = roundRobinCounter.getAndIncrement();

        long t0 = System.nanoTime();

        List<CompletableFuture<WorkerComputeResponse>> futures = slices.stream()
                .map(slice -> {
                    String workerUrl = activeUrls.get(Math.floorMod(baseOffset + slice.partitionIndex(), activeUrls.size()));
                    return CompletableFuture.supplyAsync(
                            () -> dispatchSliceToWorker(audioId, slice, windowSize, hopSize, workerUrl),
                            dispatchExecutor
                    );
                })
                .toList();

        double[][] consolidated = new double[totalFrames][bins];
        for (CompletableFuture<WorkerComputeResponse> future : futures) {
            WorkerComputeResponse part = future.join();
            double[][] partMagnitudes = part.magnitudes();
            int start = part.startFrame();
            for (int f = 0; f < partMagnitudes.length; f++) {
                if (start + f < totalFrames) {
                    consolidated[start + f] = partMagnitudes[f];
                }
            }
        }

        long computeMs = (System.nanoTime() - t0) / 1_000_000L;
        return new ScatterGatherResult(totalFrames, bins, consolidated, computeMs, slices.size());
    }

    private WorkerComputeResponse dispatchSliceToWorker(
            String audioId,
            PartitionSlice slice,
            int windowSize,
            int hopSize,
            String workerUrl
    ) {
        WorkerComputeRequest payload = new WorkerComputeRequest(
                audioId,
                slice.startFrame(),
                slice.frameCount(),
                windowSize,
                hopSize,
                slice.subSamples()
        );

        try {
            String json = objectMapper.writeValueAsString(payload);
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(workerUrl + "/internal/fft/compute"))
                    .header("Content-Type", "application/json")
                    .timeout(Duration.ofMinutes(2))
                    .POST(HttpRequest.BodyPublishers.ofString(json))
                    .build();

            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() == 200) {
                WorkerComputeResponse workerResp =
                        objectMapper.readValue(response.body(), WorkerComputeResponse.class);
                log.info("Scatter-Gather: slice [startFrame={}, frames={}] completado por {} en {} ms",
                        slice.startFrame(), workerResp.frames(), workerResp.workerInstance(), workerResp.computeMs());
                return workerResp;
            }
            log.warn("Worker {} respondió HTTP {}. Ejecutando fallback local para startFrame={}.",
                    workerUrl, response.statusCode(), slice.startFrame());
        } catch (Exception e) {
            log.warn("No se pudo contactar al worker {} ({}). Ejecutando fallback local para startFrame={}.",
                    workerUrl, e.getMessage(), slice.startFrame());
        }

        return computeLocalFallback(audioId, slice, windowSize, hopSize);
    }

    private WorkerComputeResponse computeLocalFallback(
            String audioId,
            PartitionSlice slice,
            int windowSize,
            int hopSize
    ) {
        try {
            long t0 = System.nanoTime();
            int threads = Math.max(1, Runtime.getRuntime().availableProcessors());
            SharedResultMatrix matrix = FFTPipelineManager.compute(
                    slice.subSamples(), windowSize, hopSize, threads);
            long ms = (System.nanoTime() - t0) / 1_000_000L;
            return new WorkerComputeResponse(
                    audioId,
                    slice.startFrame(),
                    matrix.getNumChunks(),
                    matrix.getNumBins(),
                    matrix.copyAsFrameMajor(),
                    ms,
                    "ingestion-local-fallback"
            );
        } catch (Exception ex) {
            throw new IllegalStateException("Fallo en cómputo FFT para slice " + slice.startFrame(), ex);
        }
    }
}
