package escom.chris.storage.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

@Entity
@Table(
        name = "spectrogram_history",
        indexes = {
                @Index(name = "idx_spectrogram_created_at", columnList = "createdAt"),
                @Index(name = "idx_spectrogram_audio_id", columnList = "audioId")
        }
)
public class SpectrogramEntity {

    @Id
    @Column(length = 64, nullable = false)
    private String id;

    @Column(length = 128)
    private String audioId;

    @Column(length = 255, nullable = false)
    private String fileName;

    @Column(nullable = false)
    private int frames;

    @Column(nullable = false)
    private int bins;

    private Integer sampleRate;

    @Column(nullable = false)
    private int windowSize;

    @Column(nullable = false)
    private int hopSize;

    @Column(nullable = false)
    private long computeMs;

    @Column(nullable = false)
    private long createdAt;

    /**
     * Matriz [frames][bins] serializada como float32 en orden frame-major y comprimida con GZIP.
     */
    @JdbcTypeCode(SqlTypes.BINARY)
    @Column(nullable = false)
    private byte[] compressedMatrix;

    public SpectrogramEntity() {
    }

    public SpectrogramEntity(
            String id,
            String audioId,
            String fileName,
            int frames,
            int bins,
            Integer sampleRate,
            int windowSize,
            int hopSize,
            long computeMs,
            long createdAt,
            byte[] compressedMatrix
    ) {
        this.id = id;
        this.audioId = audioId;
        this.fileName = fileName;
        this.frames = frames;
        this.bins = bins;
        this.sampleRate = sampleRate;
        this.windowSize = windowSize;
        this.hopSize = hopSize;
        this.computeMs = computeMs;
        this.createdAt = createdAt;
        this.compressedMatrix = compressedMatrix;
    }

    public String getId() { return id; }
    public String getAudioId() { return audioId; }
    public String getFileName() { return fileName; }
    public int getFrames() { return frames; }
    public int getBins() { return bins; }
    public Integer getSampleRate() { return sampleRate; }
    public int getWindowSize() { return windowSize; }
    public int getHopSize() { return hopSize; }
    public long getComputeMs() { return computeMs; }
    public long getCreatedAt() { return createdAt; }
    public byte[] getCompressedMatrix() { return compressedMatrix; }
}

