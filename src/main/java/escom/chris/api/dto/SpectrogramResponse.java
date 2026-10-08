package escom.chris.api.dto;

public record SpectrogramResponse(
        int frames,
        int bins,
        double[][] magnitudes,
        long computeMs
) {
}
