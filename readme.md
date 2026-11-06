# DistriFourier: Espectrogramas FFT sobre Arquitectura de Microservicios y PWA

Este proyecto implementa el cálculo distribuido y concurrente de la **Transformada Rápida de Fourier (FFT)** sobre señales de audio `.wav` desacoplado en **4 microservicios funcionales** con **Java 21 y Spring Boot 3.4.4**, consumidos por una **Progressive Web App (PWA)** instalable y con soporte offline.

---

## 1. Descomposición por Dominios (Domain-Driven Decomposition)

Para mantener una clara separación de responsabilidades y preparar el despliegue nativo en la nube (Kubernetes / HPA), el sistema se divide en 4 microservicios más infraestructura de almacenamiento (MinIO S3 y PostgreSQL):

```text
                                  [ PWA / Frontend ] (:3000 Nginx)
                                          │
                                          │ HTTP (Multipart / JSON)
                                          ▼
                               ┌──────────────────────┐
                               │   1. API GATEWAY     │ :8080
                               │ (Spring Cloud GW MVC)│
                               └──────────┬───────────┘
                                          │
                  ┌───────────────────────┼───────────────────────┐
                  │ REST (/api/fft/**)    │ REST (/internal/fft)  │ REST (/api/history/**)
                  ▼                       ▼                       ▼
      ┌──────────────────────┐ ┌──────────────────────┐ ┌──────────────────────┐
      │   2. AUDIO INGESTION │ │   3. FFT WORKER      │ │   4. SPECTRO-STORAGE │
      │      SERVICE         │─►      SERVICE         │ │      SERVICE         │
      │       :8081          │ │  :8082, :8083, ...   │ │       :8085          │
      └──────────┬───────────┘ └──────────────────────┘ └──────────┬───────────┘
                 │                                                 │
                 ▼                                                 ▼
       [ MinIO / S3 Local ]                                [ PostgreSQL 16 ]
      (Archivos .WAV brutos)                             (Metadatos y Matrices)
```

---

## 2. Responsabilidades de Cada Microservicio

### A. Microservicio 1: API Gateway (`api-gateway` — `:8080`)
- **Rol**: Punto único de entrada para la PWA y clientes externos.
- **Tecnología**: `spring-cloud-starter-gateway-mvc` sobre Tomcat imperativo (óptimo para streaming `multipart/form-data` hasta `50MB`).
- **Funciones**:
  - Enrutamiento inverso (`/api/fft/**` $\rightarrow$ `service-ingestion:8081`, `/api/history/**` $\rightarrow$ `service-storage:8085`).
  - Gestión centralizada de CORS para la PWA.
  - Endpoints de monitoreo (`/actuator/health`, `/actuator/info`).

### B. Microservicio 2: Audio Ingestion & Preprocessing Service (`service-ingestion` — `:8081`)
- **Rol**: Manejo de archivos, validación de señal y orquestador Scatter-Gather.
- **Funciones**:
  - Recibe el archivo `.wav` desde la PWA vía `multipart/form-data` (hasta `50MB`) o muestras JSON.
  - Valida la integridad de la cabecera RIFF/WAVE y decodifica muestras PCM (8/16/24-bit, mono/downmix estéreo) con `WavReader`.
  - Almacena el `.wav` original en **MinIO** (compatible con AWS S3) en el bucket `distrifourier-wavs` y emite un `audioId`.
  - **Particionado Scatter con margen de ventana (`ScatterPartitioner`)**: Divide el total de ventanas (`totalFrames`) entre las instancias activas de `service-worker-fft`. Para que cada worker procese `count` ventanas desde `startFrame` sin desfase ni truncamiento en los bordes de solapamiento:
    $$\text{startSample} = \text{startFrame} \times \text{hopSize}$$
    $$\text{endSample} = \text{startSample} + (\text{count} - 1) \times \text{hopSize} + \text{windowSize}$$
  - Despacha los sub-bloques en paralelo (`CompletableFuture` + hilos virtuales de Java 21) hacia los workers y ensambla la matriz consolidada `double[frames][bins]`.

### C. Microservicio 3: FFT Compute Worker Service (`service-worker-fft` — `:8082`, `:8083`, ...)
- **Rol**: Unidad de cómputo intensivo (*CPU-bound*) completamente *stateless*.
- **Funciones**:
  - Expone `POST /internal/fft/compute`.
  - Ejecuta el pipeline concurrente (`FFTPipelineManager`: Productor-Consumidor con `BlockingQueue`, función de ventaneo Hann y algoritmo Cooley-Tukey $O(N \log N)$) explotando todos los hilos de CPU del contenedor.
  - **Escalabilidad Horizontal**: En local se levantan múltiples réplicas en `docker-compose.yml` (`worker-fft-1:8082`, `worker-fft-2:8083`); en Kubernetes escala automáticamente con un *Horizontal Pod Autoscaler (HPA)*.

### D. Microservicio 4: Spectrogram Storage & History Service (`service-storage` — `:8085`)
- **Rol**: Persistencia de espectrogramas e historial de análisis.
- **Funciones**:
  - Almacena en **PostgreSQL** (`spectrogram_history`) los metadatos del análisis y la matriz `double[][]` codificada en binario `float32` + compresión **GZIP** (`MatrixCompressionCodec`), reduciendo en ~85% el espacio en disco.
  - Expone `POST /api/history`, `GET /api/history`, `GET /api/history/{id}` y `DELETE /api/history/{id}` para que la PWA recupere espectrogramas previos sin recomputar la FFT.

