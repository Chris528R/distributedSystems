package escom.chris.worker;

import escom.chris.FFTPipelineManager;
import escom.chris.api.dto.WorkerComputeRequest;
import escom.chris.api.dto.WorkerComputeResponse;
import escom.chris.concurrency.SharedResultMatrix;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.net.InetAddress;
import java.util.Map;

/**
 * Unidad de cómputo intensivo (CPU-bound) totalmente stateless.
 * Recibe un lote de muestras desde {@code service-ingestion}, aplica ventaneo Hann
 * y el algoritmo Cooley-Tukey en paralelo usando los núcleos locales del contenedor,
 * y devuelve la sub-matriz {@code [frame][bin]} en dB.
 */
@RestController
@RequestMapping("/internal/fft")
public class WorkerController {

    private static final Logger log = LoggerFactory.getLogger(WorkerController.class);

    private final int serverPort;

    public WorkerController(@Value("${server.port:8082}") int serverPort) {
        this.serverPort = serverPort;
    }

    @PostMapping("/compute")
    public WorkerComputeResponse computeSlice(@RequestBody WorkerComputeRequest request) {
        if (request == null || request.samples() == null || request.samples().length == 0) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "El lote de muestras está vacío");
        }
        int windowSize = request.windowSize();
        int hopSize = request.hopSize();
        if (windowSize < 2 || Integer.bitCount(windowSize) != 1) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "windowSize debe ser potencia de 2");
        }
        if (hopSize <= 0 || hopSize > windowSize) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "hopSize fuera de rango");
        }
        if (request.samples().length < windowSize) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Muestras insuficientes para windowSize=" + windowSize);
        }

        try {
            int numThreads = Math.max(1, Runtime.getRuntime().availableProcessors());
            long t0 = System.nanoTime();
            SharedResultMatrix matrix = FFTPipelineManager.compute(
                    request.samples(), windowSize, hopSize, numThreads);
            long computeMs = (System.nanoTime() - t0) / 1_000_000L;

            String instanceId = resolveInstanceId();
            log.info("[{}] Audio={} | startFrame={} | frames={} | muestras={} | {} ms ({} hilos)",
                    instanceId, request.audioId(), request.startFrame(),
                    matrix.getNumChunks(), request.samples().length, computeMs, numThreads);

            return new WorkerComputeResponse(
                    request.audioId(),
                    request.startFrame(),
                    matrix.getNumChunks(),
                    matrix.getNumBins(),
                    matrix.copyAsFrameMajor(),
                    computeMs,
                    instanceId
            );
        } catch (Exception e) {
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR,
                    "Error en cómputo FFT del worker: " + e.getMessage(), e);
        }
    }

    @GetMapping("/info")
    public Map<String, Object> info() {
        return Map.of(
                "instance", resolveInstanceId(),
                "availableProcessors", Runtime.getRuntime().availableProcessors(),
                "status", "UP"
        );
    }

    private String resolveInstanceId() {
        try {
            return InetAddress.getLocalHost().getHostName() + ":" + serverPort;
        } catch (Exception e) {
            return "worker:" + serverPort;
        }
    }
}

