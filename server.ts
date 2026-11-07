// Production server on the Hetzner box, behind the Cloudflare Tunnel (see README): the
// /api handlers. Responses are cached in memory by their Cache-Control, as Vercel's CDN
// did — reused for s-maxage, then served stale for stale-while-revalidate while one
// rebuild runs — so TiviMate rarely waits on a build, and concurrent misses never
// download the guides twice.
import * as app from './api/app'
import epg from './api/epg'
import playlist from './api/playlist'
import { accessToken } from './api/_trakt'

type Handler = (req: Request) => Promise<Response>
type Entry = { body: Blob; init: ResponseInit; fresh: number; stale: number }

const routes: Record<string, Handler> = { '/api/epg': epg, '/api/playlist': playlist, '/api/app/channels': app.channels, '/api/app/guide': app.guide, '/api/app/movies': app.movies, '/api/app/shows': app.shows, '/api/app/episodes': app.episodes, '/api/app/movie': app.movie, '/api/app/vod-search': app.vodSearch, '/api/app/copies': app.copies, '/api/app/title': app.title, '/api/app/search': app.search, '/api/app/person': app.person, '/api/app/subtitles': app.subtitles, '/api/app/subtitle': app.subtitle }
const cache = new Map<string, Entry>()
const building = new Map<string, Promise<Entry>>()

/** Forgets the kept answers whose URL [drop] matches. */
const forget = (drop: (u: URL) => boolean) => { for (const url of cache.keys()) if (drop(new URL(url))) cache.delete(url) }

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

export async function handle(req: Request): Promise<Response> {
  const { pathname } = new URL(req.url)
  // A new build must show at once, and the APK is too big to hold in memory.
  if (pathname === '/api/app/version') return app.version(req)
  if (pathname === '/api/app/apk') return app.apk(req)
  if (pathname === '/api/app/dm') return app.dm(req)
  // Answers from its own memory, with ETag/304.
  if (pathname === '/api/app/library') return app.library(req)
  if (pathname === '/api/app/history') return app.history(req)
  if (pathname === '/api/app/quality' && req.method === 'POST') {
    const res = await app.quality(req)
    // A stored reading can reorder feeds, so the channel lists rebuild on their next request.
    if (res.ok && (await res.clone().json()).stored)
      for (const url of cache.keys()) if (/^\/api\/(app\/channels|playlist)$/.test(new URL(url).pathname)) cache.delete(url)
    return res
  }
  if (pathname === '/api/app/bitrate' && req.method === 'POST') return app.bitrate(req)
  if (pathname === '/api/app/film-quality' && req.method === 'POST') {
    const res = await app.filmQuality(req)
    // A new reading changes that film's info and copies: their cached answers go.
    const body = res.ok ? await res.clone().json() : null
    if (body?.stored) forget((u) => (u.pathname === '/api/app/movie' && u.searchParams.get('id') === String(body.id)) || u.pathname === '/api/app/copies')
    return res
  }
  if (pathname === '/api/app/events' && req.method === 'POST') return app.events(req)
  if (pathname === '/api/app/trakt') return app.traktSignIn(req)
  if (pathname === '/api/app/trakt-history' && req.method === 'POST') return app.traktHistory(req)
  if (pathname === '/api/app/watchlist' && req.method === 'POST') return app.watchlist(req)
  if (pathname === '/api/app/mark' && req.method === 'POST') return app.mark(req)
  if (pathname === '/api/app/title-ids' && req.method === 'POST') return app.titleIds(req)
  if (pathname === '/api/app/rd-infringing' && req.method === 'POST') {
    const res = await app.rdInfringing(req)
    // A refused torrent leaves every title's copies at once.
    if (res.ok && (await res.clone().json()).stored) forget((u) => u.pathname === '/api/app/copies')
    return res
  }
  const handler = routes[pathname]
  return handler ? cached(req, handler) : new Response('Not found', { status: 404 })
}

if (import.meta.main) {
  // Keeps a Trakt sign-in alive across restarts: reading the token schedules its refresh.
  accessToken().catch(() => {})
  Bun.serve({
    hostname: '127.0.0.1',
    port: Number(process.env.PORT ?? 8790),
    // A cold EPG build takes ~15s, and Bun drops a connection idle for 10s by default.
    idleTimeout: 255,
    fetch: handle,
  })
}
