// Production server on the Hetzner box, behind the Cloudflare Tunnel (see README): the
// web app built into dist/ plus the /api handlers. Responses are cached in memory by
// their Cache-Control, as Vercel's CDN did — reused for s-maxage, then served stale for
// stale-while-revalidate while one rebuild runs — so TiviMate rarely waits on a build,
// and concurrent misses never download the guides twice.
import epg from './api/epg'
import playlist from './api/playlist'
import proxy from './api/proxy'

type Handler = (req: Request) => Promise<Response>
type Entry = { body: Blob; init: ResponseInit; fresh: number; stale: number }

const routes: Record<string, Handler> = { '/api/epg': epg, '/api/playlist': playlist }
const cache = new Map<string, Entry>()
const building = new Map<string, Promise<Entry>>()

const ms = (cacheControl: string, directive: string) =>
  Number(cacheControl.match(new RegExp(`\\b${directive}=(\\d+)`))?.[1] ?? 0) * 1000

// One build per URL at a time; a response with an s-maxage is kept for the requests after it.
function build(url: string, req: Request, handler: Handler): Promise<Entry> {
  const running = building.get(url)
  if (running) return running
  const job = (async () => {
    const resp = await handler(req)
    const cc = resp.headers.get('cache-control') ?? ''
    const now = Date.now()
    const fresh = now + ms(cc, 's-maxage')
    const entry = { body: await resp.blob(), init: { status: resp.status, headers: resp.headers }, fresh, stale: fresh + ms(cc, 'stale-while-revalidate') }
    for (const [k, e] of cache) if (e.stale <= now) cache.delete(k)
    if (resp.ok && fresh > now) cache.set(url, entry)
    return entry
  })().finally(() => building.delete(url))
  building.set(url, job)
  return job
}

async function cached(req: Request, handler: Handler): Promise<Response> {
  const hit = cache.get(req.url)
  const now = Date.now()
  if (hit && now >= hit.fresh && now < hit.stale) build(req.url, req, handler).catch(() => {})
  const entry = hit && now < hit.stale ? hit : await build(req.url, req, handler)
  return new Response(entry.body, entry.init)
}

const dist = `${import.meta.dir}/dist`

Bun.serve({
  hostname: '127.0.0.1',
  port: Number(process.env.PORT ?? 8790),
  // A cold EPG build takes ~15s, and Bun drops a connection idle for 10s by default.
  idleTimeout: 255,
  async fetch(req) {
    const { pathname } = new URL(req.url)
    // The proxy relays files of any size, so it bypasses the cache: holding its responses
    // in memory would let anyone fill this process with GitHub files.
    if (pathname === '/api/proxy') return proxy(req)
    const handler = routes[pathname]
    if (handler) return cached(req, handler)
    const file = Bun.file(dist + (pathname === '/' ? '/index.html' : pathname))
    return (await file.exists()) ? new Response(file) : new Response('Not found', { status: 404 })
  },
})
