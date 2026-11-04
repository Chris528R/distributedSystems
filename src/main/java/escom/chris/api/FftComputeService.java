package escom.chris.api;

import escom.chris.FFTPipelineManager;
import escom.chris.api.dto.SpectrogramRequest;
import escom.chris.api.dto.SpectrogramResponse;
import escom.chris.audio.WavReader;
import escom.chris.concurrency.SharedResultMatrix;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.io.InputStream;

@Service
public class FftComputeService {

    /** Variante JSON: el cliente ya decodificó el WAV y envía las muestras. */
    public SpectrogramResponse compute(SpectrogramRequest request) throws Exception {
        if (request == null || request.samples() == null || request.samples().length == 0) {
            throw new IllegalArgumentException("El arreglo de muestras no puede estar vacío");
        }
        return compute(request.samples(), null, request.resolvedWindowSize(), request.resolvedHopSize());
    }

    /** Variante multipart: el servidor recibe el WAV crudo y lo decodifica con {@link WavReader}. */
    public SpectrogramResponse computeFromWav(InputStream wav, String fileName,
                                              int windowSize, int hopSize) throws Exception {
        WavReader.WavData data;
        try {
            data = new WavReader().read(wav, fileName);
        } catch (IOException | UnsupportedOperationException e) {
            throw new IllegalArgumentException("No se pudo leer el WAV: " + e.getMessage(), e);
        }
        return compute(data.samples, data.sampleRate, windowSize, hopSize);
    }

    private SpectrogramResponse compute(double[] samples, Integer sampleRate,
                                        int windowSize, int hopSize) throws Exception {
        if (windowSize < 2 || Integer.bitCount(windowSize) != 1) {
            throw new IllegalArgumentException("windowSize debe ser potencia de 2 (p.ej. 512, 1024, 2048)");
        }
        if (hopSize <= 0 || hopSize > windowSize) {
            throw new IllegalArgumentException("hopSize debe estar en el rango [1, windowSize]");
        }
        if (samples.length < windowSize) {
            throw new IllegalArgumentException(
                    "Se requieren al menos " + windowSize + " muestras para windowSize=" + windowSize);
        }

        int numWorkers = Math.max(1, Runtime.getRuntime().availableProcessors());
        long t0 = System.nanoTime();
        SharedResultMatrix matrix = FFTPipelineManager.compute(samples, windowSize, hopSize, numWorkers);
        long computeMs = (System.nanoTime() - t0) / 1_000_000L;

        return new SpectrogramResponse(
                matrix.getNumChunks(),
                matrix.getNumBins(),
                matrix.copyAsFrameMajor(),
                computeMs,
                sampleRate,
                windowSize,
                hopSize);
    }
}
