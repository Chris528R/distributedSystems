# FFT Concurrente: Pipeline Distribuidor de Fourier

Este proyecto implementa la **Transformada Rápida de Fourier (FFT)** en señales de audio `.wav` de forma concurrente, utilizando patrones de diseño de sistemas distribuidos y concurrentes en Java (Productor-Consumidor y Master-Worker).

El procesamiento paraleliza el cálculo de espectros de frecuencia (espectrogramas) dividiendo la señal de audio original en ventanas con solapamiento (*overlap*), aplicando una función de ventaneo (Hann), ejecutando la FFT y exportando los resultados a un formato estructurado en CSV.

---

## 🛠️ Arquitectura y Patrones de Diseño

El sistema está estructurado mediante un pipeline de procesamiento concurrente:

1. **Lectura de Audio (I/O Secuencial)**:
   - Se realiza de forma secuencial y mono-hilo a través de `WavReader`, que decodifica y normaliza las muestras PCM del archivo `.wav`.

2. **Productor (`WavProducerTask`)**:
   - Corre en su propio hilo dedicado (`Producer-WavChunker`).
   - Se encarga de trocear la señal en ventanas de tamaño fijo ($N$, por defecto 1024) aplicando el desplazamiento especificado (*hop size*).
   - Publica los fragmentos de audio (`AudioChunk`) en una cola bloqueante acotada (`BlockingQueue`). Esto proporciona control de flujo nativo (*backpressure*): si los hilos consumidores están saturados, el productor se bloquea hasta que haya espacio en la cola.
   - Al finalizar, envía señales de apagado (*poison pills*) a los consumidores para iniciar el cierre ordenado del pipeline.

3. **Consumidores / Workers (`FFTWorker`)**:
   - Múltiples hilos concurrentes gestionados por un pool de hilos (`ExecutorService`).
   - Consumen los fragmentos de la cola compartida.
   - Procesan cada bloque de forma independiente y sin estado (*stateless*): aplican la función de ventaneo, calculan la FFT y obtienen la magnitud en decibelios (dB).
   - Escriben de manera segura la columna de resultados en una matriz compartida (`SharedResultMatrix`).

4. **Escritura y Exportación**:
   - Una vez finalizado el cálculo concurrente, el orquestador exporta la matriz a un archivo CSV estructurado para su posterior análisis o graficación.

---

## 📁 Estructura del Proyecto

```text
src/main/java/escom/chris/
│
├── Main.java                   # Punto de entrada y análisis de argumentos CLI
├── FFTPipelineManager.java     # Orquestador del pipeline completo
│
├── audio/
│   ├── AudioChunk.java         # Modelo de datos para un bloque de audio
│   └── WavReader.java          # Lector manual de la cabecera WAV
│
├── concurrency/
│   ├── FFTWorker.java          # Worker consumidor que ejecuta FFT
│   ├── SharedResultMatrix.java # Matriz concurrente para resultados
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

#### Parámetros:
- `<archivo.wav>`: Ruta al archivo de audio de entrada (ej. `src/main/resources/audio.wav`).
- `[numWorkers]` *(Opcional)*: Número de hilos consumidores concurrentes (por defecto: número de núcleos de la CPU).
- `[windowSize]` *(Opcional)*: Tamaño de ventana para el procesamiento FFT (debe ser potencia de 2, por defecto: `1024`).
- `[hopSize]` *(Opcional)*: Desplazamiento entre ventanas en muestras (por defecto: `windowSize / 2` para 50% de solapamiento).
- `[salida.csv]` *(Opcional)*: Nombre del archivo de salida del espectrograma (por defecto: `spectrogram.csv`).

