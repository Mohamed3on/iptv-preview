// Title ids (KTD10) and the TMDB client (KTD9).
// A title id names a film or show whatever copy plays it: `movie:<tmdb>`, `tv:<tmdb>`, an episode
// `tv:<tmdb>:s<season>e<episode>`. A provider title with no TMDB id keeps its own: `iptv:<vodId>` for a
// film, `iptv:tv:<seriesId>` for a show (VOD and series ids are separate counters, so they could clash);
// those are never merged with anything.
// TMDB details (IMDb id, original language, runtime, cast, recommendations) are fetched only when
// TMDB_TOKEN is set, and kept in .cache/tmdb-titles.json for 30 days; without the token everything
// still answers, with title ids from the provider's tmdb column and no TMDB facts.
import { mkdir, readFile, rename } from 'node:fs/promises'
import { dirname } from 'node:path'
import { fileURLToPath } from 'node:url'
import { getJson, HttpError } from './_http.js'

const tmdbId = (v: unknown) => {
  const s = String(v ?? '').trim()
  return /^[1-9]\d*$/.test(s) ? s : ''
}
export const filmTitle = (tmdb: unknown, vodId: number) => (tmdbId(tmdb) ? `movie:${tmdbId(tmdb)}` : `iptv:${vodId}`)
export const showTitle = (tmdb: unknown, seriesId: number) => (tmdbId(tmdb) ? `tv:${tmdbId(tmdb)}` : `iptv:tv:${seriesId}`)
export const episodeTitle = (show: string, season: number, episode: number) => `${show}:s${season}e${episode}`

export type Kind = 'movie' | 'tv'
/** A film or show as TMDB knows it, trimmed to what the box serves. */
export interface TmdbTitle {
  /** "tt1375666"; "" when TMDB has none. */
  imdb: string
  /** ISO 639-1, "en". */
  originalLanguage: string
  /** Minutes (a show's: an episode's); 0 when unknown. */
  runtime: number
  name: string
  year: string
  poster: string
  /** The overview; "" when TMDB has none. */
  plot: string
  /** Backdrop path ("/x.jpg"); "" when none. */
  backdrop: string
  genres: string[]
  /** A show's season numbers (specials left out); [] for a film. */
  seasons: number[]
  cast: { id: number; name: string; character: string }[]
  /** A film's directors, a show's creators. */
  directors: { id: number; name: string }[]
  recommendations: { title: string; name: string; year: string; poster: string }[]
}

interface Raw {
  title?: string
  name?: string
  imdb_id?: string
  original_language?: string
  runtime?: number
  episode_run_time?: number[]
  last_episode_to_air?: { runtime?: number } | null
  release_date?: string
  first_air_date?: string
  poster_path?: string | null
  overview?: string
  backdrop_path?: string | null
  genres?: { name: string }[]
  seasons?: { season_number: number }[]
  created_by?: { id: number; name: string }[]
  external_ids?: { imdb_id?: string | null }
  credits?: { cast?: { id: number; name: string; character?: string }[]; crew?: { id: number; name: string; job?: string }[] }
  recommendations?: { results?: (Raw & { id: number; media_type?: string })[] }
}

const poster = (p: string | null | undefined) => (p ? `https://image.tmdb.org/t/p/w342${p}` : '')
const yearOf = (r: Raw) => (r.release_date || r.first_air_date || '').slice(0, 4)

function trim(kind: Kind, r: Raw): TmdbTitle {
  return {
    imdb: r.imdb_id || r.external_ids?.imdb_id || '',
    originalLanguage: r.original_language ?? '',
    runtime: r.runtime || r.episode_run_time?.[0] || r.last_episode_to_air?.runtime || 0,
    name: r.title ?? r.name ?? '',
    year: yearOf(r),
    poster: poster(r.poster_path),
    plot: r.overview?.trim() ?? '',
    backdrop: r.backdrop_path ?? '',
    genres: (r.genres ?? []).map((g) => g.name),
    seasons: (r.seasons ?? []).map((x) => x.season_number).filter((n) => n > 0),
    cast: (r.credits?.cast ?? []).slice(0, 10).map(({ id, name, character }) => ({ id, name, character: character ?? '' })),
    directors: kind === 'tv'
      ? (r.created_by ?? []).map(({ id, name }) => ({ id, name }))
      : (r.credits?.crew ?? []).filter((c) => c.job === 'Director').map(({ id, name }) => ({ id, name })),
    recommendations: (r.recommendations?.results ?? []).slice(0, 20).map((x) => ({
      title: (x.media_type ?? kind) === 'tv' ? showTitle(x.id, 0) : filmTitle(x.id, 0),
      name: x.title ?? x.name ?? '',
      year: yearOf(x),
      poster: poster(x.poster_path),
    })),
  }
}

const MAX_AGE_MS = 30 * 864e5
const cacheFile = () => process.env.TMDB_CACHE_FILE ?? fileURLToPath(new URL('../.cache/tmdb-titles.json', import.meta.url))
type Entry = { at: number; t?: TmdbTitle | null; e?: TmdbEpisode[] }
let cache: Promise<Record<string, Entry>> | null = null
const loaded = () => (cache ??= readFile(cacheFile(), 'utf8').then((s) => JSON.parse(s) as Record<string, Entry>).catch(() => ({} as Record<string, Entry>)))
const pending = new Map<string, Promise<TmdbTitle | null>>()

