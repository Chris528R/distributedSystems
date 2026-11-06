/**
 * Punto de entrada de la PWA: conecta la UI con el API Gateway (:8080),
 * el renderizador, el almacenamiento local (IndexedDB) y el Microservicio 4
 * de historial remoto (PostgreSQL / service-storage).
 */
import {
  requestSpectrogram,
  fetchRemoteHistory,
  fetchRemoteSpectrogram,
  deleteRemoteSpectrogram,
} from './api.js';
import { drawColorbar } from './colormap.js';
import { SpectrogramView } from './spectrogram.js';
import { saveSpectrogram, listSpectrograms, getSpectrogram, deleteSpectrogram } from './storage.js';

const $ = (id) => document.getElementById(id);

const els = {
  form: $('fft-form'),
  dropzone: $('dropzone'),
  fileInput: $('file-input'),
  fileInfo: $('file-info'),
  windowSize: $('window-size'),
  hopSize: $('hop-size'),
  apiUrl: $('api-url'),
  submit: $('submit-btn'),
  progress: $('progress'),
  progressBar: $('progress-bar'),
  progressLabel: $('progress-label'),
  error: $('error-msg'),
  history: $('history'),
  historyEmpty: $('history-empty'),
  viewerTitle: $('viewer-title'),
  stats: $('stats'),
  placeholder: $('placeholder'),
  dbRange: $('db-range'),
  legendMin: $('legend-min'),
  legendMax: $('legend-max'),
  netStatus: $('net-status'),
  installBtn: $('install-btn'),
};

/** Límite del backend (spring.servlet.multipart.max-file-size = 50MB). */
const MAX_UPLOAD_BYTES = 50 * 1024 * 1024;
const SETTINGS_KEY = 'distrifourier.settings';

let selectedFile = null;
let activeId = null;

const view = new SpectrogramView({
  wrap: $('canvas-wrap'),
  canvas: $('spectrogram'),
  overlay: $('overlay'),
  tooltip: $('tooltip'),
});

// ----------------------------------------------------------------------
// Preferencias (localStorage)
// ----------------------------------------------------------------------

function loadSettings() {
  try {
    const s = JSON.parse(localStorage.getItem(SETTINGS_KEY) || '{}');
    if (s.apiUrl) els.apiUrl.value = s.apiUrl;
    if (s.windowSize) els.windowSize.value = s.windowSize;
    if (s.hopSize) els.hopSize.value = s.hopSize;
    if (s.dbRange) els.dbRange.value = s.dbRange;
  } catch { /* valores por defecto */ }
  els.hopSize.max = els.windowSize.value;
}

function saveSettings() {
  try {
    localStorage.setItem(SETTINGS_KEY, JSON.stringify({
      apiUrl: els.apiUrl.value.trim(),
      windowSize: els.windowSize.value,
      hopSize: els.hopSize.value,
      dbRange: els.dbRange.value,
    }));
  } catch { /* ignorado */ }
}

// ----------------------------------------------------------------------
// Selección de archivo (botón + drag & drop)
// ----------------------------------------------------------------------

function isWav(file) {
  return /\.wav$/i.test(file.name) || /audio\/(x-)?wav/.test(file.type);
}

function selectFile(file) {
  hideError();
  if (!file) return;
  if (!isWav(file)) {
    showError(`"${file.name}" no es un archivo .wav`);
    return;
  }
  if (file.size > MAX_UPLOAD_BYTES) {
    showError(`El archivo pesa ${formatBytes(file.size)}; el límite es ${formatBytes(MAX_UPLOAD_BYTES)}.`);
    return;
  }
  selectedFile = file;
  els.fileInfo.textContent = `${file.name} · ${formatBytes(file.size)}`;
  els.fileInfo.hidden = false;
  els.submit.disabled = false;
}

els.fileInput.addEventListener('change', () => selectFile(els.fileInput.files[0]));

['dragenter', 'dragover'].forEach((type) =>
  els.dropzone.addEventListener(type, (e) => {
    e.preventDefault();
    els.dropzone.classList.add('dragover');
  }));
['dragleave', 'drop'].forEach((type) =>
  els.dropzone.addEventListener(type, (e) => {
    e.preventDefault();
    els.dropzone.classList.remove('dragover');
  }));
els.dropzone.addEventListener('drop', (e) => selectFile(e.dataTransfer.files[0]));

window.addEventListener('dragover', (e) => e.preventDefault());
window.addEventListener('drop', (e) => e.preventDefault());

// ----------------------------------------------------------------------
// Parámetros
// ----------------------------------------------------------------------

