package escom.chris.api.dto;

/**
 * Respuesta del endpoint de espectrograma.
 *
 * @param sampleRate frecuencia de muestreo del audio (Hz). Solo se conoce cuando
 *                   el servidor parsea el WAV (petición multipart); en la variante
 *                   JSON llega {@code null} porque el cliente solo envía muestras.
 */
public record SpectrogramResponse(
        int frames,
        int bins,
        double[][] magnitudes,
        long computeMs,
        Integer sampleRate,
        int windowSize,
        int hopSize
) {
}
