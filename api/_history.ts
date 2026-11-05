// The owner's history (U16; R26, R31, KTD19): what Trakt and the Letterboxd diary say was watched, Trakt's
// Watchlist and recommendations, and each followed show's next episode for Up next. The Trakt and Letterboxd
// parts are kept in .cache/history.json, read again nightly by scripts/build-library.ts and when the app asks
// and they are over 10 minutes old (then one Trakt call, last_activities, when nothing changed, and the RSS
// feed). /api/app/history serves them with titles in the library's shape, with ETag/304 like the library.
// Watched marks are the union of Trakt, Letterboxd and the stick's own marks (merged on the stick); the
// stick's are never written back to Trakt. A source that fails keeps what it gave last and says so in `src`.
import { mkdir, readFile, rename } from 'node:fs/promises'
import { dirname } from 'node:path'
import { fileURLToPath } from 'node:url'
import { byScore, decorate, type Known } from './_browse.js'
import type { LibTitle } from './_library.js'
import { episodeTitle, tmdbEpisodes, tmdbTitle, type Kind, type TmdbEpisode } from './_tmdb.js'
import { accessToken, revoked, status, traktHeaders } from './_trakt.js'

const API = 'https://api.trakt.tv'
export const HISTORY_FILE = fileURLToPath(new URL('../.cache/history.json', import.meta.url))

/** What the box keeps of the owner's history: title ids (KTD10). */
export interface Kept {
  at: number
  trakt: 'on' | 'off' | 'reconnect' | 'error'
  letterboxd: 'on' | 'off' | 'blocked'
  /** Trakt's last_activities.all when its parts were read. */
  acts?: string
  movies: string[]
  /** Watched episodes, the most recently watched first (U17 reads the owner's latest from here). */
  eps: string[]
  /** Each watched show's last watch, epoch ms. */
  shows: Record<string, number>
  /** Shows hidden from Trakt's progress (dropped): never followed. */
  hidden: string[]
  watchlist: string[]
  recs: string[]
  /** Films in the Letterboxd diary: every one the feed has listed (it holds the latest 50). */
  lb: string[]
}
type TraktPart = Pick<Kept, 'trakt' | 'acts' | 'movies' | 'eps' | 'shows' | 'hidden' | 'watchlist' | 'recs'>

const NONE = { movies: [], eps: [], shows: {}, hidden: [], watchlist: [], recs: [] }
const traktOf = (h: Kept | null) => ({ ...NONE, ...(h && { acts: h.acts, movies: h.movies, eps: h.eps, shows: h.shows, hidden: h.hidden, watchlist: h.watchlist, recs: h.recs }) })

type Ids = { ids?: { tmdb?: number | null } }
const film = (m?: Ids) => (m?.ids?.tmdb ? `movie:${m.ids.tmdb}` : null)
const show = (s?: Ids) => (s?.ids?.tmdb ? `tv:${s.ids.tmdb}` : null)
const known = (x: string | null): x is string => !!x

class Refused extends Error {}

async function readTrakt(access: string, prev: Kept | null, full: boolean): Promise<TraktPart> {
  const get = async <T>(path: string): Promise<T> => {
    const r = await fetch(API + path, { headers: traktHeaders(access), signal: AbortSignal.timeout(20_000) })
    if (r.status === 401) throw new Refused()
    if (!r.ok) throw new Error(`Trakt ${r.status} on ${path}`)
    return r.json() as Promise<T>
  }
  const acts = (await get<{ all?: string }>('/sync/last_activities')).all
  if (!full && prev?.trakt === 'on' && acts && prev.acts === acts) return { ...traktOf(prev), trakt: 'on' }
  const recs = (kind: string) => get<Ids[]>(`/recommendations/${kind}?limit=50&ignore_collected=true&ignore_watchlisted=true`)
  const [movies, shows, hidden, watchlist, recFilms, recShows] = await Promise.all([
    get<{ movie: Ids }[]>('/sync/watched/movies'),
    get<{ last_watched_at: string; show: Ids; seasons?: { number: number; episodes: { number: number; last_watched_at: string }[] }[] }[]>('/sync/watched/shows'),
    get<{ show?: Ids }[]>('/users/hidden/progress_watched?type=show&limit=1000'),
    get<{ type: string; movie?: Ids; show?: Ids }[]>('/sync/watchlist'),
    recs('movies'),
    recs('shows'),
  ])
  const eps = shows.flatMap((s) => {
    const id = show(s.show)
    return id ? (s.seasons ?? []).flatMap((n) => n.episodes.map((e) => ({ id: episodeTitle(id, n.number, e.number), at: Date.parse(e.last_watched_at) }))) : []
  })
  return {
    trakt: 'on',
    acts,
    movies: movies.map((m) => film(m.movie)).filter(known),
    eps: eps.sort((a, b) => b.at - a.at).map((e) => e.id),
    shows: Object.fromEntries(shows.map((s) => [show(s.show), Date.parse(s.last_watched_at)]).filter(([id]) => id)),
    hidden: hidden.map((h) => show(h.show)).filter(known),
    watchlist: watchlist.map((w) => (w.type === 'movie' ? film(w.movie) : w.type === 'show' ? show(w.show) : null)).filter(known),
    // Films and shows taken in turn: the app ranks them by score.
    recs: Array.from({ length: Math.max(recFilms.length, recShows.length) }, (_, i) => [film(recFilms[i]), show(recShows[i])]).flat().filter(known),
  }
}

