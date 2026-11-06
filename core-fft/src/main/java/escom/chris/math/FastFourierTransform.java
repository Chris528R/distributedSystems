package escom.chris.math;

/**
 * Implementación del algoritmo de Cooley-Tukey (divide y vencerás), O(N log N).
 * Es puramente funcional / sin estado: cada hilo FFTWorker puede invocar
 * transform(...) de forma concurrente sin ningún tipo de sincronización,
 * ya que no hay memoria compartida dentro de esta clase.
 */
public final class FastFourierTransform {

    private FastFourierTransform() { }

    /**
     * Calcula la FFT de una señal real (samples de audio), haciendo
     * zero-padding hasta la siguiente potencia de 2 si es necesario.
     */
    public static Complex[] transform(double[] realSamples) {
        int n = nextPowerOfTwo(realSamples.length);
        Complex[] x = new Complex[n];
        for (int i = 0; i < n; i++) {
            double sample = (i < realSamples.length) ? realSamples[i] : 0.0;
            x[i] = new Complex(sample, 0.0);
        }
        return fft(x);
    }

    /** Cooley-Tukey recursivo sobre un arreglo complejo cuya longitud es potencia de 2. */
    public static Complex[] fft(Complex[] x) {
        int n = x.length;
        if (n == 1) {
            return new Complex[]{x[0]};
        }
        if ((n & (n - 1)) != 0) {
            throw new IllegalArgumentException("La longitud debe ser potencia de 2, recibido: " + n);
        }

        Complex[] even = new Complex[n / 2];
        Complex[] odd = new Complex[n / 2];
        for (int i = 0; i < n / 2; i++) {
            even[i] = x[2 * i];
            odd[i] = x[2 * i + 1];
        }

        Complex[] evenFft = fft(even);
        Complex[] oddFft = fft(odd);

        Complex[] result = new Complex[n];
        for (int k = 0; k < n / 2; k++) {
            Complex t = Complex.twiddle(k, n).mul(oddFft[k]);
            result[k] = evenFft[k].add(t);
            result[k + n / 2] = evenFft[k].sub(t);
        }
        return result;
    }

    /**
     * Convierte el resultado complejo en el espectro de magnitud (dB),
     * devolviendo solo la mitad útil (Nyquist), ya que para señales reales
     * el espectro es simétrico.
     */
    public static double[] magnitudeSpectrumDb(Complex[] spectrum) {
        int half = spectrum.length / 2;
        double[] magnitudes = new double[half];
        for (int i = 0; i < half; i++) {
            magnitudes[i] = spectrum[i].magnitudeDb();
        }
        return magnitudes;
    }

    private static int nextPowerOfTwo(int n) {
        int power = 1;
        while (power < n) {
            power <<= 1;
        }
        return Math.max(power, 1);
    }
}
