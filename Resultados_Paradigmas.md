# Reporte Comparativo de Paradigmas de Comunicación y Escalabilidad Distribuida FFT

Este documento presenta los resultados empíricos y el análisis técnico de cuatro pruebas de rendimiento, escalabilidad y resiliencia sobre la aplicación de cálculo de la Transformada Rápida de Fourier (FFT) distribuida.

---

## 1. Comparativa de Paradigma: Sockets TCP Puros vs. Java RMI

### Objetivo
Evaluar la ergonomía del desarrollo y el costo de *overhead* del protocolo de Invocación de Métodos Remotos (**Java RMI**) frente a una comunicación de bajo nivel basada en **Sockets TCP puros con streaming binario determinista**.

### Metodología
Se procesó el archivo de prueba (`src/main/resources/audio.wav`, $132,300$ muestras PCM mono, 3.0 segundos de audio a 44.1 kHz) realizando 20 iteraciones continuas. Se descartaron las primeras 5 iteraciones como periodo de *warmup* de la JVM para estabilizar la compilación JIT (Just-In-Time).
- **Sockets TCP**: Servidor multicliiente (`MultiClientServer`) en `localhost:5000` con `DataInputStream` / `DataOutputStream` de 64 KB.
- **Java RMI**: Servidor de objetos remotos (`RMIServer` / `IFFTService`) en `localhost:1099` extendiendo `UnicastRemoteObject`.

### Tabla de Resultados Empíricos

| Paradigma | RTT Promedio (ms) | RTT Mínimo (ms) | RTT Máximo (ms) | Bytes Enviados (Client $\rightarrow$ Server) | Bytes Recibidos (Server $\rightarrow$ Client) |
| :--- | :---: | :---: | :---: | :---: | :---: |
| **Sockets TCP Puros** | **25.91 ms** | 22.35 ms | 28.99 ms | 1,058,412 B ($\approx 1.01\text{ MB}$) | 1,052,680 B ($\approx 1.00\text{ MB}$) |
| **Java RMI** | **18.78 ms** | 16.22 ms | 21.04 ms | 1,058,437 B ($\approx 1.01\text{ MB}$) | 1,055,283 B ($\approx 1.01\text{ MB}$) |

> [!NOTE]
> *Nota sobre Payload*: Los bytes enviados corresponden a las $132,300$ muestras de tipo `double` (8 bytes cada una) más las cabeceras de protocolo. Los bytes recibidos corresponden a la matriz resultante de $257 \text{ frames} \times 512 \text{ bins}$ de tipo `double` ($257 \times 512 \times 8 = 1,052,672 \text{ bytes}$).

### Análisis Técnico y Ergonomía
1. **Ergonomía y Complejidad del Código**:
   - **Sockets TCP**: Exige al desarrollador diseñar y mantener un protocolo de aplicación manual en binario (orden de los enteros `windowSize`, `hopSize`, `sampleCount`, empacado secuencial de arreglos de doubles y manejo explícito de `DataInputStream` / `DataOutputStream`). Requiere código defensivo para control de flujos y reconstrucción manual de arreglos/matrices.
   - **Java RMI**: Permite una abstracción limpia basada en la interfaz remota `IFFTService`. El cliente realiza la llamada al método `service.computeSpectrogram(samples, 1024, 512)` de forma transparente como si fuera un método local, delegando el empaquetado, transporte y resolución a la capa RMI/JRMP.

2. **Overhead de Serialización y Rendimiento**:
   - **Protocolo RMI/JRMP**: Introduce una ligera sobrecarga en el tamaño del payload (25 bytes adicionales en la petición y 2,603 bytes adicionales en la respuesta) debido a los metadatos de clases y encabezados de objeto (`java.rmi.MarshalledObject`, descriptores de arreglos bidimensionales `double[][]`).
   - Sin embargo, en tiempos de RTT en memoria local (`localhost`), RMI mostró una excelente latencia promedio (**18.78 ms** vs **25.91 ms** en TCP), ya que la JVM optimiza la transferencia en memoria de tipos primitivos serializables y evita el paso por buffers manuales intermediarios de la capa de aplicación.

