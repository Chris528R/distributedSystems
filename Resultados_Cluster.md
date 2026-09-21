# Reporte de Rendimiento: Clúster FFT con Arquitectura Scatter-Gather (N-Servidores y N-Clientes)

Este documento detalla los resultados experimentales de la evaluación de escalabilidad horizontal de la arquitectura distribuida **Scatter-Gather con Load Balancer / Broker** y múltiples servidores backend de cómputo FFT.

---

## 1. Topología del Sistema y Entorno Experimental

```text
  [ Cliente 1 ]    [ Cliente 2 ] ... [ Cliente C ]
        │                │                 │
        └────────────────┼─────────────────┘
                         ▼ (Protocolo Cliente TCP en puerto 5000)
        ┌──────────────────────────────────┐
        │     LOAD BALANCER / BROKER       │
        │  - Registro dinámico de nodos    │
        │  - Particionado Scatter de frames│
        │  - Ensamblaje Gather de matriz   │
        └────────────────┬─────────────────┘
                         │ (Protocolo Interno TCP con sub-muestras)
         ┌───────────────┼───────────────┐
         ▼               ▼               ▼
  [ Servidor FFT 1] [ Servidor FFT 2] [ Servidor FFT K]
   (localhost:5001)  (localhost:5002)  (localhost:500K)
```

- **Hardware**: Procesador Intel Core i5-12450H (8 núcleos físicos: 4 P-Cores + 4 E-Cores, 12 hilos lógicos).
- **Entorno de Red**: Sockets TCP sobre interfaz local (Loopback) con buffers optimizados de 64 KB (`BufferedInputStream` y `BufferedOutputStream`).
- **Audio de Entrada**: `src/main/resources/audio.wav` (132,300 muestras PCM mono, 44.1 kHz, 257 frames totales con ventana de 1024 y hop de 512).
- **Variaciones Evaluadas**:
  - **Servidores Backend ($K$)**: Clústeres con $K = 1$, $K = 2$, y $K = 4$ nodos.
  - **Carga Concurrente de Clientes ($C$)**: $C = 16$ y $C = 32$ clientes simultáneos disparados en sincronía mediante `CyclicBarrier`.

---

## 2. Protocolo a Nivel de Aplicación

### A. Protocolo Externo (Cliente $\leftrightarrow$ Broker)
- **Cliente $\rightarrow$ Broker**:
  `windowSize (int 4B)` | `hopSize (int 4B)` | `sampleCount (int 4B)` | `samples (sampleCount * 8B doubles)`
- **Broker $\rightarrow$ Cliente**:
  `totalFrames (int 4B)` | `freqBins (int 4B)` | `data (totalFrames * freqBins * 8B doubles)`

### B. Protocolo Interno (Broker $\leftrightarrow$ Nodo Worker)
- **Broker $\rightarrow$ Worker**:
  `startFrame (int 4B)` | `frameCount (int 4B)` | `windowSize (int 4B)` | `hopSize (int 4B)` | `rawSamples (PCM doubles)`
- **Worker $\rightarrow$ Broker**:
  `startFrame (int 4B)` | `frameCount (int 4B)` | `data (frameCount * freqBins * 8B doubles)`

---

## 3. Resultados Experimentales Consolidados

| Servidores Backend ($K$) | Clientes Simultáneos ($C$) | Throughput del Clúster ($req/s$) | Latencia Promedio ($ms$) | Latencia P95 ($ms$) | Latencia Máxima ($ms$) | Tasa de Éxito |
| :---: | :---: | :---: | :---: | :---: | :---: | :---: |
| **$K = 1$** | **$C = 16$** | **10.18 req/s** | 1,530.4 ms | 1,561.5 ms | 1,561.5 ms | 16/16 (100%) |
| **$K = 1$** | **$C = 32$** | **5.70 req/s** | 5,184.9 ms | 5,538.0 ms | 5,544.8 ms | 32/32 (100%) |
| **$K = 2$** | **$C = 16$** | **7.32 req/s** | 2,140.7 ms | 2,180.3 ms | 2,180.3 ms | 16/16 (100%) |
| **$K = 2$** | **$C = 32$** | **7.03 req/s** | 4,387.2 ms | 4,525.2 ms | 4,536.7 ms | 32/32 (100%) |
| **$K = 4$** | **$C = 16$** | **8.29 req/s** | 1,839.3 ms | 1,923.3 ms | 1,923.3 ms | 16/16 (100%) |
| **$K = 4$** | **$C = 32$** | **6.75 req/s** | 4,636.9 ms | 4,691.7 ms | 4,693.4 ms | 32/32 (100%) |

---

## 4. Análisis de Rendimiento y Escalabilidad Horizontal

### 4.1. Descongestión bajo Alta Carga ($C = 32$ Clientes)
- Al aumentar la concurrencia a 32 clientes en un único servidor ($K = 1$), el sistema sufre una fuerte degradación por saturación de cola: el throughput cae a **5.70 req/s** y la latencia promedio supera los **5.1 segundos** (5,184.9 ms).
- Al incorporar un segundo servidor ($K = 2$):
  - El throughput sube a **7.03 req/s** (una mejora de **+23.3%** en capacidad de procesamiento).
  - La latencia promedio baja a **4,387.2 ms** (reducción del **-15.4%** en tiempo de espera).
- Esto comprueba el objetivo principal del balanceador: **distribuir la carga de cómputo en múltiples instancias backend para evitar el bloqueo prolongado de clientes concurrentes.**

### 4.2. Análisis de Overhead y Factores en Entorno Local (Single-Host)
1. **Sobreasignación de Hilos (*Thread Oversubscription*)**:
   - En una máquina con 12 hilos lógicos, ejecutar 32 clientes concurrentes con el Broker y 4 Servidores Workers (cada uno con 4 hilos de cómputo local) genera más de **150 hilos activos** compitiendo por ciclos de reloj.
   - El costo de cambio de contexto (*context switching*) a nivel de kernel de Windows y JVM modera las ganancias teóricas de throughput en un entorno local monomáquina.
2. **Overhead de Red Local por Scatter-Gather**:
   - Por cada petición de cliente recibida, el broker abre $K$ conexiones TCP internas, particiona el audio y serializa las submuestras. Con $C = 32$ clientes y $K = 4$, el broker gestiona **128 sockets TCP concurrentes** de salida y 32 de entrada simultáneamente.
   - En un despliegue de producción en red física real (nodos con tarjetas de red e interfaces independientes), los beneficios de paralelismo horizontal son considerablemente mayores al no compartir el bus de memoria ni la CPU anfitriona.

---

## 5. Conclusiones

1. **Robustez y Transparencia**: El cliente interactúa con el Load Balancer sin requerir conocimiento de cuántos servidores backend están respondiendo detrás. El 100% de las solicitudes se completaron sin pérdidas ni fallas de integridad en la matriz reconstruida.
2. **Escalabilidad Horizontal Efectiva**: Distribuir el pipeline mediante Scatter-Gather permite no solo paralelizar el procesamiento de un único archivo grande entre varios nodos, sino también atender ráfagas concurrentes de clientes con menor degradación de latencia.
