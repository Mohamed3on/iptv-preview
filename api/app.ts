// The Fire TV app's endpoints, gated by APP_TOKENS (never PLAYLIST_TOKEN) and private:
// no CORS header, and only the server's own cache may keep a response.
// GET /api/app/channels?t=TOKEN — groups, channels, feed families, badges and both provider fronts.
// GET /api/app/guide?t=TOKEN — the guide as a gzip SQLite file the stick opens without parsing.
// GET /api/app/movies?t=TOKEN — the on-demand library: 4K movies, UFC replays and Blu-ray films.
// GET /api/app/vod-search?t=TOKEN&q=… — films across the provider's whole catalogue.
// GET /api/app/version, /api/app/apk, /api/app/dm ?t=TOKEN — the latest build and its dex metadata (the
// baseline profile, compiled at install), copied to the box by firetv.yml.
// POST /api/app/quality?t=TOKEN — a quality reading the app measured while playing.
// POST /api/app/bitrate?t=TOKEN — a feed's bitrate the app measured while playing.
// POST /api/app/events?t=TOKEN — what happened while watching, for later analysis.
import { appendFile, mkdir, rename, stat } from 'node:fs/promises'
import { dirname } from 'node:path'
import { buildAppFeed, buildAppGuide } from './_app-feed.js'
import { appTokenOk, backupConfigFromEnv, configFromEnv, fetchCuratedChannels, saneFps, savedVodChannels, withBackupFeeds, withCompetitionGroups } from './_lib.js'
import { addBitrate, mergeAppReading, saveQuality, type Meta } from './_quality.js'
import { QUALITY } from './quality-cache.js'
import { curatedGuide } from './epg.js'
import { movieInfo, saveMeasuredFilm, showEpisodes, showList } from './_shows.js'
import { blurayMovies, searchVod } from './_vod.js'

const forbidden = () => new Response('Forbidden', { status: 403 })

export async function channels(req: Request): Promise<Response> {
  if (!appTokenOk(req.url)) return forbidden()
  try {
    const cfg = configFromEnv()
    const backup = backupConfigFromEnv()
    const feed = buildAppFeed(cfg, withCompetitionGroups(await withBackupFeeds(await fetchCuratedChannels(cfg, false), backup)), backup)
    return Response.json(feed, {
      headers: { 'Cache-Control': 'private, s-maxage=1800, stale-while-revalidate=3600' },
    })
  } catch (e) {
    return new Response(e instanceof Error ? e.message : 'upstream error', { status: 502 })
  }
}

export async function guide(req: Request): Promise<Response> {
  if (!appTokenOk(req.url)) return forbidden()
  try {
    const { channels, xml } = await curatedGuide()
    return new Response(buildAppGuide(channels, xml), {
      headers: { 'Content-Type': 'application/gzip', 'Cache-Control': 'private, s-maxage=900' },
    })
  } catch (e) {
    return new Response(e instanceof Error ? e.message : 'upstream error', { status: 502 })
  }
}

// Builds live outside dist/ (deploys prune that), at fixed paths never taken from the request.
// No file extension in the route, so Cloudflare never caches the APK.
const appFile = (name: string) => Bun.file(`${process.env.APP_DIR ?? `${import.meta.dir}/../app`}/${name}`)

async function serveFile(req: Request, name: string, type: string): Promise<Response> {
  if (!appTokenOk(req.url)) return forbidden()
  const file = appFile(name)
  if (!(await file.exists())) return new Response('Not found', { status: 404 })
  return new Response(file, { headers: { 'Content-Type': type, 'Cache-Control': 'no-store' } })
}

export const version = (req: Request) => serveFile(req, 'version.json', 'application/json')
export const apk = (req: Request) => serveFile(req, 'app.apk', 'application/vnd.android.package-archive')
export const dm = (req: Request) => serveFile(req, 'app.dm', 'application/zip')

