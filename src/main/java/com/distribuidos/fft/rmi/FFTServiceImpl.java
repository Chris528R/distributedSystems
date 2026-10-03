package com.distribuidos.fft.rmi;

import escom.chris.math.Complex;
import escom.chris.math.FastFourierTransform;
import escom.chris.math.WindowFunction;

import java.rmi.RemoteException;
import java.rmi.server.UnicastRemoteObject;
import java.util.concurrent.*;

public class FFTServiceImpl extends UnicastRemoteObject implements IFFTService {

    private final ExecutorService computePool;

    public FFTServiceImpl(int threads) throws RemoteException {
        super();
        this.computePool = Executors.newFixedThreadPool(threads);
    }

    @Override
    public double[][] computeSpectrogram(double[] samples, int windowSize, int hopSize) throws RemoteException {
        int totalFrames = (samples.length - windowSize) / hopSize + 1;
        int freqBins = windowSize / 2;
        double[][] spectrogram = new double[totalFrames][freqBins];

        CountDownLatch latch = new CountDownLatch(totalFrames);

        for (int frame = 0; frame < totalFrames; frame++) {
            final int frameIndex = frame;
            final int offset = frame * hopSize;

            computePool.submit(() -> {
                try {
                    double[] frameSamples = new double[windowSize];
                    System.arraycopy(samples, offset, frameSamples, 0, windowSize);

                    double[] windowed = WindowFunction.applyHann(frameSamples);
                    Complex[] complex = toComplex(windowed);
                    Complex[] spectrum = FastFourierTransform.fft(complex);

                    for (int k = 0; k < freqBins; k++) {
                        spectrogram[frameIndex][k] = spectrum[k].abs();
                    }
                } finally {
                    latch.countDown();
                }
            });
        }

        try {
            latch.await();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RemoteException("Cómputo interrumpido en el servidor", e);
        }

        return spectrogram;
    }

    @Override
    public double[][] computeChunkBatch(double[] samples, int startFrame, int count, int windowSize, int hopSize) throws RemoteException {
        // Implementación equivalente para sub-lotes si fuese requerida
        return computeSpectrogram(samples, windowSize, hopSize);
    }

    private Complex[] toComplex(double[] data) {
        Complex[] c = new Complex[data.length];
        for (int i = 0; i < data.length; i++) c[i] = new Complex(data[i], 0);
        return c;
    }
}
