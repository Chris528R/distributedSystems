package escom.chris.api;

import escom.chris.api.dto.SpectrogramRequest;
import escom.chris.api.dto.SpectrogramResponse;
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

import java.io.InputStream;

/**
 * POST /api/fft/spectrogram acepta dos formatos (se elige por Content-Type):
 * <ul>
 *   <li>{@code application/json}: muestras ya decodificadas (RestFftClient).</li>
 *   <li>{@code multipart/form-data}: el archivo .wav crudo (PWA del directorio frontend/).</li>
 * </ul>
 */
@RestController
@RequestMapping("/api/fft")
public class FftController {

    private final FftComputeService fftComputeService;

    public FftController(FftComputeService fftComputeService) {
        this.fftComputeService = fftComputeService;
    }

    @PostMapping(value = "/spectrogram", consumes = MediaType.APPLICATION_JSON_VALUE)
    public SpectrogramResponse spectrogram(@RequestBody SpectrogramRequest request) {
        try {
            return fftComputeService.compute(request);
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
            @RequestParam(required = false) Integer hopSize) {
        if (file.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "El archivo está vacío");
        }
        int hop = hopSize == null || hopSize <= 0 ? windowSize / 2 : hopSize;
        try (InputStream in = file.getInputStream()) {
            return fftComputeService.computeFromWav(in, file.getOriginalFilename(), windowSize, hop);
        } catch (IllegalArgumentException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, e.getMessage());
        } catch (Exception e) {
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, e.getMessage());
        }
    }
}
