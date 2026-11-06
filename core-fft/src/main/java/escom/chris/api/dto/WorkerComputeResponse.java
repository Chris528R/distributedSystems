package escom.chris.api.dto;

/**
 * Respuesta devuelta por una instancia de {@code service-worker-fft} con la
 * sub-matriz calculada para el rango de ventanas asignado.
 */
public record WorkerComputeResponse(
        String audioId,
        int startFrame,
        int frames,
        int bins,
        double[][] magnitudes,
        long computeMs,
        String workerInstance
) {
}

