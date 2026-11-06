/**
 * Cliente HTTP de la PWA hacia el API Gateway (:8080) de DistriFourier.
 *
 * Se usa XMLHttpRequest para la subida del WAV porque es la única API del
 * navegador que reporta el progreso de subida (`xhr.upload.onprogress`),
 * y `fetch` para las consultas al Microservicio 4 (`/api/history`).
 */

export const SPECTROGRAM_PATH = '/api/fft/spectrogram';
export const HISTORY_PATH = '/api/history';

/**
 * Envía el WAV como multipart/form-data al API Gateway y devuelve el espectrograma normalizado.
 *
 * @param {object}   opts
 * @param {string}   opts.baseUrl     p.ej. "http://localhost:8080"
 * @param {File}     opts.file        archivo .wav
 * @param {number}   opts.windowSize
 * @param {number}   opts.hopSize
 * @param {(phase: 'upload'|'processing'|'download', fraction: number|null) => void} [opts.onProgress]
 * @returns {Promise<import('./storage.js').SpectrogramRecord>}
 */
export function requestSpectrogram({ baseUrl, file, windowSize, hopSize, onProgress = () => {} }) {
  return new Promise((resolve, reject) => {
    const form = new FormData();
    form.append('file', file, file.name);
    form.append('windowSize', String(windowSize));
    form.append('hopSize', String(hopSize));

    const xhr = new XMLHttpRequest();
    const startedAt = performance.now();
    xhr.open('POST', baseUrl.replace(/\/+$/, '') + SPECTROGRAM_PATH);
    xhr.responseType = 'json';

    // Fase 1: subida del archivo hacia API Gateway -> Ingestion Service
    xhr.upload.onprogress = (e) => onProgress('upload', e.lengthComputable ? e.loaded / e.total : null);
    // Fase 2: ingesta en MinIO + Scatter-Gather en FFT Workers + guardado en PostgreSQL
    xhr.upload.onload = () => onProgress('processing', null);
    // Fase 3: descarga de la matriz consolidada
    xhr.onprogress = (e) => onProgress('download', e.lengthComputable ? e.loaded / e.total : null);

    xhr.onload = () => {
      if (xhr.status !== 200) {
        const msg = xhr.response?.message || xhr.response?.error || `HTTP ${xhr.status}`;
        reject(new Error(`El backend respondió con error: ${msg}`));
        return;
      }
      try {
        resolve(toRecord(xhr.response, {
          name: file.name,
          roundTripMs: Math.round(performance.now() - startedAt),
          windowSize,
          hopSize,
        }));
      } catch (err) {
        reject(err);
      }
    };

    xhr.onerror = () => reject(new Error(
      'No se pudo conectar con el API Gateway. Verifica que esté corriendo en ' + baseUrl +
      ' y que el origen de esta página esté permitido por CORS.'));

    xhr.send(form);
  });
}

/**
 * Consulta la lista de análisis persistidos en PostgreSQL (service-storage) vía API Gateway.
 */
export async function fetchRemoteHistory(baseUrl) {
  const cleanUrl = baseUrl.replace(/\/+$/, '') + HISTORY_PATH;
  const res = await fetch(cleanUrl, { method: 'GET' });
  if (!res.ok) {
    throw new Error(`HTTP ${res.status}`);
  }
  return res.json();
}

/**
 * Descarga un espectrograma histórico desde service-storage sin recomputar la FFT.
 */
export async function fetchRemoteSpectrogram(baseUrl, spectrogramId, fileName = 'audio.wav') {
  const startedAt = performance.now();
  const cleanUrl = `${baseUrl.replace(/\/+$/, '')}${HISTORY_PATH}/${encodeURIComponent(spectrogramId)}`;
  const res = await fetch(cleanUrl, { method: 'GET' });
  if (!res.ok) {
    throw new Error(`No se pudo recuperar el espectrograma (${res.status})`);
  }
  const json = await res.json();
  return toRecord(json, {
    name: fileName,
    roundTripMs: Math.round(performance.now() - startedAt),
    windowSize: json.windowSize,
    hopSize: json.hopSize,
  });
}

/**
 * Elimina un espectrograma en service-storage (PostgreSQL).
 */
export async function deleteRemoteSpectrogram(baseUrl, spectrogramId) {
  const cleanUrl = `${baseUrl.replace(/\/+$/, '')}${HISTORY_PATH}/${encodeURIComponent(spectrogramId)}`;
  await fetch(cleanUrl, { method: 'DELETE' });
}

/**
 * Convierte la respuesta JSON ({frames, bins, magnitudes[frame][bin], ...}) al
 * formato interno: una única Float32Array plana en orden frame-major.
 */
function toRecord(json, { name, roundTripMs, windowSize, hopSize }) {
  if (!json || !Array.isArray(json.magnitudes)) {
    throw new Error('Respuesta inesperada del backend: falta "magnitudes"');
  }
  const { frames, bins, magnitudes } = json;
  const data = new Float32Array(frames * bins);
  for (let f = 0; f < frames; f++) {
    data.set(magnitudes[f], f * bins);
  }
  return {
    name,
    createdAt: Date.now(),
    frames,
    bins,
    data,
    sampleRate: json.sampleRate ?? null,
    windowSize: json.windowSize ?? windowSize,
    hopSize: json.hopSize ?? hopSize,
    computeMs: json.computeMs ?? null,
    roundTripMs,
    audioId: json.audioId ?? null,
    spectrogramId: json.spectrogramId ?? null,
  };
}
