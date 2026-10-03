# FFT Concurrente: Pipeline Distribuidor de Fourier

Este proyecto implementa la **Transformada Rápida de Fourier (FFT)** en señales de audio `.wav` de forma concurrente y distribuida, utilizando patrones de diseño de sistemas distribuidos en Java:
1. **Productor-Consumidor** y **Master-Worker** para la concurrencia multihilo local.
2. **Arquitectura Cliente-Servidor sobre Sockets TCP** con un protocolo binario determinista de alto rendimiento.
3. **Arquitectura Clúster Scatter-Gather con Load Balancer / Broker** para distribuir el cálculo entre $K$ servidores backend ante $C$ clientes simultáneos.
4. **Arquitectura de Objetos Distribuidos con Java RMI (Remote Method Invocation)** para abstraer la comunicación de red mediante Stubs y llamadas a métodos remotos.

El procesamiento paraleliza el cálculo de espectros de frecuencia (espectrogramas) dividiendo la señal de audio original en ventanas con solapamiento (*overlap*), aplicando una función de ventaneo (Hann), ejecutando la FFT y exportando los resultados a formato CSV y consola.

---

## 🛠️ Arquitectura y Modos de Operación

### 1. Modo Local (Pipeline Concurrente)
- **Lectura de Audio**: Mono-hilo secuencial mediante `WavReader`.
- **Productor (`WavProducerTask`)**: Trocea la señal y encola los bloques en una `BlockingQueue` acotada (*backpressure*).
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

- **Cliente (`FFTClient`)**: Lee el WAV local, empaqueta las muestras y configuración, las transmite al servidor por TCP, recibe la matriz del espectrograma y la exporta a CSV.
- **Servidor simple (`FFTServer`)**: Atiende una petición con pool FFT local por conexión.

### 3. Modo Clúster Scatter-Gather (Load Balancer / Broker + N-Servidores)
Permite escalar horizontalmente distribuyendo las ventanas de tiempo entre múltiples servidores de cómputo:

```text
  [ Cliente 1 ]    [ Cliente 2 ] ... [ Cliente C ]
        │                │                 │
        └────────────────┼─────────────────┘
                         ▼ (TCP: 5000)
        ┌──────────────────────────────────┐
        │     LOAD BALANCER / BROKER       │
        │  - Registro de Servidores Activos│
        │  - Particionado Scatter          │
        │  - Ensamblaje Gather             │
        └────────────────┬─────────────────┘
                         │ (TCP Interno)
         ┌───────────────┼───────────────┐
         ▼               ▼               ▼
  [ Servidor FFT 1] [ Servidor FFT 2] [ Servidor FFT K]
   (localhost:5001)  (localhost:5002)  (localhost:5003)
```

- **Broker (`FFTLoadBalancer`)**: Divide los frames entre los $K$ servidores (**Scatter**), recolecta los resultados en paralelo (**Gather**) y los devuelve al cliente.
- **Nodos Backend (`WorkerNodeServer`)**: Procesan su porción asignada con pools de hilos locales y devuelven las magnitudes calculadas.
- **Simulador de Clúster (`ClusterBenchmarkRunner`)**: Evalúa el clúster con $K \in \{1,2,4\}$ y $C \in \{16,32\}$.

### 4. Modo de Objetos Distribuidos (Java RMI)
En esta arquitectura, el cliente ya no abre sockets de bajo nivel ni gestiona flujos binarios manualmente. En su lugar, utiliza Invocación de Métodos Remotos (**Java RMI**):

```text
  [ RMI Registry (Puerto 1099) ]
        ▲                  ▲
 (lookup)                 (bind)
        │                  │
 [ Cliente RMI ] ──────> [ Servidor RMI (UnicastRemoteObject) ]
   (Stub remoto)  (RMI)    (Calcula FFT con su Thread Pool local)
```