let writing = Promise.resolve()
/** Writes the cache atomically (temp + rename), one write at a time. */
function save(all: Record<string, Entry>): Promise<void> {
  const file = cacheFile()
  writing = writing.then(async () => {
    await mkdir(dirname(file), { recursive: true })
    await Bun.write(file + '.tmp', JSON.stringify(all))
    await rename(file + '.tmp', file)
  }).catch(() => {})
  return writing
}

/** GET a TMDB v3 path ("discover/movie?with_genres=35"). A v3 API key (32 hex) goes in the query; a v4 read token as a Bearer header. */
export function tmdbGet<T>(path: string, key = process.env.TMDB_TOKEN ?? ''): Promise<T> {
  const v3 = /^[0-9a-f]{32}$/i.test(key)
  const url = `https://api.themoviedb.org/3/${path}${v3 ? `${path.includes('?') ? '&' : '?'}api_key=${key}` : ''}`
  return getJson<T>(url, v3 ? {} : { headers: { Authorization: `Bearer ${key}` } })
}

/**
 * A film's or show's TMDB details; null without TMDB_TOKEN, for an id TMDB doesn't know, or when TMDB
 * fails with nothing cached (a stale entry is served instead).
 */
export async function tmdbTitle(kind: Kind, id: string | number): Promise<TmdbTitle | null> {
  const key = process.env.TMDB_TOKEN
  const tmdb = tmdbId(id)
  if (!key || !tmdb) return null
  const k = `${kind}:${tmdb}`
  const all = await loaded()
  // An entry kept before plots and backdrops were is fetched again.
  if (all[k] && Date.now() - all[k].at < MAX_AGE_MS && (!all[k].t || all[k].t!.plot !== undefined)) return all[k].t ?? null
  let job = pending.get(k)
  if (!job) {
    job = tmdbGet<Raw>(`${kind}/${tmdb}?append_to_response=external_ids,credits,recommendations`, key)
      .then((r) => trim(kind, r))
      .catch((e) => {
        if (e instanceof HttpError && e.status === 404) return null
        throw e
      })
      .then((t) => {
        all[k] = { at: Date.now(), t }
        save(all)
        return t
      }, () => all[k]?.t ?? null)
      .finally(() => pending.delete(k))
    pending.set(k, job)
  }
  return job
}

/** A title id's IMDb id and original language, to spread into a response; {} when TMDB has nothing. */
export async function tmdbFacts(title: string): Promise<{ imdb?: string; originalLanguage?: string }> {
  const m = title.match(/^(movie|tv):(\d+)/)
  const t = m ? await tmdbTitle(m[1] as Kind, m[2]) : null
  return t ? { ...(t.imdb && { imdb: t.imdb }), ...(t.originalLanguage && { originalLanguage: t.originalLanguage }) } : {}
}

/** A show's episode as TMDB lists it. */
export interface TmdbEpisode { season: number; episode: number; title: string; secs: number; image: string }
interface RawEpisode { season_number: number; episode_number: number; name?: string; runtime?: number | null; still_path?: string | null; air_date?: string | null }

const EPISODES_MAX_AGE_MS = 864e5

/**
 * A show's aired episodes in order ([seasons] asked 20 to a call), kept a day in the same cache, as new
 * episodes air; [] without TMDB_TOKEN, the last list kept when TMDB fails.
 */
export async function tmdbEpisodes(id: string | number, seasons: number[]): Promise<TmdbEpisode[]> {
  const key = process.env.TMDB_TOKEN
  const tmdb = tmdbId(id)
  if (!key || !tmdb || !seasons.length) return []
  const k = `tv:${tmdb}:episodes`
  const all = await loaded()
  if (all[k] && Date.now() - all[k].at < EPISODES_MAX_AGE_MS) return all[k].e ?? []
  const today = new Date().toISOString().slice(0, 10)
  const chunks = Array.from({ length: Math.ceil(seasons.length / 20) }, (_, i) => seasons.slice(i * 20, i * 20 + 20))
  try {
    const parts = await Promise.all(chunks.map((c) => tmdbGet<Record<string, { episodes?: RawEpisode[] }>>(`tv/${tmdb}?append_to_response=${c.map((n) => `season/${n}`).join(',')}`, key)))
    const e = parts
      .flatMap((p) => Object.entries(p).filter(([n]) => n.startsWith('season/')).flatMap(([, s]) => s?.episodes ?? []))
      .filter((x) => x.air_date && x.air_date <= today)
      .map((x) => ({ season: x.season_number, episode: x.episode_number, title: x.name ?? '', secs: (x.runtime ?? 0) * 60, image: x.still_path ? `https://image.tmdb.org/t/p/w300${x.still_path}` : '' }))
      .sort((a, b) => a.season - b.season || a.episode - b.episode)
    all[k] = { at: Date.now(), e }
    save(all)
    return e
  } catch {
    return all[k]?.e ?? []
  }
}
