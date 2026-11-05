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
// POST /api/app/title-ids?t=TOKEN — title ids for the provider ids the app saved marks under.
// GET /api/app/library?t=TOKEN — Movies & Shows: titles and rows (see _library.ts).
// GET /api/app/copies?t=TOKEN&title=… — every copy of a film or episode, for the stick to rank.
// POST /api/app/rd-infringing?t=TOKEN — torrents Real-Debrid refused, left out of copies from then on.
// GET /api/app/title, /api/app/search, /api/app/person ?t=TOKEN — title pages, search and person pages (see _browse.ts).
import { appendFile, mkdir, rename, stat } from 'node:fs/promises'
import { dirname } from 'node:path'
import { buildAppFeed, buildAppGuide } from './_app-feed.js'
import { iptvCopies, markInfringing, titleCopies } from './_copies.js'
import { appTokenOk, backupConfigFromEnv, configFromEnv, fetchCuratedChannels, saneFps, savedVodChannels, withBackupFeeds, withCompetitionGroups } from './_lib.js'
import { providerLibrary, servedLibrary } from './_library.js'
import { addBitrate, mergeAppReading, saveQuality, type Meta } from './_quality.js'
import { QUALITY } from './quality-cache.js'
import { curatedGuide } from './epg.js'
import { movieInfo, saveMeasuredFilm, showEpisodes, showList, titleIds as mapTitleIds } from './_shows.js'
import { knownTitles, personTitles, searchTitles, titleDetails } from './_browse.js'
import { loadScores, ranked, scoreTitles } from './_scores.js'
import { tmdbFacts, tmdbGet } from './_tmdb.js'
import { blurayMovies, filmTitleOf, searchVod } from './_vod.js'

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
// picture, stalls, rebuffers, feed switches, busy/wrong-route/offline; a film's copy failovers, step-downs, offers and trials). Appended to a capped log on
// the box for later analysis: which feeds actually hold up, and real zap times.
const EVENT_TYPES = new Set(['tune', 'stall', 'rebuffer', 'failover', 'problem', 'stepdown', 'offer', 'trial'])
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
    const [vod, titleOf] = await Promise.all([savedVodChannels(cfg), filmTitleOf(cfg).catch(() => null)])
    // The 1080p Blu-ray row follows the 4K groups: films with no 4K copy.
    const movies = [...vod.map((m) => ({ id: m.streamId, titleId: titleOf?.(m.streamId) ?? `iptv:${m.streamId}`, name: m.name, poster: m.logo, group: m.group, ext: m.vodExt ?? 'mp4' })), ...(await blurayMovies(cfg).catch(() => []))]
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
    const info = await movieInfo(configFromEnv(), id)
    return Response.json({ ...info, ...(await tmdbFacts(info.titleId)) }, {
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
    const show = await showEpisodes(configFromEnv(), id)
    return Response.json({ ...show, ...(await tmdbFacts(show.titleId)) }, {
      headers: { 'Cache-Control': 'private, s-maxage=21600, stale-while-revalidate=86400' },
    })
  } catch (e) {
    return new Response(e instanceof Error ? e.message : 'upstream error', { status: 502 })
  }
}

