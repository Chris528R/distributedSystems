package escom.chris.client;

import com.fasterxml.jackson.databind.ObjectMapper;
import escom.chris.api.dto.SpectrogramRequest;
import escom.chris.api.dto.SpectrogramResponse;
import escom.chris.audio.WavReader;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

/**
 * Cliente HTTP que lee un WAV local y solicita el espectrograma al servidor REST.
 */
public final class RestFftClient {

    public static void main(String[] args) throws Exception {
        String baseUrl = args.length > 0 ? args[0] : "http://localhost:8080";
        String wavPath = args.length > 1 ? args[1] : "src/main/resources/audio.wav";
        int windowSize = args.length > 2 ? Integer.parseInt(args[2]) : 1024;
        int hopSize = args.length > 3 ? Integer.parseInt(args[3]) : windowSize / 2;

        System.out.println("CLIENTE FFT REST");
        System.out.printf("Servidor: %s%n", baseUrl);
        System.out.printf("Audio: %s | Ventana: %d | Hop: %d%n%n", wavPath, windowSize, hopSize);

        System.out.println("[1/3] Leyendo WAV local...");
        double[] samples = WavReader.readMonoPcm(wavPath);
        System.out.printf("      %d muestras leídas%n", samples.length);

        ObjectMapper mapper = new ObjectMapper();
        SpectrogramRequest body = new SpectrogramRequest(samples, windowSize, hopSize);
        String json = mapper.writeValueAsString(body);

        HttpClient http = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .build();

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(baseUrl + "/api/fft/spectrogram"))
                .header("Content-Type", "application/json")
                .timeout(Duration.ofMinutes(5))
                .POST(HttpRequest.BodyPublishers.ofString(json))
                .build();

        System.out.println("[2/3] POST /api/fft/spectrogram ...");
        long t0 = System.currentTimeMillis();
        HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
        long elapsed = System.currentTimeMillis() - t0;

        if (response.statusCode() != 200) {
            System.err.printf("[3/3] Error HTTP %d: %s%n", response.statusCode(), response.body());
            System.exit(1);
        }

        SpectrogramResponse result = mapper.readValue(response.body(), SpectrogramResponse.class);
        System.out.printf("[3/3] Espectrograma recibido: %d frames x %d bins (cómputo servidor: %d ms, round-trip: %d ms)%n",
                result.frames(), result.bins(), result.computeMs(), elapsed);
    }
}
