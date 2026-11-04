# FFT Concurrente: Pipeline de Fourier sobre API REST

Este proyecto implementa la **Transformada Rápida de Fourier (FFT)** sobre señales de audio `.wav` de forma concurrente. El cómputo se expone como **API REST con Spring Boot**; un cliente Java lee el WAV en local y envía las muestras por HTTP (`java.net.http.HttpClient`).

El procesamiento paraleliza espectros de frecuencia (espectrogramas) dividiendo la señal en ventanas con solapamiento (*overlap*), aplicando una ventana Hann, ejecutando la FFT y devolviendo la matriz de magnitudes.

Las versiones anteriores (sockets TCP, clúster Scatter-Gather y Java RMI) quedan en el historial de Git; el transporte actual es únicamente REST/JSON.

---

## Arquitectura y modos de operación

### 1. Pipeline concurrente (núcleo)

Se ejecuta **en el servidor** al atender cada petición, o de forma local sin red:

- **Lectura de audio**: `WavReader` parsea PCM y entrega muestras `double` en \([-1, 1]\). En modo REST esta lectura ocurre en el **cliente**.
- **Productor (`WavProducerTask`)**: Trocea la señal y encola bloques en una `BlockingQueue` acotada (*backpressure*).
- **Workers (`FFTWorker`)**: Pool de hilos (`ExecutorService`) que calcula FFT, magnitudes y escribe en `SharedResultMatrix`.
- **Orquestador (`FFTPipelineManager`)**: Método `compute(samples, windowSize, hopSize, numWorkers)` reutilizado por la API.

### 2. Modo distribuido (cliente-servidor REST)

```text
  [ RestFftClient ]                         [ Spring Boot :8080 ]
        │                                           │
        │  1. Lee WAV local (WavReader)             │
        │  2. POST /api/fft/spectrogram             │
        │     Content-Type: application/json        │
        └────────────── JSON ──────────────────────►│
                                                    │  FftController
                                                    │  FftComputeService
                                                    │  FFTPipelineManager.compute(...)
        ◄──────────── JSON ─────────────────────────┘
           frames, bins, magnitudes[frame][bin], computeMs
```

- **Servidor**: `DistriFourierApplication` (Tomcat embebido, puerto **8080**).
- **API**: `POST /api/fft/spectrogram`.
- **Cliente**: `RestFftClient`. No abre sockets crudos ni usa RMI; solo HTTP.

**Petición**

```json
{
  "samples": [0.0, 0.12, -0.05],
  "windowSize": 1024,
  "hopSize": 512
}
```

Si `windowSize` u `hopSize` se omiten, el servidor usa `1024` y `windowSize / 2`.

**Respuesta**

```json
{
  "frames": 128,
  "bins": 512,
  "magnitudes": [[0.01, 0.02], [0.03, 0.04]],
  "computeMs": 45
}
```

`magnitudes` usa layout **`[frame][bin]`**.

**Variante multipart (la usa la PWA)**: el mismo endpoint acepta `multipart/form-data` con el `.wav` crudo (campo `file`) y los parámetros `windowSize` / `hopSize`. El servidor decodifica el WAV con `WavReader`, por lo que la respuesta incluye además `sampleRate`, `windowSize` y `hopSize`. Las magnitudes vienen en **dB**.

```bash
curl -F file=@src/main/resources/audio.wav -F windowSize=1024 -F hopSize=512 \
     http://localhost:8080/api/fft/spectrogram
```

CORS está habilitado en `/api/**` para los orígenes de `app.cors.allowed-origins` (por defecto `http://localhost:*` y `http://127.0.0.1:*`).

### 3. Cliente web (PWA)

`frontend/` contiene una Progressive Web App en HTML/CSS/JS modular, sin dependencias ni paso de compilación:

- Carga de `.wav` por drag & drop o selector, con `windowSize` / `hopSize` configurables.
- Barra de progreso en tres fases: subida → cálculo en backend → descarga de la matriz.
- Espectrograma en `<canvas>` (paleta tipo *inferno*), ejes de tiempo/frecuencia, cursor con tooltip (t, f, dB) y control de rango dinámico.
- Historial de los últimos 3 espectrogramas en IndexedDB, consultable sin red.
- `manifest.json` + `sw.js` (caché del App Shell) para instalarla y abrirla offline.

---

## Estructura del proyecto

```text
src/main/java/escom/chris/
├── DistriFourierApplication.java   # Arranque Spring Boot
├── Main.java                       # Pipeline local (sin red)
├── FFTPipelineManager.java         # Orquestador productor-consumidor
├── BenchmarkRunner.java            # Escalabilidad local (hilos)
├── api/
│   ├── FftController.java          # POST /api/fft/spectrogram
│   ├── FftComputeService.java      # Valida y llama al pipeline
│   ├── CorsConfig.java             # CORS para la PWA
│   └── dto/
│       ├── SpectrogramRequest.java
│       └── SpectrogramResponse.java
├── client/
│   └── RestFftClient.java          # Cliente HTTP
├── audio/
│   ├── AudioChunk.java
│   └── WavReader.java
├── concurrency/
│   ├── FFTWorker.java
│   ├── SharedResultMatrix.java
│   └── WavProducerTask.java
└── math/
    ├── Complex.java
    ├── FastFourierTransform.java
    └── WindowFunction.java

src/main/resources/
├── application.properties          # Puerto 8080 y límites multipart
└── audio.wav                       # Señal de prueba
```

`pom.xml`: Spring Boot **3.4.4**, Java **21**, `spring-boot-starter-web`.

---

## Compilación y ejecución

### Requisitos
- **JDK 21** o superior
- **Apache Maven** (o el Maven embebido de IntelliJ / NetBeans)

```bash
mvn clean compile
```

### 1. Servidor REST

```bash
mvn spring-boot:run
```

En IntelliJ: ejecutar `escom.chris.DistriFourierApplication`.

El servidor queda en `http://localhost:8080`.

### 2. Cliente HTTP

En otra terminal (o configuración de ejecución de `RestFftClient`):

```bash
mvn -q exec:java "-Dexec.mainClass=escom.chris.client.RestFftClient" "-Dexec.args=http://localhost:8080 src/main/resources/audio.wav 1024 512"
```

Argumentos: `[baseUrl] [archivo.wav] [windowSize] [hopSize]`.

Por defecto: `http://localhost:8080`, `src/main/resources/audio.wav`, ventana `1024`, hop `512`.

### 3. Pipeline local (sin red)

```bash
mvn -q exec:java "-Dexec.mainClass=escom.chris.Main" "-Dexec.args=src/main/resources/audio.wav 4 1024 512 espectrograma.csv"
```

Uso: `Main <archivo.wav> [numWorkers] [windowSize] [hopSize] [salida.csv]`.

### 4. PWA

Con el servidor REST corriendo, sirve `frontend/` con cualquier servidor estático en `localhost` (los Service Workers exigen `localhost` o HTTPS):

```bash
cd frontend
python3 -m http.server 5173     # o: npx serve -l 5173
```

Abre `http://localhost:5173`. La URL del backend se puede cambiar desde el panel lateral. Si sirves la PWA desde otro origen, agrégalo a `app.cors.allowed-origins`. Al modificar archivos del frontend, incrementa `CACHE_VERSION` en `sw.js` para que los clientes instalados reciban la versión nueva.

### 5. Benchmark de hilos (local)

```bash
mvn -q exec:java "-Dexec.mainClass=escom.chris.BenchmarkRunner" "-Dexec.args=src/main/resources/audio.wav"
```

Mide speedup y eficiencia del pipeline concurrente en un solo proceso; no usa la API REST.
