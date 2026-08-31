package escom.chris.math;

/**
 * Representación inmutable de un número complejo, con las operaciones
 * mínimas necesarias para el algoritmo Cooley-Tukey (suma, resta, producto)
 * y para el cálculo de magnitud/energía usada en el espectrograma.
 */
public final class Complex {

    public static final Complex ZERO = new Complex(0.0, 0.0);

    private final double re;
    private final double im;

    public Complex(double re, double im) {
        this.re = re;
        this.im = im;
    }

    public double re() { return re; }
    public double im() { return im; }

    public Complex add(Complex o) {
        return new Complex(this.re + o.re, this.im + o.im);
    }

    public Complex sub(Complex o) {
        return new Complex(this.re - o.re, this.im - o.im);
    }

    public Complex mul(Complex o) {
        return new Complex(
                this.re * o.re - this.im * o.im,
                this.re * o.im + this.im * o.re
        );
    }

    /** Genera e^{-j*2*pi*k/n}, el "twiddle factor" usado en la FFT. */
    public static Complex twiddle(int k, int n) {
        double angle = -2.0 * Math.PI * k / n;
        return new Complex(Math.cos(angle), Math.sin(angle));
    }

    public double magnitude() {
        return Math.sqrt(re * re + im * im);
    }

    /** Magnitud en decibelios, con piso mínimo para evitar log(0). */
    public double magnitudeDb() {
        return 20.0 * Math.log10(magnitude() + 1e-12);
    }

    @Override
    public String toString() {
        return String.format("(%.5f%+.5fi)", re, im);
    }
}
