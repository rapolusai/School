/*
 * Akshara service worker (docs/adr/0005-installable-parent-app.md).
 *
 * It caches only the static app shell: content-hashed scripts and styles under /_next/static/, the
 * icons, the manifest and the offline page. It never caches API responses (/api/*) or pages, which
 * may show a child's personal data; those always come from the network, and a page that cannot be
 * reached while offline shows /offline.html instead. No push, no background sync, no third parties.
 */

const VERSION = "akshara-shell-v1";
const OFFLINE_URL = "/offline.html";
const PRECACHE = [OFFLINE_URL, "/icons/icon-192.png", "/icons/icon-512.png", "/icons/maskable-512.png", "/icons/icon.svg"];
/** Old hashed files are trimmed beyond this many entries. */
const MAX_ENTRIES = 200;

self.addEventListener("install", (event) => {
  event.waitUntil(
    caches
      .open(VERSION)
      .then((cache) => cache.addAll(PRECACHE))
      .then(() => self.skipWaiting()),
  );
});

self.addEventListener("activate", (event) => {
  event.waitUntil(
    caches
      .keys()
      .then((keys) => Promise.all(keys.filter((key) => key.startsWith("akshara-") && key !== VERSION).map((key) => caches.delete(key))))
      .then(() => self.clients.claim()),
  );
});

/** Static files that hold no personal data and never change at the same URL. */
function isShellAsset(url) {
  return (
    url.pathname.startsWith("/_next/static/") ||
    url.pathname.startsWith("/icons/") ||
    url.pathname === "/manifest.webmanifest" ||
    url.pathname === OFFLINE_URL
  );
}

async function trim(cache) {
  const keys = await cache.keys();
  for (let i = 0; i < keys.length - MAX_ENTRIES; i++) await cache.delete(keys[i]);
}

async function cacheFirst(request) {
  const cache = await caches.open(VERSION);
  const hit = await cache.match(request);
  if (hit) return hit;
  const response = await fetch(request);
  if (response.ok && response.type === "basic") {
    await cache.put(request, response.clone());
    await trim(cache);
  }
  return response;
}

self.addEventListener("fetch", (event) => {
  const request = event.request;
  if (request.method !== "GET") return;
  const url = new URL(request.url);
  // Other origins (fonts) and the API are left entirely to the browser.
  if (url.origin !== self.location.origin || url.pathname.startsWith("/api/")) return;
  if (request.mode === "navigate") {
    // Pages are never cached: network only, with the offline page when there is no connection.
    event.respondWith(fetch(request).catch(() => caches.match(OFFLINE_URL)));
    return;
  }
  if (isShellAsset(url)) event.respondWith(cacheFirst(request));
});
