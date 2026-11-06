package escom.chris.audio;

import escom.chris.FFTPipelineManager;
import escom.chris.concurrency.SharedResultMatrix;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;

class ScatterPartitionerTest {

    @Test
    void partitionedComputeMatchesMonolithicComputeBitForBit() throws Exception {
        int sampleCount = 15000;
        int windowSize = 1024;
        int hopSize = 512;

        double[] samples = new double[sampleCount];
        for (int i = 0; i < sampleCount; i++) {
            samples[i] = Math.sin(2.0 * Math.PI * 440.0 * i / 44100.0)
                    + 0.5 * Math.cos(2.0 * Math.PI * 1200.0 * i / 44100.0);
        }

        SharedResultMatrix monolithic = FFTPipelineManager.compute(samples, windowSize, hopSize, 2);
        double[][] expected = monolithic.copyAsFrameMajor();

        for (int numWorkers : new int[]{1, 2, 3, 4}) {
            List<ScatterPartitioner.PartitionSlice> slices =
                    ScatterPartitioner.partition(samples, windowSize, hopSize, numWorkers);

            double[][] assembled = new double[expected.length][expected[0].length];
            int totalFramesFromSlices = 0;

            for (ScatterPartitioner.PartitionSlice slice : slices) {
                SharedResultMatrix workerMatrix =
                        FFTPipelineManager.compute(slice.subSamples(), windowSize, hopSize, 2);
                double[][] workerFrames = workerMatrix.copyAsFrameMajor();

                assertEquals(slice.frameCount(), workerFrames.length,
                        "El worker debe producir exactamente frameCount ventanas");

                for (int f = 0; f < workerFrames.length; f++) {
                    assembled[slice.startFrame() + f] = workerFrames[f];
                }
                totalFramesFromSlices += workerFrames.length;
            }

            assertEquals(expected.length, totalFramesFromSlices);
            for (int f = 0; f < expected.length; f++) {
                assertArrayEquals(expected[f], assembled[f], 1e-9,
                        "Diferencia en frame " + f + " con numWorkers=" + numWorkers);
            }
        }
    }
}

