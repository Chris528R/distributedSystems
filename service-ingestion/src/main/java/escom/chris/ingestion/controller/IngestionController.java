package escom.chris.ingestion.controller;

import escom.chris.api.dto.SpectrogramRequest;
import escom.chris.api.dto.SpectrogramResponse;
import escom.chris.ingestion.service.IngestionOrchestratorService;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;

/**
 * Endpoints de ingesta de audio expuestos en /api/fft/spectrogram
 * (enrutados desde el API Gateway :8080 hacia :8081).
 */
@RestController
@RequestMapping("/api/fft")
public class IngestionController {

    private final IngestionOrchestratorService orchestratorService;

    public IngestionController(IngestionOrchestratorService orchestratorService) {
        this.orchestratorService = orchestratorService;
    }

    @PostMapping(value = "/spectrogram", consumes = MediaType.APPLICATION_JSON_VALUE)
    public SpectrogramResponse spectrogram(@RequestBody SpectrogramRequest request) {
        try {
            return orchestratorService.computeFromSamples(request);
        } catch (IllegalArgumentException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, e.getMessage());
        } catch (Exception e) {
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, e.getMessage());
        }
    }

    @PostMapping(value = "/spectrogram", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public SpectrogramResponse spectrogramFromWav(
            @RequestPart("file") MultipartFile file,
            @RequestParam(defaultValue = "1024") int windowSize,
            @RequestParam(required = false) Integer hopSize
    ) {
        if (file.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "El archivo está vacío");
        }
        int hop = hopSize == null || hopSize <= 0 ? windowSize / 2 : hopSize;
        try {
            byte[] wavBytes = file.getBytes();
            return orchestratorService.computeFromWavBytes(
                    wavBytes, file.getOriginalFilename(), windowSize, hop);
        } catch (IllegalArgumentException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, e.getMessage());
        } catch (Exception e) {
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, e.getMessage());
        }
    }
}