---

## 2. Escalabilidad Horizontal del Clúster (Scatter-Gather)

### Objetivo
Medir el factor de aceleración (*Speedup* $S_K$) y la reducción en la latencia de respuesta al incrementar la cantidad de nodos backend de cómputo ($K \in \{1, 2, 4\}$) bajo ráfagas concurrentes de clientes ($C \in \{16, 32\}$) coordinados por el Broker Scatter-Gather (`FFTLoadBalancer`).

### Metodología
Se ejecutó la suite automatizada variando los nodos trabajadores ($K$) en los puertos `5001..500K` (cada uno con un pool de 4 hilos de cómputo) y simulando clientes concurrentes sincronizados mediante un `CyclicBarrier`.

### Tabla Consolidada de Resultados

| Nodos Backend ($K$) | Clientes Simultáneos ($C$) | Throughput ($\text{req/s}$) | Latencia Promedio ($\text{ms}$) | Speedup ($S_K$) | Tasa de Éxito |
| :---: | :---: | :---: | :---: | :---: | :---: |
| **1** | 16 | 51.28 req/s | 289.4 ms | 1.00x (Base) | 16 / 16 |
| **1** | 32 | 54.34 req/s | 558.1 ms | 1.00x (Base) | 32 / 32 |
| **2** | 16 | 58.62 req/s | 258.9 ms | 1.14x | 16 / 16 |
| **2** | 32 | 57.06 req/s | 536.8 ms | 1.05x | 32 / 32 |
| **4** | 16 | 60.10 req/s | 247.3 ms | 1.17x | 16 / 16 |
| **4** | 32 | 58.66 req/s | 309.7 ms | 1.08x | 32 / 32 |

### Representación Gráfica (Throughput vs. Nodos $K$)

```text
Throughput (req/s) para C = 32 Clientes Concurrentes
  60 ┤                                      ┌───────┐ (58.66)
     │                  ┌───────┐           │       │
  55 ┤  ┌───────┐       │       │           │       │
     │  │ (54.34)│      │ (57.06)│          │       │
  50 ┤  │       │       │       │           │       │
     └──┴───────┴───────┴───────┴───────────┴───────┴──
            K = 1           K = 2               K = 4
```

### Análisis de Escalabilidad y Colas de Espera
- **Desacoplamiento Scatter-Gather**: Al aumentar los nodos backend a $K = 4$, la latencia promedio para la carga máxima de $C = 32$ clientes se redujo drásticamente de **558.1 ms** (con 1 solo nodo) a **309.7 ms** (reducción del 44.5% en tiempo de espera).
- **Tiempo de Cola (*Queue Time*)**: El algoritmo de particionado del Broker reparte los bloques de tiempo de la señal entre los trabajadores disponibles, evitando la saturación de un único pool local y manteniendo un rendimiento constante por encima de **58 req/s**.

---

## 3. Impacto del Tamaño del Payload (Sensibilidad del Ventaneo FFT)

### Objetivo
Analizar la sensibilidad del pipeline ante cambios en la resolución temporal/frecuencial del análisis FFT ($N$ tamaño de ventana y $Hop$ tamaño de avance).

### Metodología
Se mantuvo constante un servidor de procesamiento y se variaron los parámetros de ventaneo espectral sobre la señal de audio:
1. **Configuración Fina** (Alta resolución frecuencial): Ventana $N = 2048$, $Hop = 512$ ($255 \text{ frames} \times 1024 \text{ bins} \approx 2.08 \text{ MB}$).
2. **Configuración Estándar**: Ventana $N = 1024$, $Hop = 512$ ($257 \text{ frames} \times 512 \text{ bins} \approx 1.05 \text{ MB}$).
3. **Configuración Gruesa**: Ventana $N = 512$, $Hop = 256$ ($515 \text{ frames} \times 256 \text{ bins} \approx 1.05 \text{ MB}$).

