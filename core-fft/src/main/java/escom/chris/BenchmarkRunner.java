package escom.chris;

import java.util.ArrayList;
import java.util.List;

/**
 * Ejecutor de pruebas de rendimiento y escalabilidad (Speedup, Eficiencia, Overhead).
 */
public final class BenchmarkRunner {

    public static void main(String[] args) throws Exception {
        String wavPath = args.length > 0 ? args[0] : "src/main/resources/audio.wav";
        int[] threadConfigs = {1, 2, 4, 8, 16};
        int windowSize = 1024;
        int hopSize = 512;
        int warmupRuns = 5;
        int benchmarkRuns = 10;

        System.out.println("===============================================================================");
        System.out.println("                   INICIANDO BENCHMARK DE ESCALABILIDAD FFT                    ");
        System.out.println("===============================================================================");
        System.out.printf("Archivo: %s%n", wavPath);
        System.out.printf("Configuraciones de hilos (p): [1, 2, 4, 8, 16]%n");
        System.out.printf("Warmup: %d iteraciones | Muestras por test: %d iteraciones%n%n", warmupRuns, benchmarkRuns);

        // Fase 1: Calentamiento de la JVM (Warmup JIT)
        System.out.print("Ejecutando fase de calentamiento (Warmup)... ");
        for (int p : threadConfigs) {
            for (int i = 0; i < warmupRuns; i++) {
                FFTPipelineManager manager = new FFTPipelineManager(wavPath, p, windowSize, hopSize, null);
                manager.execute(false, false);
            }
        }
        System.out.println("¡Completado!\n");

        // Fase 2: Medición de Rendimiento
        List<BenchmarkResult> results = new ArrayList<>();
        double t1Compute = 0.0;
        double t1Total = 0.0;

        for (int p : threadConfigs) {
            System.out.printf("Evaluando p = %2d hilos... ", p);
            double sumCompute = 0.0;
            double sumTotal = 0.0;
            double sumRead = 0.0;
            double sumExport = 0.0;
            List<Double> computeTimes = new ArrayList<>();

            for (int i = 0; i < benchmarkRuns; i++) {
                String tempCsv = "temp_bench_" + p + ".csv";
                FFTPipelineManager manager = new FFTPipelineManager(wavPath, p, windowSize, hopSize, tempCsv);
                FFTPipelineManager.PipelineMetrics metrics = manager.execute(false, true);

                sumCompute += metrics.computeTimeMs();
                sumTotal += metrics.totalTimeMs();
                sumRead += metrics.readTimeMs();
                sumExport += metrics.exportTimeMs();
                computeTimes.add(metrics.computeTimeMs());
            }

            double avgCompute = sumCompute / benchmarkRuns;
            double avgTotal = sumTotal / benchmarkRuns;
            double avgRead = sumRead / benchmarkRuns;
            double avgExport = sumExport / benchmarkRuns;

            // Calcular desviación estándar
            double variance = 0.0;
            for (double t : computeTimes) {
                variance += Math.pow(t - avgCompute, 2);
            }
            double stdDev = Math.sqrt(variance / benchmarkRuns);

            if (p == 1) {
                t1Compute = avgCompute;
                t1Total = avgTotal;
            }

            double speedupCompute = t1Compute / avgCompute;
            double efficiencyCompute = speedupCompute / p;

            double speedupTotal = t1Total / avgTotal;
            double efficiencyTotal = speedupTotal / p;

            double throughput = 257.0 / (avgCompute / 1000.0);

            BenchmarkResult res = new BenchmarkResult(
                    p, avgCompute, stdDev, speedupCompute, efficiencyCompute,
                    avgRead, avgExport, avgTotal, speedupTotal, efficiencyTotal, throughput
            );
            results.add(res);
            System.out.printf("T_p: %.2f ms | Speedup: %.2fx | Eficiencia: %.1f%%%n",
                    avgCompute, speedupCompute, efficiencyCompute * 100);
        }

        // Imprimir Tabla Resumen de Cómputo
        System.out.println("\n==========================================================================================");
        System.out.println("                          RESULTADOS: TIEMPO DE CÓMPUTO (Tp)                              ");
        System.out.println("==========================================================================================");
        System.out.printf("%-6s | %-12s | %-10s | %-12s | %-12s | %-16s%n",
                "Hilos", "Tp Prom (ms)", "Desv Est", "Speedup (Sp)", "Eficiencia", "Throughput (chk/s)");
        System.out.println("------------------------------------------------------------------------------------------");
        for (BenchmarkResult r : results) {
            System.out.printf("%-6d | %12.2f | %10.2f | %12.2fx | %11.1f%% | %16.1f%n",
                    r.p, r.avgCompute, r.stdDevCompute, r.speedupCompute, r.efficiencyCompute * 100, r.throughput);
        }
        System.out.println("==========================================================================================\n");

        // Imprimir Tabla Resumen Pipeline Completo
        System.out.println("==========================================================================================");
        System.out.println("                      RESULTADOS: PIPELINE COMPLETO (I/O + Cómputo)                       ");
        System.out.println("==========================================================================================");
        System.out.printf("%-6s | %-10s | %-12s | %-10s | %-12s | %-12s | %-10s%n",
                "Hilos", "Read (ms)", "Compute (ms)", "Export(ms)", "Total (ms)", "Speedup Tot", "Efic Tot");
        System.out.println("------------------------------------------------------------------------------------------");
        for (BenchmarkResult r : results) {
            System.out.printf("%-6d | %10.2f | %12.2f | %10.2f | %12.2f | %11.2fx | %9.1f%%%n",
                    r.p, r.avgRead, r.avgCompute, r.avgExport, r.avgTotal, r.speedupTotal, r.efficiencyTotal * 100);
        }
        System.out.println("==========================================================================================");
    }

    public record BenchmarkResult(
            int p,
            double avgCompute,
            double stdDevCompute,
            double speedupCompute,
            double efficiencyCompute,
            double avgRead,
            double avgExport,
            double avgTotal,
            double speedupTotal,
            double efficiencyTotal,
            double throughput
    ) {}
}
