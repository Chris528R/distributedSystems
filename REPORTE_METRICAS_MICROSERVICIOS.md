# Reporte Experimental: Evaluación de Desempeño de la Arquitectura de Microservicios DistriFourier

Este documento presenta los resultados experimentales obtenidos al evaluar la arquitectura distribuida de **4 microservicios** (`api-gateway`, `service-ingestion`, `service-worker-fft`, `service-storage`) junto con almacenamiento de objetos compatible con **S3** y persistencia relacional en **PostgreSQL 16**, orquestados mediante **Docker Compose**.

---

## 1. Entorno Experimental y Señales de Prueba

- **Plataforma de ejecución**: Contenedores Linux (`eclipse-temurin:21-jre-alpine`, `postgres:16-alpine`, `adobe/s3mock`, `nginx:1.27-alpine`) sobre Docker Desktop (Windows).
- **Señal de referencia principal (`audio.wav`)**:
  - Tamaño del archivo `.wav` bruto: `264,644 bytes` (~258.4 KB, PCM 16-bit, `44,100 Hz`).
  - Parámetros FFT por defecto: `windowSize = 1024`, `hopSize = 512` (solapamiento del 50%).
  - Dimensiones del espectrograma resultante: `257 frames × 512 bins` = `131,584 celdas` en decibelios (dB).
- **Señales adicionales para pruebas de almacenamiento (`chirp.wav`, `melody.wav`)**:
  - `chirp.wav` (`1024 / 512`): `257 frames × 512 bins`.
  - `melody.wav` (`1024 / 512`): `343 frames × 512 bins`.
  - `audio.wav` alta resolución (`2048 / 512`): `255 frames × 1024 bins`.

---

## 2. Métrica A: Overhead de Microservicios vs. Monolito Local y Java RMI

En la arquitectura de microservicios, el tiempo de respuesta de extremo a extremo ($\text{RTT}_{\text{Total}}$) desde que la PWA envía el archivo `.wav` vía `multipart/form-data` hasta que recibe el JSON consolidado se descompone como:

$$
\text{RTT}_{\text{Total}} = T_{\text{Gateway}} + T_{\text{Decode WAV}} + T_{\text{MinIO S3}} + T_{\text{Scatter-Gather (HTTP)}} + T_{\text{GZIP + PostgreSQL}}
$$

### 2.1 Resultados de 10 Iteraciones Síncronas E2E (Post-Warmup JIT)

| Iteración | $\text{RTT}_{\text{Total}}$ Cliente $\leftrightarrow$ Gateway (ms) | $T_{\text{Scatter-Gather (HTTP)}}$ (2 Workers, ms) | Resto del Pipeline ($T_{\text{Gateway}} + T_{\text{S3}} + T_{\text{GZIP+PG}}$, ms) | Payload JSON Recibido (Bytes) |
| :---: | :---: | :---: | :---: | :---: |
| 1 | `369.62 ms` | `114.00 ms` | `255.62 ms` | `2,485,269 B` |
| 2 | `526.65 ms` | `231.00 ms` | `295.65 ms` | `2,485,269 B` |
| 3 | `497.47 ms` | `161.00 ms` | `336.47 ms` | `2,485,269 B` |
| 4 | `330.62 ms` | `126.00 ms` | `204.62 ms` | `2,485,269 B` |
| 5 | `376.58 ms` | `120.00 ms` | `256.58 ms` | `2,485,269 B` |
| 6 | `533.92 ms` | `216.00 ms` | `317.92 ms` | `2,485,269 B` |
| 7 | `366.47 ms` | `114.00 ms` | `252.47 ms` | `2,485,269 B` |
| 8 | `316.51 ms` | `101.00 ms` | `215.51 ms` | `2,485,269 B` |
| 9 | `346.99 ms` | `99.00 ms` | `247.99 ms` | `2,485,268 B` |
| 10 | `426.03 ms` | `111.00 ms` | `315.03 ms` | `2,485,269 B` |
| **Promedio** | **`409.09 ms`** | **`139.30 ms`** | **`269.79 ms`** | **`2,485,269 B` (2.37 MB)** |
| **Mínimo (Óptimo)** | **`316.51 ms`** | **`99.00 ms`** | **`204.62 ms`** | **`2,485,268 B`** |

