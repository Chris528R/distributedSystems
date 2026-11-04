/**
 * Persistencia local de espectrogramas en IndexedDB.
 * Se conservan solo los MAX_ENTRIES más recientes para no llenar el disco;
 * así pueden volver a visualizarse sin red.
 *
 * @typedef {object} SpectrogramRecord
 * @property {number}       [id]         asignado por IndexedDB
 * @property {string}       name         nombre del archivo de audio
 * @property {number}       createdAt    epoch ms
 * @property {number}       frames
 * @property {number}       bins
 * @property {Float32Array} data         magnitudes en dB, frame-major (frames * bins)
 * @property {number|null}  sampleRate
 * @property {number}       windowSize
 * @property {number}       hopSize
 * @property {number|null}  computeMs
 * @property {number|null}  roundTripMs
 */

const DB_NAME = 'distrifourier';
const DB_VERSION = 1;
const STORE = 'spectrograms';
export const MAX_ENTRIES = 3;

let dbPromise = null;

function openDb() {
  if (!dbPromise) {
    dbPromise = new Promise((resolve, reject) => {
      const req = indexedDB.open(DB_NAME, DB_VERSION);
      req.onupgradeneeded = () => {
        const store = req.result.createObjectStore(STORE, { keyPath: 'id', autoIncrement: true });
        store.createIndex('createdAt', 'createdAt');
      };
      req.onsuccess = () => resolve(req.result);
      req.onerror = () => reject(req.error);
    });
  }
  return dbPromise;
}

/** Envuelve una IDBRequest en una promesa. */
function promisify(req) {
  return new Promise((resolve, reject) => {
    req.onsuccess = () => resolve(req.result);
    req.onerror = () => reject(req.error);
  });
}

async function withStore(mode, fn) {
  const db = await openDb();
  const tx = db.transaction(STORE, mode);
  // Se suscribe antes de operar para no perder el evento 'complete'.
  const done = new Promise((resolve, reject) => {
    tx.oncomplete = resolve;
    tx.onerror = () => reject(tx.error);
    tx.onabort = () => reject(tx.error);
  });
  const result = await fn(tx.objectStore(STORE));
  await done;
  return result;
}

/** Guarda un espectrograma y elimina los más antiguos. Devuelve el id asignado. */
export async function saveSpectrogram(record) {
  return withStore('readwrite', async (store) => {
    const id = await promisify(store.add(record));
    const keys = await promisify(store.index('createdAt').getAllKeys()); // orden ascendente
    for (const oldKey of keys.slice(0, Math.max(0, keys.length - MAX_ENTRIES))) {
      store.delete(oldKey);
    }
    return id;
  });
}

/** Lista los espectrogramas guardados, del más reciente al más antiguo. */
export async function listSpectrograms() {
  const all = await withStore('readonly', (store) => promisify(store.index('createdAt').getAll()));
  return all.reverse();
}

export async function getSpectrogram(id) {
  return withStore('readonly', (store) => promisify(store.get(id)));
}

export async function deleteSpectrogram(id) {
  return withStore('readwrite', (store) => promisify(store.delete(id)));
}
