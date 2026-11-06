package escom.chris;

/**
 * Punto de entrada de la aplicación. Se encarga de validar los argumentos
 * de la línea de comandos e iniciar la ejecución del pipeline FFT.
 */
public final class Main {

    public static void main(String[] args) throws Exception {
        if (args.length < 1) {
            System.out.println("Uso: java Main <archivo.wav> [numWorkers] [windowSize] [hopSize] [salida.csv]");
            return;
        }

        String wavPath = args[0];
        int numWorkers = args.length > 1 ? Integer.parseInt(args[1]) : Runtime.getRuntime().availableProcessors();
        int windowSize = args.length > 2 ? Integer.parseInt(args[2]) : 1024;
        int hopSize = args.length > 3 ? Integer.parseInt(args[3]) : windowSize / 2;
        String outputCsv = args.length > 4 ? args[4] : "spectrogram.csv";

        FFTPipelineManager pipeline = new FFTPipelineManager(wavPath, numWorkers, windowSize, hopSize, outputCsv);
        pipeline.run();
    }
}