### 2.2 Desglose Promedio por Etapa Interna del Pipeline

A partir de la telemetría instrumentada en `IngestionOrchestratorService` (`[MÉTRICAS INGESTA]` y `[MÉTRICAS PIPELINE]`):

| Etapa del Pipeline Distribuido | Tiempo Promedio (ms) | Porcentaje del $\text{RTT}_{\text{Total}}$ | Operación Realizada |
| :--- | :---: | :---: | :--- |
| 1. $T_{\text{Decode WAV}}$ (`WavReader`) | `15.86 ms` | `3.88 %` | Parseo RIFF/WAVE y normalización PCM a `double[]` en $[-1, 1]$ |
| 2. $T_{\text{MinIO S3}}$ (`PutObject`) | `108.77 ms` | `26.59 %` | Almacenamiento del `.wav` bruto (`264 KB`) en bucket S3 |
| 3. $T_{\text{Scatter-Gather (HTTP)}}$ | `139.30 ms` | `34.05 %` | Particionado con margen, envío paralelo a $N$ workers, FFT Cooley-Tukey (~16 ms) y serialización JSON |
| 4. $T_{\text{GZIP + PostgreSQL}}$ | `95.40 ms` | `23.32 %` | Conversión a `float32`, compresión GZIP y `INSERT` en tabla `spectrogram_history` |
| 5. $T_{\text{Gateway + Red Externa}}$ | `49.76 ms` | `12.16 %` | Proxy inverso en `api-gateway` (`:8080`) y transmisión de `2.48 MB` al cliente |
| **Total ($\text{RTT}_{\text{Total}}$)** | **`409.09 ms`** | **`100.00 %`** | **Flujo completo con persistencia dual (S3 + PostgreSQL)** |

### 2.3 Contraste Arquitectónico: Monolito vs. RMI vs. Microservicios

| Evolución Arquitectónica | Tiempo de Cómputo / RTT | Persistencia de Audio Bruto | Persistencia de Resultados | Escalabilidad Horizontal | Tolerancia a Fallos |
| :--- | :---: | :---: | :---: | :---: | :---: |
| **Etapa 1: Monolito Concurrente (`ExecutorService`)** | `~28.00 ms` | No (Disco local efímero) | CSV local manual | Vertical únicamente (núcleos de 1 máquina) | Nula (SPOF en proceso único) |
| **Etapa 2: Distribuido Java RMI** | `~18.78 ms` | No | En memoria volátil | Fija / Manual por IP:Puerto | Baja (acoplamiento fuerte Java-a-Java) |
| **Etapa 3: Microservicios Cloud-Native (Scatter-Gather)** | `99–139 ms` *(Scatter-Gather)* / `409.09 ms` *(RTT Completo)* | **Sí (S3 / MinIO)** | **Sí (PostgreSQL + GZIP + IndexedDB)** | **Elástica (`--scale worker-fft=N` / HPA)** | **Alta (Workers *stateless* + Fallback)** |

> **Discusión (Métrica A)**: Mientras que el monolito local (~28 ms) y Java RMI (~18.78 ms) operaban exclusivamente en memoria volátil sin persistir ni el audio original ni la matriz calculada, el pipeline de microservicios completa en **~409 ms** un ciclo industrial completo: ingesta multipart a través de un API Gateway, respaldo del `.wav` en almacenamiento de objetos S3, cómputo distribuido Scatter-Gather sobre HTTP, compresión binaria GZIP e inserción transaccional en PostgreSQL. Además, gracias al `service-storage`, las consultas subsecuentes al mismo espectrograma (`GET /api/history/{id}`) se resuelven en **~26–34 ms** sin volver a ejecutar la FFT.

