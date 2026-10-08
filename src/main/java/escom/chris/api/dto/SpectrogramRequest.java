package escom.chris.api.dto;

public record SpectrogramRequest(
        double[] samples,
        Integer windowSize,
        Integer hopSize
) {
    public int resolvedWindowSize() {
        return windowSize == null || windowSize <= 0 ? 1024 : windowSize;
    }

    public int resolvedHopSize() {
        int window = resolvedWindowSize();
        return hopSize == null || hopSize <= 0 ? window / 2 : hopSize;
    }
}