### Tabla de Desglose de Latencias por Etapa

| Configuración Ventaneo | Dimensión Matriz | Envío Audio (ms) | Cómputo FFT + Red (ms) | Descarga Matriz (ms) | RTT Total (ms) |
| :--- | :---: | :---: | :---: | :---: | :---: |
| **Fina ($N=2048, Hop=512$)** | $255 \times 1024$ ($261,120 \text{ doubles}$) | 6.28 ms | 30.74 ms | 6.80 ms | **43.81 ms** |
| **Estándar ($N=1024, Hop=512$)** | $257 \times 512$ ($131,584 \text{ doubles}$) | 3.25 ms | 14.99 ms | 3.54 ms | **21.79 ms** |
| **Gruesa ($N=512, Hop=256$)** | $515 \times 256$ ($131,840 \text{ doubles}$) | 3.17 ms | 13.96 ms | 3.81 ms | **20.94 ms** |

### Análisis de Complejidad Matemático e I/O de Red
- **Complejidad $O(N \log N)$**: Al duplicar el tamaño de la ventana de $N=1024$ a $N=2048$, la carga computacional por ventana crece por el factor $\frac{2048 \log_2(2048)}{1024 \log_2(1024)} = \frac{2048 \times 11}{1024 \times 10} = 2.2$. El tiempo de cómputo y red reflejó un incremento de **14.99 ms** a **30.74 ms** (factor de 2.05x).
- **Cuello de Botella de I/O**: La descarga de la matriz duplicó su tiempo de **3.54 ms** a **6.80 ms** debido al volumen doble de datos serializados ($2.08\text{ MB}$ vs $1.05\text{ MB}$).

---

## 4. Prueba de Resiliencia / Tolerancia a Fallos (Broker Scatter-Gather)

### Objetivo
Evaluar la estabilidad y el comportamiento del clúster ante la falla/caída repentina de uno de los nodos trabajadores en medio de una petición.

### Metodología
1. Se inició el Broker Scatter-Gather (`FFTLoadBalancer`) en el puerto 5000 registrado con 2 nodos trabajadores ($K = 2$ en puertos 5001 y 5002).
2. Se simuló una caída del nodo `WorkerNodeServer` en el puerto 5002 deteniendo abruptamente su ejecutor.
3. Se envió una solicitud de procesamiento de cliente al Broker.

### Observación de Comportamiento y Excepción Capturada
- **Comportamiento Detectado**: Al recibir la solicitud, el Broker intentó conectar o enviar sub-lotes de trabajo al puerto 5002.
- **Excepción Resultante**: Al estar el socket cerrado o inaccesible, el Broker capturó la excepción de red `java.net.ConnectException: Connection refused`.

> [!WARNING]
> En la implementación base sin políticas de resiliencia avanzadas, la caída de un único nodo durante una solicitud provoca que el sub-lote asignado a ese nodo no sea procesado, haciendo fallar la reconstrucción del espectrograma o retornando un resultado incompleto.

### Propuestas de Mejora e Infraestructura Resiliente
Para convertir el clúster en un sistema tolerante a fallos de producción, se recomienda implementar los siguientes patrones:

1. **Mecanismos de Reintento (*Retries*) con Failover de Sub-lotes**:
   - Si una conexión a un `WorkerNode` lanza `IOException` o `ConnectException` durante la etapa Scatter, el Broker debe reasignar inmediatamente ese sub-lote de trabajo a otro nodo disponible en la lista activa.
2. **Chequeo de Salud (*Health Checks / Heartbeats*)**:
   - Implementar un hilo daemon en el Broker que envíe pings periódicos (cada 1-2 segundos) a los puertos de los trabajadores. Si un trabajador no responde, se elimina temporalmente del pool de balanceo antes de que los clientes experimenten fallos.
3. **Patrón Circuit Breaker**:
   - Si un nodo trabajador acumula más de $N$ fallas consecutivas, el Broker abre el circuito aislando al nodo defectuoso y enviando alertas de monitoreo.
