# Reporte de Rendimiento y Escalabilidad: Pipeline FFT Concurrente

Este documento presenta los resultados de las pruebas de escalabilidad y rendimiento realizadas sobre el pipeline concurrente de la **Transformada Rápida de Fourier (FFT)** implementado bajo los patrones **Productor-Consumidor** y **Master-Worker**.

---

## 1. Entorno Experimental y Configuración de Pruebas

- **Archivo de prueba**: `src/main/resources/audio.wav` (132,300 muestras PCM mono, 44.1 kHz, 16 bits, duración ~3.0 segundos).
- **Parámetros del ventaneo FFT**:
  - Tamaño de ventana ($N$): `1024` muestras.
  - Desplazamiento (*Hop size*): `512` muestras (50% de solapamiento).
  - Total de chunks generados: **257 chunks**.
- **Entorno de Hardware y Software**:
  - **Procesador (CPU)**: 12th Gen Intel(R) Core(TM) i5-12450H (8 núcleos físicos: 4 P-Cores + 4 E-Cores, 12 hilos lógicos / procesadores virtuales).
  - **Entorno de ejecución**: Java SE (JDK 21.0.12).
  - **Sistema Operativo**: Windows 11 (64-bit).
- **Metodología de Medición**:
  - **Fase de Calentamiento (Warmup)**: 5 ejecuciones previas por configuración para permitir la compilación JIT (*Just-In-Time*) de la JVM y estabilizar caches.
  - **Muestreo**: 10 ejecuciones independientes por cada configuración de hilos $p \in \{1, 2, 4, 8, 16\}$.
  - Los valores reportados corresponden al promedio aritmético y su respectiva desviación estándar ($\sigma$).

---

## 2. Métricas Evaluadas

1. **Tiempo de Cómputo ($T_p$)**: Tiempo transcurrido (en milisegundos) desde que el hilo productor inicia el encolado hasta que el pool de trabajadores (*workers*) completa el cálculo de la FFT de todos los fragmentos y finaliza su ejecución.
2. **Speedup ($S_p$)**: Factor de aceleración obtenido al utilizar $p$ hilos de trabajo respecto a la ejecución secuencial ($p=1$):
   $$S_p = \frac{T_1}{T_p}$$
3. **Eficiencia ($E_p$)**: Porcentaje de aprovechamiento de los recursos de cómputo adicionales:
   $$E_p = \frac{S_p}{p} \times 100\%$$
4. **Throughput**: Tasa de procesamiento de fragmentos de audio por segundo ($\text{chunks/seg}$).

---

## 3. Resultados Experimentales

### 3.1. Fase de Cómputo Concurrente ($T_p$)

| Hilos de Trabajo ($p$) | Tiempo Promedio $T_p$ (ms) | Desviación Estándar ($\pm \sigma$) | Speedup ($S_p$) | Eficiencia ($E_p$) | Throughput (chunks/s) |
| :---: | :---: | :---: | :---: | :---: | :---: |
| **1** | **68.75 ms** | $\pm 10.16$ ms | **1.00x** | **100.0%** | 3,738.1 |
| **2** | **40.85 ms** | $\pm 2.98$ ms | **1.68x** | **84.2%** | 6,291.5 |
| **4** | **31.78 ms** | $\pm 1.83$ ms | **2.16x** | **54.1%** | 8,086.6 |
| **8** | **28.51 ms** | $\pm 1.22$ ms | **2.41x** | **30.1%** | 9,013.3 |
| **16** | **28.65 ms** | $\pm 1.73$ ms | **2.40x** | **15.0%** | 8,970.8 |

---

### 3.2. Desglose del Pipeline Completo (I/O + Cómputo)

| Hilos ($p$) | Lectura WAV (ms) | Cómputo FFT ($T_p$) | Exportación CSV (ms) | Tiempo Total ($T_{tot}$) | Speedup Total ($S_{tot}$) | Eficiencia Total ($E_{tot}$) |
| :---: | :---: | :---: | :---: | :---: | :---: | :---: |
| **1** | 2.02 ms | 68.75 ms | 186.52 ms | **257.29 ms** | **1.00x** | **100.0%** |
| **2** | 2.29 ms | 40.85 ms | 136.11 ms | **179.25 ms** | **1.44x** | **71.8%** |
| **4** | 2.20 ms | 31.78 ms | 120.10 ms | **154.08 ms** | **1.67x** | **41.7%** |
| **8** | 2.16 ms | 28.51 ms | 114.16 ms | **144.83 ms** | **1.78x** | **22.2%** |
| **16** | 1.89 ms | 28.65 ms | 116.33 ms | **146.87 ms** | **1.75x** | **10.9%** |

---

## 4. Gráficas Comparativas

### 4.1. Curva de Speedup ($S_p$ vs $S_{ideal}$)

```text
Speedup (Sp)
 4.0 |                                                [Ideal Sp = p]
     |                                              /
 3.0 |                                      .-/''''
     |                              _..-''''
 2.4 |                          .--' * (p=8: 2.41x)  * (p=16: 2.40x)
     |                    * (p=4: 2.16x)
 1.7 |              * (p=2: 1.68x)
 1.0 |        * (p=1: 1.00x)
 0.0 +--------+-----------+-----------+--------------+------------->
             p=1         p=2         p=4            p=8           p=16 (Hilos)
```

### 4.2. Curva de Eficiencia ($E_p$)

```text
Eficiencia (Ep)
100% |  * (100.0%)
 84% |        \
     |         * (84.2%)
 54% |               \
     |                * (54.1%)
 30% |                      \
     |                       * (30.1%)
 15% |                             \
     |                              * (15.0%)
  0% +----+-----------+-----------+--------------+------------->
         p=1         p=2         p=4            p=8           p=16 (Hilos)
```

