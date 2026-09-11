/*
 * Trabajador de servicio de BoxVault.
 *
 * Está para dos cosas: que el navegador ofrezca instalar la aplicación en el
 * móvil, y que al abrirla arranque al momento aunque la cobertura sea mala.
 * No está para funcionar sin conexión: los datos —cobros, contratos, clientes—
 * no se guardan NUNCA aquí.
 *
 * Reglas, y por qué:
 *
 *   /api/**          no se toca. Son datos del negocio y van con la sesión en
 *                    cookies; guardarlos en el disco del móvil sería enseñarle
 *                    a quien entre después lo que vio el anterior. Se dejan
 *                    pasar sin mirarlos.
 *
 *   navegaciones     red primero, y si no hay red se enseña el index.html
 *                    guardado. Así se ve la aplicación (que ya pedirá sus datos
 *                    y avisará del fallo) en vez del dinosaurio del navegador.
 *
 *   /assets/**       caché primero. Vite les pone un hash en el nombre, así que
 *                    un fichero con un nombre dado no cambia jamás: si está
 *                    guardado, vale.
 *
 *   lo demás (GET)   red primero guardando copia; sirve la copia si no hay red.
 *
 * Una trampa del servidor: cualquier ruta desconocida devuelve index.html con
 * un 200. Por eso antes de guardar algo que no es una navegación se comprueba
 * que no sea HTML; si no, un .js que ya no existe se quedaría guardado como
 * página y rompería el arranque siguiente.
 */

// Al cambiar este número se tira toda la caché anterior. Hay que subirlo cuando
// cambie este fichero; los ficheros con hash no lo necesitan.
const VERSION = 'v1';
const SHELL = `boxvault-shell-${VERSION}`;
const ASSETS = `boxvault-assets-${VERSION}`;
const RUNTIME = `boxvault-runtime-${VERSION}`;
const VIGENTES = [SHELL, ASSETS, RUNTIME];

const INDEX = '/index.html';

self.addEventListener('install', (event) => {
  event.waitUntil(
    caches.open(SHELL)
      // `reload` salta la caché HTTP del navegador: al instalar interesa el
      // index recién publicado, no el que tuviera guardado de antes.
      .then((cache) => cache.add(new Request(INDEX, { cache: 'reload' })))
      // Si falla (sin red en ese momento) la instalación sigue adelante: el
      // index se guardará en la primera navegación con red.
      .catch(() => {})
      .then(() => self.skipWaiting())
  );
});

self.addEventListener('activate', (event) => {
  event.waitUntil(
    caches.keys()
      .then((nombres) => Promise.all(
        nombres.filter((n) => n.startsWith('boxvault-') && !VIGENTES.includes(n))
               .map((n) => caches.delete(n))
      ))
      .then(() => self.clients.claim())
  );
});

const esHtml = (respuesta) => (respuesta.headers.get('content-type') || '').includes('text/html');

/** ¿Se puede guardar? Sólo respuestas propias, completas y correctas. */
const guardable = (respuesta) => respuesta && respuesta.ok && respuesta.type === 'basic';

async function redPrimeroGuardando(request, nombreCache, { aceptarHtml = false } = {}) {
  try {
    const respuesta = await fetch(request);
    if (guardable(respuesta) && (aceptarHtml || !esHtml(respuesta))) {
      const copia = respuesta.clone();
      caches.open(nombreCache).then((cache) => cache.put(request, copia)).catch(() => {});
    }
    return respuesta;
  } catch (sinRed) {
    const guardada = await caches.match(request);
    if (guardada) return guardada;
    throw sinRed;
  }
}

self.addEventListener('fetch', (event) => {
  const { request } = event;
  if (request.method !== 'GET') return;

  const url = new URL(request.url);
  // Otro origen (el almacén de documentos, una fuente): que lo resuelva el navegador.
  if (url.origin !== self.location.origin) return;
  // Datos del negocio y sesión: no se tocan.
  if (url.pathname.startsWith('/api/')) return;

  if (request.mode === 'navigate') {
    event.respondWith(
      redPrimeroGuardando(new Request(INDEX, { credentials: 'same-origin' }), SHELL, { aceptarHtml: true })
        .catch(() => caches.match(INDEX, { cacheName: SHELL }))
        .then((respuesta) => respuesta || Response.error())
    );
    return;
  }

  if (url.pathname.startsWith('/assets/')) {
    event.respondWith(
      caches.match(request, { cacheName: ASSETS })
        .then((guardada) => guardada || redPrimeroGuardando(request, ASSETS))
    );
    return;
  }

  event.respondWith(redPrimeroGuardando(request, RUNTIME));
});