async function traktPart(prev: Kept | null, full: boolean): Promise<TraktPart> {
  const access = await accessToken()
  if (!access) {
    const state = (await status()).state
    // Signed out: nothing of Trakt's is kept. A sign-in that needs renewing keeps what it read last.
    return state === 'on' || state === 'reconnect' ? { ...traktOf(prev), trakt: 'reconnect' } : { ...NONE, trakt: 'off' }
  }
  try {
    return await readTrakt(access, prev, full)
  } catch (e) {
    if (e instanceof Refused) revoked()
    return { ...traktOf(prev), trakt: e instanceof Refused ? 'reconnect' : 'error' }
  }
}

/** The films a Letterboxd RSS feed lists (diary entries and reviews carry `tmdb:movieId`). */
export const letterboxdFilms = (xml: string) => [...xml.matchAll(/<tmdb:movieId>(\d+)<\/tmdb:movieId>/g)].map((m) => `movie:${m[1]}`)

async function letterboxdPart(prev: Kept | null): Promise<Pick<Kept, 'letterboxd' | 'lb'>> {
  const user = process.env.LETTERBOXD_USER
  if (!user) return { letterboxd: 'off', lb: [] }
  const r = await fetch(`https://letterboxd.com/${encodeURIComponent(user)}/rss/`, { headers: { 'user-agent': 'iptv-preview' }, signal: AbortSignal.timeout(15_000) }).catch(() => null)
  const xml = r?.ok ? await r.text().catch(() => '') : ''
  // A challenge page or an error: skipped, the films seen before kept.
  if (!xml.includes('<rss')) return { letterboxd: 'blocked', lb: prev?.lb ?? [] }
  return { letterboxd: 'on', lb: [...new Set([...letterboxdFilms(xml), ...(prev?.lb ?? [])])] }
}

/**
 * The history read again from Trakt and Letterboxd. Trakt's parts are read whole only when its last activity
 * changed, or [full] (nightly: recommendations change by themselves).
 */
export async function refreshHistory(prev: Kept | null, { full = false } = {}): Promise<Kept> {
  const [trakt, lb] = await Promise.all([traktPart(prev, full), letterboxdPart(prev)])
  return { at: Date.now(), ...trakt, ...lb }
}

export const loadHistory = (file = HISTORY_FILE) => readFile(file, 'utf8').then((s) => JSON.parse(s) as Kept, () => null)

/** Writes the history atomically (temp + rename): the server and the nightly build both write it. */
export async function saveHistory(h: Kept, file = HISTORY_FILE): Promise<void> {
  await mkdir(dirname(file), { recursive: true })
  await Bun.write(file + '.tmp', JSON.stringify(h))
  await rename(file + '.tmp', file)
}

const FRESH_MS = 10 * 60e3
let reading: Promise<Kept | null> | null = null

/** The kept history, read again first when it is over 10 minutes old (one read at a time). */
export function currentHistory(): Promise<Kept | null> {
  return (reading ??= (async () => {
    const prev = await loadHistory()
    if (prev && Date.now() - prev.at < FRESH_MS) return prev
    const next = await refreshHistory(prev)
    await saveHistory(next)
    return next
  })().finally(() => (reading = null)))
}

/** A followed show: when it was last watched and the furthest episode watched. */
export interface Followed { last: number; season: number; episode: number }

const FOLLOW_MS = 183 * 864e5
const EPISODE = /^(tv:\d+):s(\d+)e(\d+)$/

/**
 * Shows the owner follows: watched on Trakt in the last 6 months (unless hidden from its progress), or on the
 * stick ([local]: "tv:N:sSeE@epoch ms", the episode it last finished), each with its furthest watched episode.
 */