---

## 5. Análisis Detallado y Contraste de Overhead

### 5.1. Zona de Ganancia y Alta Eficiencia ($p = 1 \rightarrow 2 \rightarrow 4$)
- De 1 a 2 hilos se observa un incremento significativo en la velocidad de cómputo (reducción de $68.75\text{ ms}$ a $40.85\text{ ms}$), con un **Speedup de 1.68x** y una **Eficiencia de 84.2%**.
- Al pasar a 4 hilos, el tiempo se reduce a $31.78\text{ ms}$ (**Speedup 2.16x**).
- En este rango, los hilos de trabajo se ejecutan en los **P-Cores (Performance Cores)** de la arquitectura Intel, beneficiándose de frecuencias altas y caches dedicadas L1/L2 con baja contención de sincronización.

### 5.2. Meseta de Rendimiento (*Plateau*) ($p = 4 \rightarrow 8$)
- Al incrementar a 8 hilos, la ganancia marginal se desacelera (tiempo baja a $28.51\text{ ms}$, Speedup de **2.41x** y Eficiencia de **30.1%**).
- **Causas**:
  1. **Heterogeneidad de Núcleos (P-Cores vs E-Cores)**: El procesador cuenta con 4 núcleos P y 4 núcleos E. Al requerir 8 hilos, parte del trabajo se asigna a los E-Cores, los cuales poseen menor IPC (*Instructions Per Cycle*) y operan a menores frecuencias.
  2. **Granularidad de la Carga de Trabajo**: Con 257 chunks totales, cada uno de los 8 workers procesa en promedio solo **~32 chunks**. El tiempo de cálculo útil por hilo es de apenas unos pocos milisegundos, por lo que los costos de creación, inicialización y notificación de hilos comienzan a ser equiparables al tiempo de cómputo útil.

### 5.3. Saturación y Degradación por Sobreasignación (*Oversubscription*) ($p = 16$)
- Con 16 hilos, el tiempo de cómputo se incrementa ligeramente a $28.65\text{ ms}$, con un Speedup de **2.40x** y una eficiencia que cae al **15.0%**.
- **Causas del Overhead**:
  1. **Cambio de Contexto (*Context Switching*)**: El sistema cuenta con 12 procesadores lógicos. Ejecutar 16 workers más el hilo productor y el hilo principal (18 hilos activos concurrentes) obliga al planificador del sistema operativo a alternar rápidamente entre hilos, provocando expulsión de líneas de caché de CPU (L1/L2 Data Caches) e invalidación de TLB.
  2. **Contención en la Cola Compartida (`ArrayBlockingQueue`)**: La cola acotada utiliza cerrojos internos (`ReentrantLock`) para coordinar las operaciones `put()` y `take()`. Al haber 16 hilos compitiendo intensamente por extraer datos de una cola con solo 257 elementos, el tiempo en estado bloqueado o compitiendo por el lock supera el beneficio de paralelización.
  3. **Contención de Escritura en la Matriz (`SharedResultMatrix`)**: La estructura compartida emplea un `ReentrantReadWriteLock` global. Cada worker adquiere el `writeLock` para registrar su columna de resultados (513 bins de frecuencia). Con 16 workers finalizando tareas concurrentemente, los hilos se serializan momentáneamente al escribir sus resultados.

---

## 6. Impacto de la Ley de Amdahl y Cuello de Botella de E/S

Analizando el pipeline completo ($T_{tot}$), observamos que el tiempo total pasa de $257.29\text{ ms}$ con 1 hilo a $144.83\text{ ms}$ con 8 hilos (Speedup total máximo de **1.78x**):

$$\text{Speedup Teórico Máximo} = \frac{1}{(1 - f) + \frac{f}{p}}$$

- La fase secuencial no paralelizable (Lectura de audio WAV $\sim 2\text{ ms}$ + Exportación de archivo CSV $\sim 115\text{ ms}$) representa más del **70% del tiempo total de ejecución**.
- La fracción paralelizable real del pipeline es de solo $f \approx \frac{68.75}{257.29} \approx 26.7\%$.
- De acuerdo con la **Ley de Amdahl**, cuando la fracción secuencial ($1 - f \approx 73.3\%$) domina el tiempo del sistema, el límite teórico absoluto de aceleración para el pipeline completo nunca podrá superar:
  $$S_{\text{límite}} = \frac{1}{1 - 0.267} = \frac{1}{0.733} \approx 1.36\text{x} \text{ (en estado continuo)}$$
- Por ende, optimizar la fase de exportación a CSV (por ejemplo, escribiendo en binario o paralelizando el formateo de texto) aportará un impacto mucho mayor en el rendimiento global del sistema que aumentar el número de hilos de cómputo más allá de 4 u 8.

---

## 7. Conclusiones

1. **Configuración Óptima**: Para cargas de trabajo similares a este archivo de audio (3 segundos, 257 chunks), el número óptimo de hilos de trabajo se sitúa entre **$p = 2$ y $p = 4$**, donde se maximiza el balance entre Speedup ($\sim 2.16x$) y Eficiencia ($\sim 54\%-84\%$) sin incurrir en cuellos de botella por contención de locks.
2. **Escalabilidad Fuerte**: La arquitectura Productor-Consumidor es robusta y previene desbordamientos de memoria gracias al *backpressure* de la cola acotada, pero requiere que el volumen de datos a procesar sea suficientemente grande para amortizar los costos de sincronización.