els.windowSize.addEventListener('change', () => {
  const w = Number(els.windowSize.value);
  els.hopSize.max = w;
  els.hopSize.value = w / 2;
  saveSettings();
});
els.hopSize.addEventListener('change', saveSettings);
els.apiUrl.addEventListener('change', () => {
  saveSettings();
  renderHistory();
});

els.dbRange.addEventListener('input', () => {
  view.setDynamicRange(Number(els.dbRange.value));
  updateLegend();
  saveSettings();
});

// ----------------------------------------------------------------------
// Envío al API Gateway (:8080)
// ----------------------------------------------------------------------

els.form.addEventListener('submit', async (e) => {
  e.preventDefault();
  if (!selectedFile) return;

  const windowSize = Number(els.windowSize.value);
  const hopSize = Number(els.hopSize.value);
  if (!Number.isInteger(hopSize) || hopSize < 1 || hopSize > windowSize) {
    showError(`hopSize debe ser un entero entre 1 y ${windowSize}.`);
    return;
  }

  hideError();
  setBusy(true);
  try {
    const record = await requestSpectrogram({
      baseUrl: els.apiUrl.value.trim(),
      file: selectedFile,
      windowSize,
      hopSize,
      onProgress: updateProgress,
    });
    showRecord(record);
    try {
      activeId = await saveSpectrogram(record);
      record.id = activeId;
    } catch (err) {
      console.warn('No se pudo guardar en IndexedDB:', err);
    }
    await renderHistory();
  } catch (err) {
    showError(navigator.onLine ? err.message : 'Sin conexión: ' + err.message);
  } finally {
    setBusy(false);
  }
});

function updateProgress(phase, fraction) {
  const indeterminate = fraction === null || phase === 'processing';
  els.progress.classList.toggle('indeterminate', indeterminate);
  els.progressBar.style.width = indeterminate ? '' : `${Math.round(fraction * 100)}%`;
  els.progressLabel.textContent = {
    upload: `Subiendo audio al API Gateway… ${indeterminate ? '' : Math.round(fraction * 100) + ' %'}`,
    processing: 'Ingestando en MinIO y calculando FFT en clúster de Workers…',
    download: `Recibiendo matriz consolidada… ${indeterminate ? '' : Math.round(fraction * 100) + ' %'}`,
  }[phase];
}

function setBusy(busy) {
  els.submit.disabled = busy || !selectedFile;
  els.submit.textContent = busy ? 'Procesando…' : 'Calcular espectrograma';
  els.progress.hidden = !busy;
  if (busy) updateProgress('upload', 0);
}

// ----------------------------------------------------------------------
// Visualización
// ----------------------------------------------------------------------

function showRecord(record) {
  els.placeholder.hidden = true;
  els.viewerTitle.textContent = record.name;
  view.setData(record);
  updateLegend();

  const stats = [
    ['frames', record.frames],
    ['bins', record.bins],
    ['win/hop', `${record.windowSize}/${record.hopSize}`],
  ];
  if (record.sampleRate) {
    stats.push(['fs', `${record.sampleRate} Hz`]);
    stats.push(['Δf', `${(record.sampleRate / record.windowSize).toFixed(1)} Hz`]);
  }
  if (record.computeMs != null) stats.push(['workers', `${record.computeMs} ms`]);
  if (record.roundTripMs != null) stats.push(['total', `${record.roundTripMs} ms`]);
  if (record.audioId) stats.push(['s3', record.audioId.slice(0, 8)]);

  els.stats.replaceChildren(...stats.map(([k, v]) => {
    const div = document.createElement('div');
    const dt = document.createElement('dt');
    const dd = document.createElement('dd');
    dt.textContent = k;
    dd.textContent = v;
    div.append(dt, dd);
    return div;
  }));
}

function updateLegend() {
  if (!view.record) return;
  els.legendMin.textContent = `${view.minDb.toFixed(0)} dB`;
  els.legendMax.textContent = `${view.maxDb.toFixed(0)} dB`;
}

// ----------------------------------------------------------------------
// Historial Híbrido (IndexedDB Offline + PostgreSQL service-storage)
// ----------------------------------------------------------------------

