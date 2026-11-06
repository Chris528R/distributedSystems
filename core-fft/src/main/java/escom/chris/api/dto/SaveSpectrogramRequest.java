package escom.chris.api.dto;

/**
 * Petición enviada a {@code service-storage} para persistir un espectrograma
 * calculado junto con sus metadatos.
 */
public record SaveSpectrogramRequest(
        String audioId,
        String fileName,
        int frames,
        int bins,
        double[][] magnitudes,
        long computeMs,
        Integer sampleRate,
        int windowSize,
        int hopSize
) {
}

