# Script de levantamiento y verificación local del clúster de microservicios DistriFourier (sin Docker)
$ErrorActionPreference = "Stop"

$javaCmd = "java"
if (Test-Path "C:\Program Files\Java\jdk-21.0.12\bin\java.exe") {
    $javaCmd = "C:\Program Files\Java\jdk-21.0.12\bin\java.exe"
}

Write-Host "=== 1. Iniciando los 5 nodos del clúster DistriFourier ===" -ForegroundColor Cyan

$procs = @()
try {
    $procs += Start-Process $javaCmd -ArgumentList "-jar","service-worker-fft\target\service-worker-fft-1.0-SNAPSHOT.jar","--server.port=8082" -PassThru -WindowStyle Minimized
    $procs += Start-Process $javaCmd -ArgumentList "-jar","service-worker-fft\target\service-worker-fft-1.0-SNAPSHOT.jar","--server.port=8083" -PassThru -WindowStyle Minimized
    $procs += Start-Process $javaCmd -ArgumentList "-jar","service-storage\target\service-storage-1.0-SNAPSHOT.jar","--server.port=8085","--spring.profiles.active=local" -PassThru -WindowStyle Minimized
    $procs += Start-Process $javaCmd -ArgumentList "-jar","service-ingestion\target\service-ingestion-1.0-SNAPSHOT.jar","--server.port=8081","--app.minio.enabled=false" -PassThru -WindowStyle Minimized
    $procs += Start-Process $javaCmd -ArgumentList "-jar","api-gateway\target\api-gateway-1.0-SNAPSHOT.jar","--server.port=8080" -PassThru -WindowStyle Minimized

    $services = @(
        @{ Name = "FFT Worker 1"; Port = 8082 },
        @{ Name = "FFT Worker 2"; Port = 8083 },
        @{ Name = "Spectro-Storage"; Port = 8085 },
        @{ Name = "Audio Ingestion"; Port = 8081 },
        @{ Name = "API Gateway"; Port = 8080 }
    )

    Write-Host "`nEsperando a que los servicios estén saludables (/actuator/health)..." -ForegroundColor Yellow
    foreach ($svc in $services) {
        $ready = $false
        for ($i = 0; $i -lt 40; $i++) {
            try {
                $health = Invoke-RestMethod -Uri "http://localhost:$($svc.Port)/actuator/health" -TimeoutSec 2
                if ($health.status -eq "UP") {
                    Write-Host ("  [OK] {0} (: {1}) -> UP" -f $svc.Name, $svc.Port) -ForegroundColor Green
                    $ready = $true
                    break
                }
            } catch {
                Start-Sleep -Milliseconds 500
            }
        }
        if (-not $ready) {
            throw "El servicio $($svc.Name) en el puerto $($svc.Port) no respondió a tiempo."
        }
    }

    Write-Host "`n=== 2. Enviando audio.wav al API Gateway (POST /api/fft/spectrogram) ===" -ForegroundColor Cyan
    $rawJson = & curl.exe -s -F "file=@core-fft/src/main/resources/audio.wav" -F "windowSize=1024" -F "hopSize=512" "http://localhost:8080/api/fft/spectrogram"
    $resp = $rawJson | ConvertFrom-Json
    Write-Host ("  [OK] Espectrograma calculado por el clúster:") -ForegroundColor Green
    Write-Host ("       Frames:        {0}" -f $resp.frames)
    Write-Host ("       Bins:          {0}" -f $resp.bins)
    Write-Host ("       Sample Rate:   {0} Hz" -f $resp.sampleRate)
    Write-Host ("       Tiempo Clúster:{0} ms" -f $resp.computeMs)
    Write-Host ("       Audio ID:      {0}" -f $resp.audioId)
    Write-Host ("       Spectrogram ID:{0}" -f $resp.spectrogramId)

    Write-Host "`n=== 3. Consultando Historial en Storage Service vía Gateway (GET /api/history) ===" -ForegroundColor Cyan
    $history = Invoke-RestMethod -Uri "http://localhost:8080/api/history"
    $history | Format-Table id, fileName, frames, bins, computeMs, createdAt -AutoSize

    Write-Host "`n=== ¡Todos los microservicios funcionan correctamente! ===" -ForegroundColor Green
    Read-Host "Presiona ENTER para detener los servicios locales (o déjalo abierto para probar la PWA en http://localhost:5173)"
} finally {
    Write-Host "Deteniendo procesos Java..." -ForegroundColor Yellow
    foreach ($p in $procs) {
        if ($p -and -not $p.HasExited) {
            Stop-Process -Id $p.Id -Force -ErrorAction SilentlyContinue
        }
    }
}

