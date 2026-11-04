/**
 * Cliente HTTP del backend DistriFourier.
 *
 * Se usa XMLHttpRequest (y no fetch) porque es la única API del navegador que
 * reporta el progreso de *subida*, necesario para la barra de progreso.
 */

export const SPECTROGRAM_PATH = '/api/fft/spectrogram';

/**
 * Envía el WAV como multipart/form-data y devuelve el espectrograma normalizado.
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

    // Fase 1: subida del archivo
    xhr.upload.onprogress = (e) => onProgress('upload', e.lengthComputable ? e.loaded / e.total : null);
    // Fase 2: el archivo llegó; el backend está calculando (duración desconocida)
    xhr.upload.onload = () => onProgress('processing', null);
    // Fase 3: descarga de la matriz de magnitudes
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

    // status 0: no hubo respuesta HTTP (servidor caído, CORS bloqueado o sin red)
    xhr.onerror = () => reject(new Error(
      'No se pudo conectar con el backend. Verifica que esté corriendo en ' + baseUrl +
      ' y que el origen de esta página esté permitido por CORS.'));

    xhr.send(form);
  });
}

/**
 * Convierte la respuesta JSON ({frames, bins, magnitudes[frame][bin], ...}) al
 * formato interno: una única Float32Array plana en orden frame-major. Ocupa la
 * mitad que double[] y se guarda tal cual en IndexedDB (structured clone).
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
  };
}
