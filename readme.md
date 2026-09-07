# FFT Concurrente: Pipeline Distribuidor de Fourier

Este proyecto implementa la **Transformada Rápida de Fourier (FFT)** en señales de audio `.wav` de forma concurrente y distribuida, utilizando patrones de diseño de sistemas distribuidos en Java:
1. **Productor-Consumidor** y **Master-Worker** para la concurrencia multihilo local.
2. **Arquitectura Cliente-Servidor sobre Sockets TCP** con un protocolo binario determinista de alto rendimiento.

El procesamiento paraleliza el cálculo de espectros de frecuencia (espectrogramas) dividiendo la señal de audio original en ventanas con solapamiento (*overlap*), aplicando una función de ventaneo (Hann), ejecutando la FFT y exportando los resultados a formato CSV y consola.

---

## 🛠️ Arquitectura y Modos de Operación

### 1. Modo Local (Pipeline Concurrente)
- **Lectura de Audio**: Mono-hilo secuencial mediante `WavReader`.
- **Productor (`WavProducerTask`)**: Trocea la señal y la encola en una `BlockingQueue` acotada (*backpressure*).
- **Consumidores / Workers (`FFTWorker`)**: Pool de hilos (`ExecutorService`) que calcula FFT, magnitudes en dB y escribe concurrentemente en `SharedResultMatrix`.

### 2. Modo Distribuido (Cliente-Servidor TCP)
Desacopla el pipeline en dos entidades de red mediante un protocolo binario directo sin sobrecarga de texto (usando `DataInputStream` y `DataOutputStream` con buffers de 64 KB):

```text
[CLIENTE -> SERVIDOR]
┌──────────────────┬─────────────────┬──────────────────┬────────────────────────┐
│ windowSize (int) │ hopSize (int)   │ sampleCount (int)│ samples (sampleCount*8)│
│     4 bytes      │     4 bytes     │     4 bytes      │      raw doubles       │
└──────────────────┴─────────────────┴──────────────────┴────────────────────────┘

[SERVIDOR -> CLIENTE]
┌──────────────────┬──────────────────┬──────────────────────────────────────────┐
│ totalFrames (int)│ freqBins (int)   │ data (totalFrames * freqBins * 8 bytes)  │
│     4 bytes      │     4 bytes      │            raw doubles (mag)             │
└──────────────────┴──────────────────┴──────────────────────────────────────────┘
```

- **Cliente (`FFTClient`)**: Lee el WAV local, empaqueta las muestras y configuración, las transmite al servidor por TCP, recibe la matriz del espectrograma, la exporta a CSV y genera una visualización ASCII en consola.
- **Servidor (`FFTServer`)**: Acepta conexiones concurrentes, deserializa las muestras en memoria, ejecuta el pipeline multihilo de la FFT y devuelve la matriz calculada en binario.

---

## 📁 Estructura del Proyecto

```text
src/main/java/escom/chris/
│
├── Main.java                   # Punto de entrada local
├── FFTPipelineManager.java     # Orquestador del pipeline en memoria/disco
├── BenchmarkRunner.java        # Ejecutor de pruebas de escalabilidad y métricas
│
├── client/
│   └── FFTClient.java          # Cliente TCP: transmisión WAV y visualización
│
├── server/
│   └── FFTServer.java          # Servidor TCP: ServerSocket y despacho de workers
│
├── audio/
│   ├── AudioChunk.java         # Modelo de datos para un bloque de audio
│   └── WavReader.java          # Lector manual de cabecera WAV PCM
│
├── concurrency/
│   ├── FFTWorker.java          # Worker consumidor que ejecuta FFT
│   ├── SharedResultMatrix.java # Matriz compartida con streaming binario
│   └── WavProducerTask.java    # Productor de chunks en cola
│
└── math/
    ├── Complex.java            # Operaciones aritméticas de números complejos
    ├── FastFourierTransform.java # Algoritmo FFT Cooley-Tukey
    └── WindowFunction.java     # Ventanas matemáticas (Hann, etc.)
```

---

## 🚀 Compilación y Ejecución

### Requisitos
- **Java Development Kit (JDK) 21** o superior instalado.

### 1. Compilar todo el proyecto
Desde la carpeta raíz del repositorio:
```powershell
javac -d target/classes src/main/java/escom/chris/*.java src/main/java/escom/chris/audio/*.java src/main/java/escom/chris/concurrency/*.java src/main/java/escom/chris/math/*.java src/main/java/escom/chris/server/*.java src/main/java/escom/chris/client/*.java
```

---

### 2. Ejecutar en Modo Distribuido (Cliente-Servidor TCP)

#### Paso 1: Iniciar el Servidor
```powershell
java -cp target/classes escom.chris.server.FFTServer [puerto] [numWorkers]
```
*Ejemplo (puerto 5000 con 8 workers):*
```powershell
java -cp target/classes escom.chris.server.FFTServer 5000 8
```

#### Paso 2: Ejecutar el Cliente
```powershell
java -cp target/classes escom.chris.client.FFTClient <host> <puerto> <archivo.wav> [windowSize] [hopSize] [salida.csv]
```
*Ejemplo:*
```powershell
java -cp target/classes escom.chris.client.FFTClient localhost 5000 src/main/resources/audio.wav 1024 512 client_spectrogram.csv
```

---

### 3. Ejecutar en Modo Local (Pipeline Monolítico)
```powershell
java -cp target/classes escom.chris.Main <archivo.wav> [numWorkers] [windowSize] [hopSize] [salida.csv]
```
*Ejemplo:*
```powershell
java -cp target/classes escom.chris.Main "src/main/resources/audio.wav" 4 1024 512 "espectrograma.csv"
```

---

### 4. Pruebas de Escalabilidad y Rendimiento
Para replicar las pruebas de Speedup, Eficiencia y Overhead reportadas en [`Resultados.md`](Resultados.md):
```powershell
java -cp target/classes escom.chris.BenchmarkRunner "src/main/resources/audio.wav"
```
