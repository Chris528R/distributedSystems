package escom.chris.api.dto;

/**
 * Resumen ligero de un análisis almacenado en {@code service-storage} (sin la matriz pesada).
 */
public record SpectrogramHistorySummary(
        String id,
        String audioId,
        String fileName,
        int frames,
        int bins,
        Integer sampleRate,
        int windowSize,
        int hopSize,
        long computeMs,
        long createdAt
) {
}

