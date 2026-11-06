package escom.chris.api.dto;

/**
 * Petición enviada por {@code service-ingestion} hacia una instancia de
 * {@code service-worker-fft} durante la fase Scatter.
 */
public record WorkerComputeRequest(
        String audioId,
        int startFrame,
        int frameCount,
        int windowSize,
        int hopSize,
        double[] samples
) {
}

