package escom.chris.ingestion.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import escom.chris.api.dto.SaveSpectrogramRequest;
import escom.chris.api.dto.SpectrogramHistorySummary;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

/**
 * Cliente HTTP hacia {@code service-storage} (:8085) para persistir el
 * espectrograma consolidado en PostgreSQL.
 */
@Service
public class StorageServiceClient {

    private static final Logger log = LoggerFactory.getLogger(StorageServiceClient.class);

    private final String storageBaseUrl;
    private final ObjectMapper objectMapper;
    private final HttpClient httpClient;

    public StorageServiceClient(
            @Value("${app.storage.url:http://localhost:8085}") String storageBaseUrl,
            ObjectMapper objectMapper
    ) {
        this.storageBaseUrl = storageBaseUrl.replaceAll("/+$", "");
        this.objectMapper = objectMapper;
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(5))
                .build();
    }

    public String saveSpectrogram(SaveSpectrogramRequest request) {
        try {
            String json = objectMapper.writeValueAsString(request);
            HttpRequest httpReq = HttpRequest.newBuilder()
                    .uri(URI.create(storageBaseUrl + "/api/history"))
                    .header("Content-Type", "application/json")
                    .timeout(Duration.ofSeconds(30))
                    .POST(HttpRequest.BodyPublishers.ofString(json))
                    .build();

            HttpResponse<String> response = httpClient.send(httpReq, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() == 200 || response.statusCode() == 201) {
                SpectrogramHistorySummary summary =
                        objectMapper.readValue(response.body(), SpectrogramHistorySummary.class);
                log.info("Espectrograma persistido en service-storage con id={}", summary.id());
                return summary.id();
            }
            log.warn("service-storage respondió HTTP {} al guardar historial.", response.statusCode());
        } catch (Exception e) {
            log.warn("No se pudo persistir en service-storage ({}): {}. Retornando resultado directo a la PWA.",
                    storageBaseUrl, e.getMessage());
        }
        return null;
    }
}