---

## 3. Estructura del Proyecto (Maven Multi-Módulo)

```text
DistriFourier/
├── pom.xml                               # POM padre (Spring Boot 3.4.4, Spring Cloud 2024.0.1, Java 21)
├── docker-compose.yml                    # Orquestador local completo (MinIO, Postgres, 4 servicios, PWA)
├── core-fft/                             # Librería común (Complex, FFT, WindowFunction, WavReader, ScatterPartitioner, DTOs)
│   └── src/main/java/escom/chris/...
├── api-gateway/                          # Microservicio 1: Spring Cloud Gateway MVC (:8080)
│   ├── Dockerfile
│   └── src/main/java/escom/chris/gateway/...
├── service-ingestion/                    # Microservicio 2: Ingesta WAV, S3 MinIO y Scatter-Gather (:8081)
│   ├── Dockerfile
│   └── src/main/java/escom/chris/ingestion/...
├── service-worker-fft/                   # Microservicio 3: Worker stateless de cálculo FFT (:8082, :8083)
│   ├── Dockerfile
│   └── src/main/java/escom/chris/worker/...
├── service-storage/                      # Microservicio 4: Persistencia PostgreSQL + GZIP (:8085)
│   ├── Dockerfile
│   └── src/main/java/escom/chris/storage/...
└── frontend-pwa/                         # Progressive Web App servida con Nginx (:3000 -> :80)
    ├── Dockerfile
    ├── nginx.conf
    ├── index.html, styles.css, manifest.json, sw.js
    └── js/ (app.js, api.js, spectrogram.js, colormap.js, storage.js)
```

---

## 4. Ejecución con Docker Compose (Recomendado)

Levanta toda la infraestructura (MinIO, PostgreSQL, 2 Workers FFT, Storage Service, Ingestion Service, API Gateway y la PWA en Nginx) con un solo comando:

```bash
docker compose up --build
```

### Puertos expuestos:
| Servicio | URL Local | Descripción |
| :--- | :--- | :--- |
| **PWA Frontend** | `http://localhost:3000` | Interfaz gráfica web instalable (Canvas Inferno + Historial híbrido) |
| **API Gateway** | `http://localhost:8080` | Punto único de entrada REST (`/api/fft/spectrogram`, `/api/history`) |
| **Audio Ingestion** | `http://localhost:8081` | Recepción multipart, MinIO S3 y Scatter-Gather |
| **FFT Worker 1 & 2** | `http://localhost:8082`, `:8083` | Nodos de cómputo matemático FFT |
| **Spectro-Storage** | `http://localhost:8085` | API de persistencia e historial sobre PostgreSQL |
| **MinIO Console** | `http://localhost:9001` | Consola S3 (`minioadmin` / `minioadmin`) |
| **PostgreSQL** | `localhost:5432` | Base de datos `fft_db` (`user` / `password`) |

Prueba rápida por consola contra el API Gateway:

```bash
curl -F file=@core-fft/src/main/resources/audio.wav -F windowSize=1024 -F hopSize=512 \
     http://localhost:8080/api/fft/spectrogram
```

Consultar el historial persistido en PostgreSQL:

```bash
curl http://localhost:8080/api/history
```

---

## 5. Compilación y Ejecución en Desarrollo Local (Sin Docker)

### Requisitos
- **JDK 21** o superior
- **Apache Maven 3.9+**

### 1. Compilar y ejecutar pruebas unitarias (incluyendo verificación del particionado Scatter-Gather)

```bash
mvn clean test package
```

### 2. Levantar los servicios manualmente en terminales separadas

Todos los servicios cuentan con *graceful degradation* para desarrollo local (por ejemplo, `service-storage` incluye el perfil `local` con base de datos en memoria H2, y `service-ingestion` continúa operando si MinIO no está activo):

```bash
# Terminal 1: Worker FFT 1 (:8082)
mvn -pl service-worker-fft spring-boot:run

# Terminal 2: Worker FFT 2 (:8083)
mvn -pl service-worker-fft spring-boot:run -Dspring-boot.run.arguments="--server.port=8083"

# Terminal 3: Storage Service con H2 en memoria (:8085)
mvn -pl service-storage spring-boot:run -Dspring-boot.run.profiles=local

# Terminal 4: Ingestion Service (:8081)
mvn -pl service-ingestion spring-boot:run

# Terminal 5: API Gateway (:8080)
mvn -pl api-gateway spring-boot:run
```

### 3. Cliente CLI HTTP y Benchmarks locales (`core-fft`)

```bash
# Cliente HTTP contra el API Gateway (:8080)
mvn -q -pl service-ingestion exec:java "-Dexec.mainClass=escom.chris.client.RestFftClient" "-Dexec.args=http://localhost:8080 core-fft/src/main/resources/audio.wav 1024 512"

# Benchmark de escalabilidad de hilos (en un nodo)
mvn -q -pl core-fft exec:java "-Dexec.mainClass=escom.chris.BenchmarkRunner" "-Dexec.args=core-fft/src/main/resources/audio.wav"
```
