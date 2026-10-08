package escom.chris.api;

import escom.chris.FFTPipelineManager;
import escom.chris.api.dto.SpectrogramRequest;
import escom.chris.api.dto.SpectrogramResponse;
import escom.chris.concurrency.SharedResultMatrix;
import org.springframework.stereotype.Service;

@Service
public class FftComputeService {

    public SpectrogramResponse compute(SpectrogramRequest request) throws Exception {
        if (request == null || request.samples() == null || request.samples().length == 0) {
            throw new IllegalArgumentException("El arreglo de muestras no puede estar vacío");
        }

        int windowSize = request.resolvedWindowSize();
        int hopSize = request.resolvedHopSize();
        if (request.samples().length < windowSize) {
            throw new IllegalArgumentException(
                    "Se requieren al menos " + windowSize + " muestras para windowSize=" + windowSize);
        }

        int numWorkers = Math.max(1, Runtime.getRuntime().availableProcessors());
        long t0 = System.nanoTime();
        SharedResultMatrix matrix = FFTPipelineManager.compute(
                request.samples(), windowSize, hopSize, numWorkers);
        long computeMs = (System.nanoTime() - t0) / 1_000_000L;

        return new SpectrogramResponse(
                matrix.getNumChunks(),
                matrix.getNumBins(),
                matrix.copyAsFrameMajor(),
                computeMs);
    }
}