---

## 3. Métrica B: Efectividad de la Compresión de Matrices (`MatrixCompressionCodec`)

El microservicio `service-storage` (`:8085`) utiliza [`MatrixCompressionCodec`](service-storage/src/main/java/escom/chris/storage/service/MatrixCompressionCodec.java) para transformar la matriz consolidada `double[frames][bins]` (64 bits por celda) a precisión simple `float32` (32 bits por celda, idéntica a la usada por el `Float32Array` del `<canvas>` en la PWA) seguida de compresión **DEFLATE / GZIP** antes de almacenarla como columna binaria `BYTEA` en PostgreSQL.

### 3.1 Resultados Extraídos de PostgreSQL (`spectrogram_history`)

Consulta ejecutada directamente sobre el motor `postgres:16-alpine`:

```sql
SELECT file_name, window_size, hop_size, frames, bins,
       (frames * bins * 8) AS raw_double_bytes,
       (frames * bins * 4) AS raw_float32_bytes,
       octet_length(compressed_matrix) AS gzip_postgres_bytes,
       ROUND((1.0 - octet_length(compressed_matrix)::numeric / (frames * bins * 8)) * 100, 2) AS reduction_vs_double_pct
FROM spectrogram_history;
```

| Archivo de Audio | `windowSize / hopSize` | Dimensiones (`frames × bins`) | Tamaño en JSON REST (`bytes`) | Matriz Cruda `double[][]` (`8 B/celda`) | Matriz `float32` (`4 B/celda`) | Tamaño Comprimido en PostgreSQL (`float32 + GZIP`) | Ahorro vs. `double[][]` en Memoria | Ahorro vs. Payload JSON REST |
| :--- | :---: | :---: | ---: | ---: | ---: | ---: | :---: | :---: |
| **`audio.wav`** | `1024 / 512` | `257 × 512` | `2,485,269 B` *(2.37 MB)* | `1,052,672 B` *(1.00 MB)* | `526,336 B` | **`428,144 B`** *(418.1 KB)* | **59.33 % (2.46×)** | **82.77 % (5.80×)** |
| **`chirp.wav`** | `1024 / 512` | `257 × 512` | `2,485,110 B` *(2.37 MB)* | `1,052,672 B` *(1.00 MB)* | `526,336 B` | **`444,695 B`** *(434.3 KB)* | **57.76 % (2.37×)** | **82.11 % (5.59×)** |
| **`melody.wav`** | `1024 / 512` | `343 × 512` | `3,316,890 B` *(3.16 MB)* | `1,404,928 B` *(1.34 MB)* | `702,464 B` | **`592,566 B`** *(578.7 KB)* | **57.82 % (2.37×)** | **82.13 % (5.60×)** |
| **`audio.wav` (HD)** | `2048 / 512` | `255 × 1024` | `4,931,520 B` *(4.70 MB)* | `2,088,960 B` *(1.99 MB)* | `1,044,480 B` | **`860,829 B`** *(840.7 KB)* | **58.79 % (2.43×)** | **82.54 % (5.73×)** |

> **Discusión (Métrica B)**:
> 1. **Frente a la estructura en memoria de Java (`double[][]` de $1,052,672\text{ B}$)**: La codificación `float32 + GZIP` reduce el tamaño a **$428,144\text{ B}$** (**59.33 % de reducción**, ocupando ~40.6 % del tamaño original en RAM).
> 2. **Frente al almacenamiento ingenuo en texto/JSON (`JSONB` de $2,485,269\text{ B}$)**: La reducción alcanza el **82.77 %** (pasando de **2.48 MB** a **418 KB** por registro). En una base de datos distribuida en la nube, esto reduce en **5.8 veces** el tráfico de red entre `service-storage` y PostgreSQL, evita la fragmentación de páginas *TOAST* en PostgreSQL y disminuye drásticamente el costo de almacenamiento por usuario.

---

## 4. Métrica C: Escalabilidad Elástica Horizontal en Docker Compose

