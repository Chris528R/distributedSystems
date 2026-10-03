package com.distribuidos.fft.rmi;

import escom.chris.audio.WavReader;

import java.rmi.registry.LocateRegistry;
import java.rmi.registry.Registry;

public class RMIClient {
    public static void main(String[] args) {
        String host = args.length > 0 ? args[0] : "127.0.0.1";
        int port = args.length > 1 ? Integer.parseInt(args[1]) : 1099;
        String wavPath = args.length > 2 ? args[2] : "src/main/resources/audio.wav";

        try {
            System.out.println("[RMI Client] Leyendo audio local...");
            double[] samples = WavReader.readMonoPcm(wavPath);

            System.out.println("[RMI Client] Buscando objeto remoto en " + host + ":" + port);
            Registry registry = LocateRegistry.getRegistry(host, port);
            IFFTService service = (IFFTService) registry.lookup("FFTService");

            System.out.println("[RMI Client] Invocando 'computeSpectrogram' de forma transparente...");
            long t0 = System.currentTimeMillis();

            // Llamada remota (RMI maneja serialización, conexión y transporte en red)
            double[][] spectrogram = service.computeSpectrogram(samples, 1024, 512);

            long elapsed = System.currentTimeMillis() - t0;
            System.out.println("[RMI Client] Espectrograma recibido: "
                    + spectrogram.length + " frames x " + spectrogram[0].length + " bins en " + elapsed + " ms.");

        } catch (Exception e) {
            System.err.println("[RMI Client] Error durante la invocación remota: " + e.getMessage());
            e.printStackTrace();
        }
    }
}
