package escom.chris.ingestion.service;

import escom.chris.api.dto.SaveSpectrogramRequest;
import escom.chris.api.dto.SpectrogramRequest;
import escom.chris.api.dto.SpectrogramResponse;
import escom.chris.audio.WavReader;
import escom.chris.ingestion.service.WorkerScatterGatherClient.ScatterGatherResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.util.UUID;

/**
 * Orquestador del Microservicio de Ingesta:
 * 1. Valida la señal o decodifica el archivo .WAV con {@link WavReader}.
 * 2. Almacena el archivo .WAV bruto en MinIO / S3 y emite {@code audioId}.
 * 3. Reparte las ventanas de audio (Scatter-Gather) entre los {@code service-worker-fft}.
 * 4. Persiste el resultado matricial consolidado en {@code service-storage} (PostgreSQL).
 */
@Service
public class IngestionOrchestratorService {

    private static final Logger log = LoggerFactory.getLogger(IngestionOrchestratorService.class);

    private final MinioAudioStorageService minioStorage;
    private final WorkerScatterGatherClient scatterGatherClient;
    private final StorageServiceClient storageServiceClient;

    public IngestionOrchestratorService(
            MinioAudioStorageService minioStorage,
            WorkerScatterGatherClient scatterGatherClient,
            StorageServiceClient storageServiceClient
    ) {
        this.minioStorage = minioStorage;
        this.scatterGatherClient = scatterGatherClient;
        this.storageServiceClient = storageServiceClient;
    }

    /** Variante JSON: el cliente envía el arreglo de muestras ya decodificado. */
    public SpectrogramResponse computeFromSamples(SpectrogramRequest request) {
        if (request == null || request.samples() == null || request.samples().length == 0) {
            throw new IllegalArgumentException("El arreglo de muestras no puede estar vacío");
        }
        String audioId = UUID.randomUUID().toString();
        return orchestrate(
                audioId,
                "samples-json.wav",
                request.samples(),
                null,
                request.resolvedWindowSize(),
                request.resolvedHopSize(),
                0.0
        );
    }

    /** Variante Multipart: la PWA envía el archivo .WAV crudo. */
    public SpectrogramResponse computeFromWavBytes(
            byte[] wavBytes,
            String fileName,
            int windowSize,
            int hopSize
    ) {
        long tDecode0 = System.nanoTime();
        WavReader.WavData wavData;
        try (ByteArrayInputStream in = new ByteArrayInputStream(wavBytes)) {
            wavData = new WavReader().read(in, fileName);
        } catch (IOException | UnsupportedOperationException e) {
            throw new IllegalArgumentException("No se pudo leer el WAV: " + e.getMessage(), e);
        }
        double decodeMs = (System.nanoTime() - tDecode0) / 1_000_000.0;

        String audioId = UUID.randomUUID().toString();
        long tMinio0 = System.nanoTime();
        minioStorage.storeWav(audioId, fileName, wavBytes);
        double minioMs = (System.nanoTime() - tMinio0) / 1_000_000.0;

        log.info("[MÉTRICAS INGESTA] audioId={} | archivo={} ({} bytes) | T_Decode={} ms | T_MinIO_S3={} ms",
                audioId, fileName, wavBytes.length, String.format("%.2f", decodeMs), String.format("%.2f", minioMs));

        return orchestrate(
                audioId,
                fileName != null ? fileName : "audio.wav",
                wavData.samples,
                wavData.sampleRate,
                windowSize,
                hopSize,
                minioMs
        );
    }

    private SpectrogramResponse orchestrate(
            String audioId,
            String fileName,
            double[] samples,
            Integer sampleRate,
            int windowSize,
            int hopSize,
            double minioMs
    ) {
        validateParameters(samples, windowSize, hopSize);

        long tSg0 = System.nanoTime();
        ScatterGatherResult result = scatterGatherClient.computeDistributed(
                audioId, samples, windowSize, hopSize);
        double sgMs = (System.nanoTime() - tSg0) / 1_000_000.0;

        long tSto0 = System.nanoTime();
        String spectrogramId = storageServiceClient.saveSpectrogram(new SaveSpectrogramRequest(
                audioId,
                fileName,
                result.totalFrames(),
                result.bins(),
                result.magnitudes(),
                result.computeMs(),
                sampleRate,
                windowSize,
                hopSize
        ));
        double stoMs = (System.nanoTime() - tSto0) / 1_000_000.0;

        log.info("[MÉTRICAS PIPELINE] audioId={} | workersUsados={} | T_MinIO_S3={} ms | T_ScatterGather={} ms | T_GZIP_PostgreSQL={} ms | T_OrquestacionTotal={} ms",
                audioId, result.activeWorkersUsed(),
                String.format("%.2f", minioMs),
                String.format("%.2f", sgMs),
                String.format("%.2f", stoMs),
                String.format("%.2f", minioMs + sgMs + stoMs));

        return new SpectrogramResponse(
                result.totalFrames(),
                result.bins(),
                result.magnitudes(),
                result.computeMs(),
                sampleRate,
                windowSize,
                hopSize,
                audioId,
                spectrogramId
        );
    }

    private void validateParameters(double[] samples, int windowSize, int hopSize) {
        if (windowSize < 2 || Integer.bitCount(windowSize) != 1) {
            throw new IllegalArgumentException("windowSize debe ser potencia de 2 (p.ej. 512, 1024, 2048)");
        }
        if (hopSize <= 0 || hopSize > windowSize) {
            throw new IllegalArgumentException("hopSize debe estar en el rango [1, windowSize]");
        }
        if (samples.length < windowSize) {
            throw new IllegalArgumentException(
                    "Se requieren al menos " + windowSize + " muestras para windowSize=" + windowSize);
        }
    }
}