// POST /api/app/quality?t=TOKEN — what the app measured on a stream it played. Saved into
// the quality cache under the probe's rules, so normal viewing keeps the ordering current.
const CODEC = /^[a-z0-9.-]{1,16}$/
export async function quality(req: Request): Promise<Response> {
  if (!appTokenOk(req.url)) return forbidden()
  const r = (await req.json().catch(() => null)) as Record<string, unknown> | null
  const int = (v: unknown, max: number) => Number.isInteger(v) && (v as number) > 0 && (v as number) <= max
  // Ids reach 2e9: provider-B feeds are BACKUP_ID (1e9) + their own id.
  if (!r || !int(r.id, 2e9) || !int(r.w, 8192) || !int(r.h, 8192) || typeof r.fps !== 'number' ||
      typeof r.codec !== 'string' || !CODEC.test(r.codec) || typeof r.name !== 'string' || r.name.length > 200) {
    return new Response('Bad reading', { status: 400 })
  }
  const reading: Meta = {
    w: r.w as number, h: r.h as number, fps: saneFps(r.fps), codec: r.codec, name: r.name,
    ...(r.atmos === true && { atmos: true as const }),
    ...(r.surround === true && { surround: true as const }),
  }
  const id = String(r.id)
  const merged = mergeAppReading(QUALITY[id], reading, new Date().toISOString().slice(0, 10))
  if (merged) {
    QUALITY[id] = merged
    await saveQuality()
  }
  return Response.json({ stored: !!merged }, { headers: { 'Cache-Control': 'no-store' } })
}

// POST /api/app/film-quality?t=TOKEN — what a film really is, measured by the app's player on its first
// play: from then on its labels and tier are measured, not the category's claim.
const HDR = new Set(['', 'HDR10', 'HLG', 'Dolby Vision'])
export async function filmQuality(req: Request): Promise<Response> {
  if (!appTokenOk(req.url)) return forbidden()
  const r = (await req.json().catch(() => null)) as Record<string, unknown> | null
  const int = (v: unknown, max: number) => Number.isInteger(v) && (v as number) > 0 && (v as number) <= max
  const word = (v: unknown) => typeof v === 'string' && /^[a-z0-9._-]{1,24}$/i.test(v)
  if (!r || !int(r.id, 2e9) || !int(r.w, 8192) || !int(r.h, 8192) || !word(r.codec) || !HDR.has(String(r.hdr ?? '')) ||
      (r.audio !== undefined && !word(r.audio)) || (r.channels !== undefined && !int(r.channels, 16)) ||
      (r.fps !== undefined && !(typeof r.fps === 'number' && r.fps > 0 && r.fps <= 240)) || (r.kbps !== undefined && !int(r.kbps, 500_000))) {
    return new Response('Bad reading', { status: 400 })
  }
  const stored = await saveMeasuredFilm(r.id as number, {
    w: r.w as number, h: r.h as number, codec: r.codec as string,
    ...(typeof r.fps === 'number' && { fps: Math.round(r.fps * 1000) / 1000 }),
    ...(r.hdr ? { hdr: r.hdr as string } : {}),
    ...(r.audio ? { audio: r.audio as string } : {}),
    ...(r.channels ? { channels: r.channels as number } : {}),
    ...(r.atmos === true && { atmos: true }),
    ...(r.kbps ? { kbps: r.kbps as number } : {}),
  })
  return Response.json({ stored, id: r.id }, { headers: { 'Cache-Control': 'no-store' } })
}

// POST /api/app/bitrate?t=TOKEN — a feed's bitrate (Mbps) measured while the app played it,
// kept in the quality cache with the feed's recent ones for the lighter-feed downshift (#4).
export async function bitrate(req: Request): Promise<Response> {
  if (!appTokenOk(req.url)) return forbidden()
  const r = (await req.json().catch(() => null)) as Record<string, unknown> | null
  const id = r?.id
  if (!r || !Number.isInteger(id) || !((id as number) > 0 && (id as number) <= 2e9) || typeof r.mbps !== 'number' || !(r.mbps >= 0.1 && r.mbps <= 200)) {
    return new Response('Bad reading', { status: 400 })
  }
  const merged = addBitrate(QUALITY[String(id)], r.mbps)
  if (merged) {
    QUALITY[String(id)] = merged
    await saveQuality()
  }
  return Response.json({ stored: !!merged }, { headers: { 'Cache-Control': 'no-store' } })
}

// POST /api/app/events?t=TOKEN — a batch of what happened while watching (tunes and their time to
// picture, stalls, rebuffers, feed switches, busy/wrong-route/offline). Appended to a capped log on
// the box for later analysis: which feeds actually hold up, and real zap times.
const EVENT_TYPES = new Set(['tune', 'stall', 'rebuffer', 'failover', 'problem'])
const EVENTS_MAX_BYTES = 20 * 1024 * 1024
export async function events(req: Request): Promise<Response> {
  if (!appTokenOk(req.url)) return forbidden()
  const batch = await req.json().catch(() => null)
  const ok = Array.isArray(batch) && batch.length <= 200 && batch.every((e) =>
    e && typeof e === 'object' && EVENT_TYPES.has(e.type) && JSON.stringify(e).length <= 500)
  if (!ok) return new Response('Bad events', { status: 400 })
  const file = process.env.APP_EVENTS_FILE ?? `${import.meta.dir}/../.cache/app-events.jsonl`
  const at = new Date().toISOString()
  await mkdir(dirname(file), { recursive: true })
  // Keep one previous file; the log never grows past twice the cap.
  if (((await stat(file).catch(() => null))?.size ?? 0) > EVENTS_MAX_BYTES) await rename(file, `${file}.1`)
  await appendFile(file, batch.map((e: object) => JSON.stringify({ at, ...e })).join('\n') + '\n')
  return new Response(null, { status: 204 })
}