Para evaluar el comportamiento bajo concurrencia y la elasticidad horizontal del Microservicio 3 (`service-worker-fft`), se utilizó el comando nativo de escalamiento de Docker Compose:

```bash
docker compose up -d --scale worker-fft=N
```

El servicio `service-ingestion` descubre dinámicamente todas las réplicas activas resolviendo los registros DNS `A` del servicio `worker-fft` en la red interna de Docker (`InetAddress.getAllByName("worker-fft")`) y reparte tanto las sub-ventanas (Scatter-Gather) como las peticiones entrantes mediante *Round-Robin*.

### 4.1 Rendimiento ante Ráfaga Concurrente de 20 Clientes Simultáneos (`audio.wav`, `5,140 frames` totales)

| Configuración del Clúster | Comando de Escalamiento | Tiempo Total de la Ráfaga (ms) | Latencia Promedio por Cliente (ms) | Latencia Mín / Máx (ms) | Throughput (peticiones/s) | Throughput (frames FFT/s) | Speedup Elástico ($S_N$) |
| :--- | :--- | :---: | :---: | :---: | :---: | :---: | :---: |
| **1 Réplica Worker** | `--scale worker-fft=1` | `7,021.7 ms` | `3,250.2 ms` | `2,166.2 / 4,526.2 ms` | `2.85 req/s` | `732.0 frames/s` | `1.00×` *(Base)* |
| **2 Réplicas Worker** | `--scale worker-fft=2` | `4,668.2 ms` | `2,107.4 ms` | `1,737.8 / 2,576.0 ms` | `4.28 req/s` | `1,101.1 frames/s` | **`1.50×`** |
| **4 Réplicas Worker (Warm)** | `--scale worker-fft=4` | **`3,193.1 ms`** | **`1,538.8 ms`** | **`98.3 / 2,999.7 ms`** | **`6.26 req/s`** | **`1,609.7 frames/s`** | **`2.20×`** |
| *4 Réplicas (Cold-Start)* | *Recién iniciadas (<6 s)* | *`24,728.7 ms`* | *`12,270.9 ms`* | *`7,386.3 / 17,046.6 ms`* | *`0.81 req/s`* | *`207.9 frames/s`* | *Penalización por arranque JVM* |

### 4.2 Análisis de Escalabilidad y Fenómeno de *Cold-Start*

1. **Aumento sostenido de Throughput (`1.00×` $\rightarrow$ `1.50×` $\rightarrow$ `2.20×`)**:
   - Al pasar de **1 a 2 réplicas**, el tiempo para drenar la ráfaga de 20 clientes cae de `7.02 s` a `4.67 s`, incrementando el throughput un **50.2 %** (`732.0` $\rightarrow$ `1,101.1 frames/s`).
   - Al escalar a **4 réplicas calientes (Warm)**, el tiempo total baja a **`3.19 s`**, alcanzando **`6.26 peticiones/s` (`1,609.7 frames/s`)**, lo que representa un **Speedup de `2.20×`** y una reducción del **52.6 %** en la latencia promedio percibida por los clientes bajo carga.
2. **Importancia del *Readiness Probe* y *Warmup* en la Nube (*Cold-Start*)**:
   - Durante el experimento se midió también una ráfaga disparada inmediatamente después de ejecutar `docker compose up -d --scale worker-fft=4` (cuando las réplicas 3 y 4 aún estaban inicializando la JVM y el servidor Tomcat embebido). En ese escenario de *Cold-Start*, la ráfaga tardó `24.7 s` debido a la inicialización de clases y compilación JIT.
   - Una vez que las 4 instancias alcanzaron estado estable (*Warm*), el tiempo cayó de `24.7 s` a **`3.19 s`**. Esto demuestra empíricamente por qué en despliegues de Kubernetes con *Horizontal Pod Autoscaler (HPA)* es indispensable configurar `readinessProbe` hacia `/actuator/health` antes de enrutar tráfico de cómputo FFT a los nuevos pods.

