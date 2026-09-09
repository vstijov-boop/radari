/* Service worker: aplikacija radi bez interneta, pločice mape se čuvaju na telefonu. */
const SHELL = "radari-shell-v3";
const TILES = "radari-tiles-v1";

const SHELL_FILES = [
  "./", "./index.html", "./manifest.json",
  "./icon-192.png", "./icon-512.png", "./apple-touch-icon.png",
  "./slike/rk-1.jpg", "./slike/rk-2.jpg", "./slike/rk-3.jpg",
  "./slike/rk-11.jpg", "./slike/rk-18.jpg"
];

/* Hostovi sa kojih dolaze pločice podloge.
   api.mapbox.com nosi token u query stringu — keš ključ je cijeli URL, pa se
   pločice skinute s jednim tokenom ne nalaze ako se token kasnije promijeni. */
const TILE_HOST = /(^|\.)tile\.openstreetmap\.org$|(^|\.)arcgisonline\.com$|(^|\.)cartocdn\.com$|^api\.mapbox\.com$/;

self.addEventListener("install", e => {
  /* Svaki fajl posebno: da jedan koji fali ne obori cijelo kesiranje. */
  e.waitUntil((async () => {
    try {
      const c = await caches.open(SHELL);
      await Promise.allSettled(SHELL_FILES.map(f => c.add(f)));
    } catch (err) {}
    await self.skipWaiting();
  })());
});

self.addEventListener("activate", e => {
  e.waitUntil(
    caches.keys()
      .then(ks => Promise.all(ks.filter(k => k !== SHELL && k !== TILES).map(k => caches.delete(k))))
      .then(() => self.clients.claim())
  );
});

self.addEventListener("fetch", e => {
  const req = e.request;
  if (req.method !== "GET") return;

  let u;
  try { u = new URL(req.url); } catch (err) { return; }

  /* Pločice: prvo keš, pa mreža. Jednom skinuta pločica se više ne traži. */
  if (TILE_HOST.test(u.hostname)) {
    e.respondWith((async () => {
      const c = await caches.open(TILES);
      const hit = await c.match(req);
      if (hit) return hit;
      try {
        const res = await fetch(req);
        if (res && res.ok) c.put(req, res.clone());
        return res;
      } catch (err) {
        return new Response("", { status: 504, statusText: "offline" });
      }
    })());
    return;
  }

  /* Sama aplikacija: prvo mreža (da izmjene stignu odmah), keš kao rezerva. */
  if (u.origin === self.location.origin) {
    e.respondWith((async () => {
      try {
        const res = await fetch(req);
        if (res && res.ok) {
          const c = await caches.open(SHELL);
          c.put(req, res.clone());
        }
        return res;
      } catch (err) {
        const hit = await caches.match(req);
        return hit || await caches.match("./index.html") ||
          new Response("", { status: 504, statusText: "offline" });
      }
    })());
  }
});