// GET /api/app/movies?t=TOKEN — the on-demand library (4K movies, UFC replays, then Blu-ray films) in playlist order,
// from the copy the playlist build saves, so the app never adds a pull of the 74MB catalogue.
export async function movies(req: Request): Promise<Response> {
  if (!appTokenOk(req.url)) return forbidden()
  try {
    const cfg = configFromEnv()
    const vod = await savedVodChannels(cfg)
    // The 1080p Blu-ray row follows the 4K groups: films with no 4K copy.
    const movies = [...vod.map((m) => ({ id: m.streamId, name: m.name, poster: m.logo, group: m.group, ext: m.vodExt ?? 'mp4' })), ...(await blurayMovies(cfg).catch(() => []))]
    return Response.json(
      {
        groups: [...new Set(movies.map((m) => m.group))],
        movies,
      },
      { headers: { 'Cache-Control': 'private, s-maxage=1800, stale-while-revalidate=3600' } },
    )
  } catch (e) {
    return new Response(e instanceof Error ? e.message : 'upstream error', { status: 502 })
  }
}

// GET /api/app/vod-search?t=TOKEN&q=QUERY — films across the provider's whole catalogue (not only the
// library's 4K and Blu-ray rows), one per film on its best copy, in /api/app/movies' item shape.
export async function vodSearch(req: Request): Promise<Response> {
  if (!appTokenOk(req.url)) return forbidden()
  const q = (new URL(req.url).searchParams.get('q') ?? '').trim()
  if (!q) return Response.json({ movies: [] })
  try {
    return Response.json({ movies: await searchVod(configFromEnv(), q) }, {
      headers: { 'Cache-Control': 'private, s-maxage=600, stale-while-revalidate=3600' },
    })
  } catch (e) {
    return new Response(e instanceof Error ? e.message : 'upstream error', { status: 502 })
  }
}

// GET /api/app/shows?t=TOKEN — the shows (series) in the user's languages, in the movies' shape
// (see _shows.ts); the provider's list is pulled at most once a day.
export async function shows(req: Request): Promise<Response> {
  if (!appTokenOk(req.url)) return forbidden()
  try {
    return Response.json(await showList(configFromEnv()), {
      headers: { 'Cache-Control': 'private, s-maxage=1800, stale-while-revalidate=86400' },
    })
  } catch (e) {
    return new Response(e instanceof Error ? e.message : 'upstream error', { status: 502 })
  }
}

// GET /api/app/movie?t=TOKEN&id=VOD_ID — a film's plot, running time, genres and rating, fetched
// when the app shows it.
export async function movie(req: Request): Promise<Response> {
  if (!appTokenOk(req.url)) return forbidden()
  const id = Number(new URL(req.url).searchParams.get('id'))
  if (!Number.isInteger(id) || id <= 0) return new Response('Bad id', { status: 400 })
  try {
    return Response.json(await movieInfo(configFromEnv(), id), {
      headers: { 'Cache-Control': 'private, s-maxage=604800, stale-while-revalidate=604800' },
    })
  } catch (e) {
    return new Response(e instanceof Error ? e.message : 'upstream error', { status: 502 })
  }
}

// GET /api/app/episodes?t=TOKEN&id=SERIES_ID — a show's plot and episodes, in order.
export async function episodes(req: Request): Promise<Response> {
  if (!appTokenOk(req.url)) return forbidden()
  const id = Number(new URL(req.url).searchParams.get('id'))
  if (!Number.isInteger(id) || id <= 0) return new Response('Bad id', { status: 400 })
  try {
    return Response.json(await showEpisodes(configFromEnv(), id), {
      headers: { 'Cache-Control': 'private, s-maxage=21600, stale-while-revalidate=86400' },
    })
  } catch (e) {
    return new Response(e instanceof Error ? e.message : 'upstream error', { status: 502 })
  }
}
