package com.distribuidos.fft.rmi;

import java.rmi.registry.LocateRegistry;
import java.rmi.registry.Registry;

public class RMIServer {
    public static void main(String[] args) {
        int rmiPort = 1099;
        int workerThreads = Runtime.getRuntime().availableProcessors();

        try {
            // 1. Crear el registro RMI en el puerto 1099
            Registry registry;
            try {
                registry = LocateRegistry.createRegistry(rmiPort);
                System.out.println("[RMI Server] Registro local creado en el puerto " + rmiPort);
            } catch (Exception e) {
                registry = LocateRegistry.getRegistry(rmiPort);
                System.out.println("[RMI Server] Conectado a registro RMI existente en el puerto " + rmiPort);
            }

            // 2. Instanciar y publicar el servicio
            IFFTService service = new FFTServiceImpl(workerThreads);
            registry.rebind("FFTService", service);

            System.out.println("[RMI Server] Objeto remoto 'FFTService' registrado y listo para recibir llamadas.");
        } catch (Exception e) {
            System.err.println("[RMI Server] Error inicializando servidor RMI: " + e.getMessage());
            e.printStackTrace();
        }
    }
}
