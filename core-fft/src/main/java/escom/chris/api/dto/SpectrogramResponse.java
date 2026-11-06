package escom.chris.api.dto;

/**
 * Respuesta consolidada del endpoint de espectrograma, retrocompatible con la PWA
 * y enriquecida con los identificadores generados por los microservicios de
 * Ingesta (MinIO S3) y Almacenamiento (PostgreSQL).
 */
public record SpectrogramResponse(
        int frames,
        int bins,
        double[][] magnitudes,
        long computeMs,
        Integer sampleRate,
        int windowSize,
        int hopSize,
        String audioId,
        String spectrogramId
) {
    public SpectrogramResponse(
            int frames,
            int bins,
            double[][] magnitudes,
            long computeMs,
            Integer sampleRate,
            int windowSize,
            int hopSize
    ) {
        this(frames, bins, magnitudes, computeMs, sampleRate, windowSize, hopSize, null, null);
    }
}

