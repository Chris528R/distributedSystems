package com.distribuidos.fft.rmi;

import java.rmi.Remote;
import java.rmi.RemoteException;

public interface IFFTService extends Remote {

    /**
     * Procesa un lote o señal de audio completa y retorna la matriz de magnitudes.
     */
    double[][] computeSpectrogram(double[] samples, int windowSize, int hopSize)
            throws RemoteException;

    /**
     * Opcional: Procesa un sub-rango de frames (útil si reutilizas la lógica de nodos worker).
     */
    double[][] computeChunkBatch(double[] samples, int startFrame, int count, int windowSize, int hopSize)
            throws RemoteException;
}