// GET /api/app/library?t=TOKEN — the Movies & Shows library the nightly job builds (see _library.ts), held in
// memory; a request whose If-None-Match names the current ETag gets an empty 304.
export async function library(req: Request): Promise<Response> {
  if (!appTokenOk(req.url)) return forbidden()
  try {
    // The provider's rows are cut and sorted like the built library's, so the fallback stays small.
    const { body, etag } = await servedLibrary(async () => ranked(await providerLibrary(configFromEnv()), scoreTitles(await loadScores())))
    const headers = { ETag: etag, 'Cache-Control': 'private, no-cache' }
    // Cloudflare weakens the ETag (W/) when it compresses the response.
    if (req.headers.get('if-none-match')?.replace(/^W\//, '') === etag) return new Response(null, { status: 304, headers })
    return new Response(body, { headers: { ...headers, 'Content-Type': 'application/json' } })
  } catch (e) {
    return new Response(e instanceof Error ? e.message : 'upstream error', { status: 502 })
  }
}

// POST /api/app/title-ids?t=TOKEN — { movies?: [vodId], shows?: [seriesId], episodes?: [{ series, id }] } to
// { movies: { vodId: titleId }, shows: { seriesId: titleId }, episodes: { episodeId: titleId } }: the one-time
// move of the app's resume and watched marks from provider ids to title ids (see _shows.ts titleIds).
export async function titleIds(req: Request): Promise<Response> {
  if (!appTokenOk(req.url)) return forbidden()
  const r = (await req.json().catch(() => null)) as { movies?: unknown; shows?: unknown; episodes?: unknown } | null
  const id = (v: unknown) => Number.isInteger(v) && (v as number) > 0 && (v as number) <= 2e9
  const list = (v: unknown, ok: (x: any) => boolean) => v === undefined || (Array.isArray(v) && v.length <= 5000 && v.every(ok))
  const eps = r?.episodes as { series: number; id: number }[] | undefined
  if (!r || !list(r.movies, id) || !list(r.shows, id) || !list(eps, (e) => e && id(e.series) && id(e.id)) || new Set(eps?.map((e) => e.series)).size > 100) {
    return new Response('Bad ids', { status: 400 })
  }
  try {
    return Response.json(await mapTitleIds(configFromEnv(), { movies: r.movies as number[], shows: r.shows as number[], episodes: eps }), {
      headers: { 'Cache-Control': 'no-store' },
    })
  } catch (e) {
    return new Response(e instanceof Error ? e.message : 'upstream error', { status: 502 })
  }
}

// GET /api/app/copies?t=TOKEN&title=TITLE_ID — every copy of a film or episode, IPTV copies and Real-Debrid
// candidates, with what each is (see _copies.ts); the stick ranks them. Kept an hour once both sources
// answered (Torrentio's cached flags change).
const COPY_TITLE = /^(?:movie:\d+|iptv:\d+|(?:tv|iptv:tv):\d+:s\d+e\d+)$/
export async function copies(req: Request): Promise<Response> {
  if (!appTokenOk(req.url)) return forbidden()
  const title = new URL(req.url).searchParams.get('title') ?? ''
  if (!COPY_TITLE.test(title)) return new Response('Bad title', { status: 400 })
  const c = await titleCopies(title, iptvCopies(configFromEnv(), title))
  const settled = c.sources.iptv === 'ok' && c.sources.rd === 'ok'
  return Response.json(c, { headers: { 'Cache-Control': settled ? 'private, s-maxage=3600, stale-while-revalidate=86400' : 'no-store' } })
}

// POST /api/app/rd-infringing?t=TOKEN — { hashes: [infoHash] } Real-Debrid refused here (451): no title
// offers them again for 60 days.
export async function rdInfringing(req: Request): Promise<Response> {
  if (!appTokenOk(req.url)) return forbidden()
  const hashes = ((await req.json().catch(() => null)) as { hashes?: unknown } | null)?.hashes
  if (!Array.isArray(hashes) || !hashes.length || hashes.length > 200 || !hashes.every((h) => typeof h === 'string' && /^[0-9a-f]{40}$/i.test(h))) {
    return new Response('Bad hashes', { status: 400 })
  }
  return Response.json({ stored: await markInfringing(hashes) }, { headers: { 'Cache-Control': 'no-store' } })
}

// GET /api/app/title?t=TOKEN&id=movie:27205|tv:1396 — a title page's TMDB details: plot, backdrop, cast and
// director, More like this, its score, and a show's episodes when the provider lacks it (see _browse.ts).
export async function title(req: Request): Promise<Response> {
  if (!appTokenOk(req.url)) return forbidden()
  const id = new URL(req.url).searchParams.get('id') ?? ''
  if (!/^(movie|tv):\d+$/.test(id)) return new Response('Bad id', { status: 400 })
  try {
    const t = await titleDetails(configFromEnv(), id)
    if (!t) return new Response('Unknown title', { status: 404 })
    return Response.json(t, { headers: { 'Cache-Control': 'private, s-maxage=21600, stale-while-revalidate=86400' } })
  } catch (e) {
    return new Response(e instanceof Error ? e.message : 'upstream error', { status: 502 })
  }
}

// GET /api/app/search?t=TOKEN&q=… — films and shows for a search: a category word's row or TMDB discover,
// else TMDB's title search plus the provider's catalogue (see _browse.ts searchTitles).
export async function search(req: Request): Promise<Response> {
  if (!appTokenOk(req.url)) return forbidden()
  const q = (new URL(req.url).searchParams.get('q') ?? '').trim().slice(0, 100)
  if (!q) return Response.json({ titles: [] })
  const cfg = configFromEnv()
  const titles = await searchTitles(q, {
    known: await knownTitles(),
    tmdb: process.env.TMDB_TOKEN ? (path) => tmdbGet(path) : undefined,
    vod: (q) => searchVod(cfg, q, 20),
  })
  return Response.json({ titles }, { headers: { 'Cache-Control': 'private, s-maxage=1800, stale-while-revalidate=3600' } })
}

// GET /api/app/person?t=TOKEN&id=TMDB_PERSON_ID — a person's films and shows, best score first.
export async function person(req: Request): Promise<Response> {
  if (!appTokenOk(req.url)) return forbidden()
  const id = new URL(req.url).searchParams.get('id') ?? ''
  if (!/^[1-9]\d{0,9}$/.test(id)) return new Response('Bad id', { status: 400 })
  if (!process.env.TMDB_TOKEN) return new Response('No TMDB', { status: 404 })
  try {
    const p = await tmdbGet<{ name?: string; combined_credits?: Parameters<typeof personTitles>[0] }>(`person/${id}?append_to_response=combined_credits`)
    return Response.json({ name: p.name ?? '', titles: personTitles(p.combined_credits ?? {}, await knownTitles()) }, {
      headers: { 'Cache-Control': 'private, s-maxage=86400, stale-while-revalidate=604800' },
    })
  } catch (e) {
    return new Response(e instanceof Error ? e.message : 'upstream error', { status: 502 })
  }
}
