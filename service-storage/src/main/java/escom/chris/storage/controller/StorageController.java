package escom.chris.storage.controller;

import escom.chris.api.dto.SaveSpectrogramRequest;
import escom.chris.api.dto.SpectrogramHistorySummary;
import escom.chris.api.dto.SpectrogramResponse;
import escom.chris.storage.entity.SpectrogramEntity;
import escom.chris.storage.repository.SpectrogramRepository;
import escom.chris.storage.service.MatrixCompressionCodec;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.io.IOException;
import java.util.List;
import java.util.UUID;

/**
 * Microservicio 4: Persistencia y consulta de historial de espectrogramas.
 */
@RestController
@RequestMapping("/api/history")
public class StorageController {

    private static final Logger log = LoggerFactory.getLogger(StorageController.class);

    private final SpectrogramRepository repository;

    public StorageController(SpectrogramRepository repository) {
        this.repository = repository;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public SpectrogramHistorySummary save(@RequestBody SaveSpectrogramRequest request) {
        if (request == null || request.magnitudes() == null || request.frames() <= 0 || request.bins() <= 0) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Datos de espectrograma inválidos");
        }
        try {
            String id = UUID.randomUUID().toString();
            long now = System.currentTimeMillis();
            byte[] compressed = MatrixCompressionCodec.compress(
                    request.magnitudes(), request.frames(), request.bins());

            SpectrogramEntity entity = new SpectrogramEntity(
                    id,
                    request.audioId(),
                    request.fileName() != null ? request.fileName() : "audio.wav",
                    request.frames(),
                    request.bins(),
                    request.sampleRate(),
                    request.windowSize(),
                    request.hopSize(),
                    request.computeMs(),
                    now,
                    compressed
            );
            repository.save(entity);
            log.info("Espectrograma guardado: id={} | audioId={} | {}x{} ({} bytes comprimidos)",
                    id, request.audioId(), request.frames(), request.bins(), compressed.length);

            return toSummary(entity);
        } catch (IOException e) {
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR,
                    "Error al comprimir matriz de espectrograma: " + e.getMessage(), e);
        }
    }

    @GetMapping
    public List<SpectrogramHistorySummary> listRecent() {
        return repository.findTop25ByOrderByCreatedAtDesc()
                .stream()
                .map(this::toSummary)
                .toList();
    }

    @GetMapping("/{id}")
    public SpectrogramResponse getById(@PathVariable String id) {
        SpectrogramEntity entity = repository.findById(id)
                .orElseThrow(() -> new ResponseStatusException(
                        HttpStatus.NOT_FOUND, "Espectrograma no encontrado: " + id));
        try {
            double[][] magnitudes = MatrixCompressionCodec.decompress(
                    entity.getCompressedMatrix(), entity.getFrames(), entity.getBins());
            return new SpectrogramResponse(
                    entity.getFrames(),
                    entity.getBins(),
                    magnitudes,
                    entity.getComputeMs(),
                    entity.getSampleRate(),
                    entity.getWindowSize(),
                    entity.getHopSize(),
                    entity.getAudioId(),
                    entity.getId()
            );
        } catch (IOException e) {
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR,
                    "Error al descomprimir espectrograma: " + e.getMessage(), e);
        }
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void deleteById(@PathVariable String id) {
        if (!repository.existsById(id)) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Espectrograma no encontrado: " + id);
        }
        repository.deleteById(id);
    }

    private SpectrogramHistorySummary toSummary(SpectrogramEntity e) {
        return new SpectrogramHistorySummary(
                e.getId(),
                e.getAudioId(),
                e.getFileName(),
                e.getFrames(),
                e.getBins(),
                e.getSampleRate(),
                e.getWindowSize(),
                e.getHopSize(),
                e.getComputeMs(),
                e.getCreatedAt()
        );
    }
}

