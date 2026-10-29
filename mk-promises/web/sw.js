const CACHE_PREFIX = 'mk-promises-';
const CACHE = CACHE_PREFIX + 'v3-protocols';
const FILES = ['./', './index.html', './promise-scoring.js', './stance-model.js', './manifest.webmanifest', './icon-192.png', './icon-512.png'];

self.addEventListener('install', (e) => {
  e.waitUntil(caches.open(CACHE).then((c) => c.addAll(FILES)).then(() => self.skipWaiting()));
});

self.addEventListener('activate', (e) => {
  e.waitUntil(caches.keys()
    .then((ks) => Promise.all(ks.filter((k) => k.startsWith(CACHE_PREFIX) && k !== CACHE).map((k) => caches.delete(k))))
    .then(() => self.clients.claim()));
});

self.addEventListener('fetch', (e) => {
  const url = new URL(e.request.url);
  if (e.request.method !== 'GET' || url.origin !== location.origin) return;
  e.respondWith(
    fetch(e.request)
      .then((r) => {
        if (r.ok) {
          const copy = r.clone();
          e.waitUntil(caches.open(CACHE).then((c) => c.put(e.request, copy)).catch(() => {}));
        }
        return r;
      })
      .catch(async () => (await caches.match(e.request)) ||
        (e.request.mode === 'navigate' && await caches.match('./index.html')) || Response.error())
  );
});