- **RMI Registry (`LocateRegistry`)**: Servicio de directorio que opera en el puerto 1099 donde el servidor registra el objeto remoto con la clave `"FFTService"`.
- **Interfaz Remota (`IFFTService`)**: Extiende `java.rmi.Remote` y declara las operaciones computacionales remotas.
- **Objeto Remoto (`FFTServiceImpl`)**: Extiende `UnicastRemoteObject` y ejecuta la FFT paralelizada usando su pool de hilos local (`ExecutorService`).
- **Cliente RMI (`RMIClient`)**: Obtiene el Stub desde el RMI Registry e invoca `computeSpectrogram(...)` de manera transparente como si fuera una librería local en memoria.

---

## 📁 Estructura del Proyecto

```text
src/main/java/
├── com/distribuidos/fft/rmi/
│   ├── IFFTService.java            # Interfaz remota RMI (extends Remote)
│   ├── FFTServiceImpl.java         # Objeto remoto (extends UnicastRemoteObject)
│   ├── RMIServer.java              # Servidor RMI (publica en RMI Registry puerto 1099)
│   └── RMIClient.java              # Cliente RMI (lookup de Stub e invocación remota)
│
└── escom/chris/
    ├── Main.java                   # Punto de entrada local
    ├── FFTPipelineManager.java     # Orquestador del pipeline en memoria/disco
    ├── BenchmarkRunner.java        # Ejecutor de pruebas de escalabilidad mono-nodo
    │
    ├── client/
    │   └── FFTClient.java          # Cliente TCP: transmisión WAV y visualización
    │
    ├── server/
    │   ├── FFTServer.java          # Servidor TCP monolítico
    │   ├── FFTLoadBalancer.java    # Broker / Load Balancer Scatter-Gather
    │   ├── WorkerNodeServer.java   # Servidor Worker backend para clúster
    │   └── ClusterBenchmarkRunner.java # Benchmark de escalabilidad horizontal
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
- **Apache Maven** (opcional, para construir con `pom.xml`).

### 1. Compilar todo el proyecto
Usando Maven:
```bash
mvn clean compile
```

O usando el compilador de Java directo:
```powershell
javac -d target/classes src/main/java/escom/chris/*.java src/main/java/escom/chris/audio/*.java src/main/java/escom/chris/concurrency/*.java src/main/java/escom/chris/math/*.java src/main/java/escom/chris/server/*.java src/main/java/escom/chris/client/*.java src/main/java/com/distribuidos/fft/rmi/*.java
```

---

### 2. Ejecutar en Modo Objetos Distribuidos (Java RMI)

#### Paso 1: Iniciar el Servidor RMI
```powershell
java -cp target/classes com.distribuidos.fft.rmi.RMIServer
```

#### Paso 2: Iniciar el Cliente RMI
En otra terminal:
```powershell
java -cp target/classes com.distribuidos.fft.rmi.RMIClient 127.0.0.1 1099 src/main/resources/audio.wav
```

---

### 3. Ejecutar en Modo Clúster Scatter-Gather (Broker + N-Workers)

#### Paso 1: Iniciar los Servidores Workers
En terminales independientes:
```powershell
java -cp target/classes escom.chris.server.WorkerNodeServer 5001 4
java -cp target/classes escom.chris.server.WorkerNodeServer 5002 4
```

#### Paso 2: Iniciar el Load Balancer / Broker
```powershell
java -cp target/classes escom.chris.server.FFTLoadBalancer 5000 localhost:5001 localhost:5002
```

#### Paso 3: Conectar el Cliente TCP
```powershell
java -cp target/classes escom.chris.client.FFTClient localhost 5000 src/main/resources/audio.wav 1024 512 cluster_output.csv
```

---

### 4. Pruebas Automatizadas de Escalabilidad Horizontal
Para ejecutar el experimento con $K \in \{1,2,4\}$ servidores y $C \in \{16,32\}$ clientes concurrentes reportado en [`Resultados_Cluster.md`](Resultados_Cluster.md):
```powershell
java -cp target/classes escom.chris.server.ClusterBenchmarkRunner "src/main/resources/audio.wav"
```

---

### 5. Modo Local (Pipeline Monolítico)
```powershell
java -cp target/classes escom.chris.Main "src/main/resources/audio.wav" 4 1024 512 "espectrograma.csv"
```
