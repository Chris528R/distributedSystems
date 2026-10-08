package escom.chris.api;

import escom.chris.api.dto.SpectrogramRequest;
import escom.chris.api.dto.SpectrogramResponse;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/api/fft")
public class FftController {

    private final FftComputeService fftComputeService;

    public FftController(FftComputeService fftComputeService) {
        this.fftComputeService = fftComputeService;
    }

    @PostMapping("/spectrogram")
    public SpectrogramResponse spectrogram(@RequestBody SpectrogramRequest request) {
        try {
            return fftComputeService.compute(request);
        } catch (IllegalArgumentException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, e.getMessage());
        } catch (Exception e) {
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, e.getMessage());
        }
    }
}