async function renderHistory() {
  let localItems = [];
  try {
    localItems = await listSpectrograms();
  } catch (err) {
    console.warn('IndexedDB no disponible:', err);
  }

  let remoteItems = [];
  if (navigator.onLine) {
    try {
      remoteItems = await fetchRemoteHistory(els.apiUrl.value.trim());
    } catch {
      // Si el backend no está disponible, se muestra únicamente IndexedDB
    }
  }

  // Combinar evitando duplicados por spectrogramId
  const localSpecIds = new Set(localItems.map((i) => i.spectrogramId).filter(Boolean));
  const combined = [
    ...localItems.map((item) => ({
      key: `local-${item.id}`,
      localId: item.id,
      spectrogramId: item.spectrogramId || null,
      name: item.name,
      frames: item.frames,
      bins: item.bins,
      createdAt: item.createdAt,
      source: item.spectrogramId ? 'local+db' : 'local',
    })),
    ...remoteItems
      .filter((r) => !localSpecIds.has(r.id))
      .map((r) => ({
        key: `remote-${r.id}`,
        localId: null,
        spectrogramId: r.id,
        name: r.fileName,
        frames: r.frames,
        bins: r.bins,
        createdAt: r.createdAt,
        source: 'db',
      })),
  ];

  els.historyEmpty.hidden = combined.length > 0;
  els.history.replaceChildren(...combined.map((item) => {
    const li = document.createElement('li');
    const isActive = (item.localId != null && item.localId === activeId)
      || (item.spectrogramId != null && item.spectrogramId === activeId);
    li.classList.toggle('active', isActive);
    li.title = item.localId != null
      ? 'Abrir desde caché local (IndexedDB)'
      : 'Recuperar de PostgreSQL (service-storage) sin recomputar';

    const main = document.createElement('div');
    main.className = 'h-main';
    const name = document.createElement('div');
    name.className = 'h-name';
    name.textContent = item.name;
    const meta = document.createElement('div');
    meta.className = 'h-meta';
    const badge = item.localId != null ? '⚡ local' : '🗄️ db';
    meta.textContent = `${badge} · ${item.frames}×${item.bins} · ${new Date(item.createdAt).toLocaleString()}`;
    main.append(name, meta);

    const del = document.createElement('button');
    del.className = 'h-del';
    del.type = 'button';
    del.textContent = '×';
    del.title = 'Eliminar del historial';
    del.addEventListener('click', async (e) => {
      e.stopPropagation();
      if (item.localId != null) {
        await deleteSpectrogram(item.localId);
      }
      if (item.spectrogramId && navigator.onLine) {
        try {
          await deleteRemoteSpectrogram(els.apiUrl.value.trim(), item.spectrogramId);
        } catch { /* ignorado */ }
      }
      await renderHistory();
    });

    li.append(main, del);
    li.addEventListener('click', async () => {
      hideError();
      if (item.localId != null) {
        const record = await getSpectrogram(item.localId);
        if (!record) return;
        activeId = record.id;
        showRecord(record);
        await renderHistory();
      } else if (item.spectrogramId) {
        try {
          const record = await fetchRemoteSpectrogram(
            els.apiUrl.value.trim(),
            item.spectrogramId,
            item.name,
          );
          activeId = item.spectrogramId;
          showRecord(record);
          await renderHistory();
        } catch (err) {
          showError(err.message);
        }
      }
    });
    return li;
  }));
}

// ----------------------------------------------------------------------
// Errores y utilidades
// ----------------------------------------------------------------------

function showError(msg) {
  els.error.textContent = msg;
  els.error.hidden = false;
}

function hideError() {
  els.error.hidden = true;
}

function formatBytes(n) {
  if (n < 1024) return `${n} B`;
  if (n < 1024 ** 2) return `${(n / 1024).toFixed(1)} KB`;
  return `${(n / 1024 ** 2).toFixed(1)} MB`;
}

// ----------------------------------------------------------------------
// PWA: Service Worker, instalación y estado de red
// ----------------------------------------------------------------------

if ('serviceWorker' in navigator) {
  window.addEventListener('load', () => {
    navigator.serviceWorker.register('./sw.js').catch((err) =>
      console.warn('No se pudo registrar el Service Worker:', err));
  });
}

let deferredInstall = null;
window.addEventListener('beforeinstallprompt', (e) => {
  e.preventDefault();
  deferredInstall = e;
  els.installBtn.hidden = false;
});
els.installBtn.addEventListener('click', async () => {
  if (!deferredInstall) return;
  deferredInstall.prompt();
  await deferredInstall.userChoice;
  deferredInstall = null;
  els.installBtn.hidden = true;
});
window.addEventListener('appinstalled', () => { els.installBtn.hidden = true; });

function updateNetStatus() {
  const online = navigator.onLine;
  els.netStatus.textContent = online ? 'online' : 'offline';
  els.netStatus.classList.toggle('offline', !online);
  if (online) renderHistory();
}
window.addEventListener('online', updateNetStatus);
window.addEventListener('offline', updateNetStatus);

// ----------------------------------------------------------------------
// Arranque
// ----------------------------------------------------------------------

loadSettings();
view.setDynamicRange(Number(els.dbRange.value));
drawColorbar($('colorbar'));
updateNetStatus();
renderHistory();
