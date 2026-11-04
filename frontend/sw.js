/**
 * Service Worker de DistriFourier.
 *
 * Estrategia:
 *  - App Shell (HTML/CSS/JS/iconos): precache en `install` y cache-first,
 *    revalidando en segundo plano (stale-while-revalidate) para recoger cambios.
 *  - Peticiones a la API (/api/...) y cualquier petición no-GET: siempre red.
 *    Los resultados offline se sirven desde IndexedDB (ver js/storage.js), no desde aquí.
 *
 * Al modificar algún archivo del shell, incrementa CACHE_VERSION para forzar
 * que los clientes descarguen la versión nueva y se borren las cachés antiguas.
 */
const CACHE_VERSION = 'v3';
const CACHE_NAME = `distrifourier-shell-${CACHE_VERSION}`;

const APP_SHELL = [
  './',
  './index.html',
  './styles.css',
  './manifest.json',
  './js/app.js',
  './js/api.js',
  './js/colormap.js',
  './js/spectrogram.js',
  './js/storage.js',
  './icons/icon.svg',
  './icons/icon-192.png',
  './icons/icon-512.png',
  './icons/icon-maskable-512.png',
];

self.addEventListener('install', (event) => {
  event.waitUntil(
    caches.open(CACHE_NAME)
      .then((cache) => cache.addAll(APP_SHELL))
      .then(() => self.skipWaiting()),
  );
});

self.addEventListener('activate', (event) => {
  event.waitUntil(
    caches.keys()
      .then((keys) => Promise.all(
        keys
          .filter((k) => k.startsWith('distrifourier-shell-') && k !== CACHE_NAME)
          .map((k) => caches.delete(k)),
      ))
      .then(() => self.clients.claim()),
  );
});

self.addEventListener('fetch', (event) => {
  const { request } = event;
  const url = new URL(request.url);

  // Solo GET del mismo origen pertenece al App Shell; lo demás va directo a la red.
  if (request.method !== 'GET' || url.origin !== self.location.origin || url.pathname.includes('/api/')) {
    return;
  }

  event.respondWith(staleWhileRevalidate(request));
});

async function staleWhileRevalidate(request) {
  const cache = await caches.open(CACHE_NAME);
  const cached = await cache.match(request, { ignoreSearch: true });

  const network = fetch(request)
    .then((response) => {
      if (response.ok) cache.put(request, response.clone());
      return response;
    })
    .catch(() => undefined);

  if (cached) return cached;

  const fresh = await network;
  if (fresh) return fresh;

  // Sin red y sin caché: para navegaciones, devolver el shell.
  if (request.mode === 'navigate') {
    const shell = await cache.match('./index.html');
    if (shell) return shell;
  }
  return new Response('Sin conexión', { status: 503, statusText: 'Offline' });
}
