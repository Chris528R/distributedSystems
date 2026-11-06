package escom.chris.ingestion.service;

import io.minio.BucketExistsArgs;
import io.minio.MakeBucketArgs;
import io.minio.MinioClient;
import io.minio.PutObjectArgs;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.ByteArrayInputStream;

/**
 * Cliente de almacenamiento de objetos (MinIO en local / AWS S3 en la nube)
 * para persistir los archivos .WAV brutos recibidos en la ingesta.
 */
@Service
public class MinioAudioStorageService {

    private static final Logger log = LoggerFactory.getLogger(MinioAudioStorageService.class);

    private final boolean enabled;
    private final String endpoint;
    private final String bucket;
    private final MinioClient minioClient;
    private volatile boolean bucketVerified = false;

    public MinioAudioStorageService(
            @Value("${app.minio.enabled:true}") boolean enabled,
            @Value("${app.minio.endpoint:http://localhost:9000}") String endpoint,
            @Value("${app.minio.access-key:minioadmin}") String accessKey,
            @Value("${app.minio.secret-key:minioadmin}") String secretKey,
            @Value("${app.minio.bucket:distrifourier-wavs}") String bucket
    ) {
        this.enabled = enabled;
        this.endpoint = endpoint;
        this.bucket = bucket;
        this.minioClient = enabled
                ? MinioClient.builder()
                        .endpoint(endpoint)
                        .credentials(accessKey, secretKey)
                        .build()
                : null;
    }

    /**
     * Almacena el archivo .WAV bruto en el bucket S3/MinIO bajo la clave {@code audioId/fileName}.
     * Si MinIO no está disponible en desarrollo local sin Docker, registra una advertencia
     * y continúa para no interrumpir el pipeline.
     */
    public void storeWav(String audioId, String originalFilename, byte[] wavBytes) {
        if (!enabled || minioClient == null || wavBytes == null || wavBytes.length == 0) {
            return;
        }
        String safeName = (originalFilename == null || originalFilename.isBlank())
                ? "audio.wav"
                : originalFilename.replaceAll("[^a-zA-Z0-9._-]", "_");
        String objectKey = audioId + "/" + safeName;

        try {
            ensureBucketExists();
            try (ByteArrayInputStream bais = new ByteArrayInputStream(wavBytes)) {
                minioClient.putObject(
                        PutObjectArgs.builder()
                                .bucket(bucket)
                                .object(objectKey)
                                .stream(bais, wavBytes.length, -1)
                                .contentType("audio/wav")
                                .build()
                );
            }
            log.info("WAV almacenado en MinIO/S3: s3://{}/{} ({} bytes)", bucket, objectKey, wavBytes.length);
        } catch (Exception e) {
            log.warn("No se pudo almacenar el WAV en MinIO ({}): {}. Continuando procesamiento.",
                    endpoint, e.getMessage());
        }
    }

    private void ensureBucketExists() throws Exception {
        if (bucketVerified) {
            return;
        }
        synchronized (this) {
            if (!bucketVerified) {
                boolean exists = minioClient.bucketExists(BucketExistsArgs.builder().bucket(bucket).build());
                if (!exists) {
                    minioClient.makeBucket(MakeBucketArgs.builder().bucket(bucket).build());
                    log.info("Bucket S3/MinIO creado: {}", bucket);
                }
                bucketVerified = true;
            }
        }
    }
}

