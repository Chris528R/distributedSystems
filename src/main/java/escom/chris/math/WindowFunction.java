package escom.chris.math;

/**
 * Funciones de ventaneo aplicadas antes de la FFT para reducir el
 * "spectral leakage" causado por el truncamiento de la señal en cada chunk.
 */
public final class WindowFunction {

    public enum Type { HANN, HAMMING, NONE }

    private WindowFunction() { }

    public static double[] apply(double[] samples, Type type) {
        switch (type) {
            case HANN:
                return hann(samples);
            case HAMMING:
                return hamming(samples);
            default:
                return samples.clone();
        }
    }

    public static double[] hann(double[] samples) {
        int n = samples.length;
        double[] out = new double[n];
        for (int i = 0; i < n; i++) {
            double w = 0.5 * (1 - Math.cos(2 * Math.PI * i / (n - 1)));
            out[i] = samples[i] * w;
        }
        return out;
    }

    public static double[] hamming(double[] samples) {
        int n = samples.length;
        double[] out = new double[n];
        for (int i = 0; i < n; i++) {
            double w = 0.54 - 0.46 * Math.cos(2 * Math.PI * i / (n - 1));
            out[i] = samples[i] * w;
        }
        return out;
    }
}