export function followed(h: Kept | null, local: string[], now = Date.now()): Map<string, Followed> {
  const out = new Map<string, Followed>()
  const further = (id: string, season: number, episode: number, last = 0) => {
    const f = out.get(id)
    if (!f) return
    if (season > f.season || (season === f.season && episode > f.episode)) Object.assign(f, { season, episode })
    f.last = Math.max(f.last, last)
  }
  const hidden = new Set(h?.hidden)
  for (const [id, last] of Object.entries(h?.shows ?? {})) if (now - last < FOLLOW_MS && !hidden.has(id)) out.set(id, { last, season: 0, episode: 0 })
  for (const l of local) {
    const [, ep, at] = l.match(/^(.+)@(\d+)$/) ?? []
    const m = ep?.match(EPISODE)
    if (!m || now - Number(at) >= FOLLOW_MS) continue
    if (!out.has(m[1])) out.set(m[1], { last: 0, season: 0, episode: 0 })
    further(m[1], +m[2], +m[3], Number(at))
  }
  for (const e of h?.eps ?? []) {
    const m = e.match(EPISODE)
    if (m) further(m[1], +m[2], +m[3])
  }
  return out
}

/** A followed show's next episode for Up next; [new] when it aired after the show was last watched. */
export interface Next { show: string; ep: string; c: string; en?: string; air: string; new?: 1 }

/** Each followed show's first aired episode after its furthest watched (none when caught up), the latest aired first. */
export async function nextEpisodes(follow: Map<string, Followed>, episodes: (show: string) => Promise<TmdbEpisode[]>): Promise<Next[]> {
  const next = await Promise.all([...follow].map(async ([id, f]) => {
    const e = (await episodes(id)).find((x) => x.season > f.season || (x.season === f.season && x.episode > f.episode))
    return e && { show: id, ep: episodeTitle(id, e.season, e.episode), c: `S${e.season} E${e.episode}`, ...(e.title && { en: e.title }), air: e.air, ...(Date.parse(e.air) > f.last && { new: 1 as const }) }
  }))
  return next.filter((n): n is Next => !!n).sort((a, b) => b.air.localeCompare(a.air))
}

/** [fn] over [items], [n] at a time. */
async function pool<T, R>(items: T[], n: number, fn: (x: T) => Promise<R>): Promise<R[]> {
  const out: R[] = []
  let i = 0
  await Promise.all(Array.from({ length: n }, async () => {
    for (let j = i++; j < items.length; j = i++) out[j] = await fn(items[j])
  }))
  return out
}

const ROW_CAP = 60
const FOLLOW_CAP = 30

/**
 * What /api/app/history answers: each source's state, every title Trakt and Letterboxd mark watched, the
 * Watchlist (Trakt's order) and Recommended (best score first, watched titles left out) as library titles,
 * and the next episode of each followed show ([local] from the stick, see [followed]).
 */
export async function historyAnswer(h: Kept | null, local: string[], k: Known) {
  const titles = new Map<string, LibTitle | null>()
  const title = async (id: string): Promise<LibTitle | null> => {
    if (titles.has(id)) return titles.get(id)!
    const [kind, tmdb] = id.split(':') as [Kind, string]
    const t = k.titles.has(id) ? null : await tmdbTitle(kind, tmdb)
    const base = k.titles.get(id) ?? (t && { id, k: kind === 'tv' ? 't' : 'm', n: t.name, ...(Number(t.year) && { y: Number(t.year) }), ...(t.poster && { p: t.poster }), ...(t.backdrop && { b: t.backdrop }), ...(t.originalLanguage && { l: t.originalLanguage }) })
    const out = base ? decorate(k, base as LibTitle) : null
    titles.set(id, out)
    return out
  }
  const seen = new Set([...(h?.movies ?? []), ...(h?.lb ?? []), ...Object.keys(h?.shows ?? {})])
  const follow = new Map([...followed(h, local)].sort(([, a], [, b]) => b.last - a.last).slice(0, FOLLOW_CAP))
  const next = await nextEpisodes(follow, async (id) => {
    const t = await tmdbTitle('tv', id.slice(3))
    return t ? tmdbEpisodes(id.slice(3), t.seasons) : []
  })
  const list = async (ids: string[]) => (await pool(ids, 8, title)).filter((t): t is LibTitle => !!t)
  const [watchlist, recommended, shows] = await Promise.all([
    list((h?.watchlist ?? []).slice(0, ROW_CAP * 2)),
    list((h?.recs ?? []).filter((id) => !seen.has(id))),
    pool(next, 8, (n) => title(n.show)),
  ])
  return {
    src: { trakt: h?.trakt ?? 'off', letterboxd: h?.letterboxd ?? 'off' },
    watched: [...new Set([...(h?.movies ?? []), ...(h?.lb ?? []), ...(h?.eps ?? [])])],
    watchlist,
    recommended: byScore(recommended).slice(0, ROW_CAP),
    next: next.flatMap(({ show: _s, ...n }, i) => (shows[i] ? [{ t: shows[i], ...n }] : [])),
  }
}
