// What the app's title pages, search and person pages ask beyond the library (U13): a title's TMDB details
// with More like this, cast and director (and a TMDB-only show's episodes, so Real-Debrid can play them),
// search over titles and category words, and a person's titles. Every title comes in the library's shape
// (_library.ts LibTitle) with the tier and score the library knows for it.
import { readFile } from 'node:fs/promises'
import type { XtreamConfig } from './_lib.js'
import { fromTmdb, LIBRARY_FILE, LIBRARY_FULL_FILE, type LibTitle, type Library, type TmdbItem } from './_library.js'
import { loadScores, scoreNow, scoreTitles, type Score, type ScoreCache } from './_scores.js'
import { showList } from './_shows.js'
import { episodeTitle, parseTitleId, tmdbEpisodes, tmdbTitle } from './_tmdb.js'
import type { VodItem } from './_vod.js'

/** The built library (null when missing), every title it has seen with its tier (the uncut build), and scores (with what the APIs said). */
export interface Known { lib: Library | null; titles: Map<string, LibTitle>; scores: Map<string, Score>; cache?: ScoreCache }

const KNOWN_MS = 10 * 60e3
let known: { at: number; job: Promise<Known> } | null = null

/** [Known], read again at most every 10 minutes (the nightly build rewrites the files). */
export function knownTitles(): Promise<Known> {
  if (known && Date.now() - known.at < KNOWN_MS) return known.job
  const read = (f: string) => readFile(f, 'utf8').then((s) => JSON.parse(s) as Library, () => null)
  const job = Promise.all([read(LIBRARY_FILE), read(LIBRARY_FULL_FILE), loadScores()]).then(([lib, full, cache]) => {
    const titles = new Map<string, LibTitle>()
    for (const t of [...(full?.titles ?? []), ...(lib?.titles ?? [])]) titles.set(t.id, { ...titles.get(t.id), ...t })
    return { lib, titles, scores: scoreTitles(cache), cache }
  })
  known = { at: Date.now(), job }
  return job
}

/** [t] with the tier and score the library knows for it. */
export function decorate(k: Known, t: LibTitle): LibTitle {
  const base = k.titles.get(t.id)
  return { ...t, ...(base?.t && { t: base.t, ...(base.e && { e: base.e }) }), ...k.scores.get(t.id) }
}

/** Best score first; unscored titles keep their order after them. */
export const byScore = <T extends LibTitle>(list: T[]) => list.map((t, i) => [t, i] as const).sort(([a, i], [b, j]) => (b.s ?? -Infinity) - (a.s ?? -Infinity) || i - j).map(([t]) => t)

const IMG = 'https://image.tmdb.org/t/p/'

/**
 * A film's or show's page beyond its card, in the app's MovieInfo shape: plot, runtime, genres, backdrop,
 * cast and director (and as people, for their pages), More like this, its score; a show the provider lists
 * gives its series id (episodes come from the provider), one it lacks its aired episodes from TMDB.
 * A title the APIs were never asked about is asked now ([scoreNow]): its scores join this answer if they come
 * back before the rest of it, else the next one (the page never waits for them).
 * null for an id TMDB doesn't know (or without TMDB_TOKEN).
 */
export async function titleDetails(cfg: XtreamConfig, id: string) {
  const p = parseTitleId(id)
  if (!p || p.season != null) return null
  const { kind, tmdb } = p
  const [t, k] = await Promise.all([tmdbTitle(kind, tmdb), knownTitles()])
  if (!t) return null
  const cache = k.cache
  if (cache && !k.scores.has(id) && !cache[id]?.ta && !cache[id]?.ma) {
    void scoreNow(id, t.imdb || undefined, { trakt: process.env.TRAKT_CLIENT_ID, mdblist: process.env.MDBLIST_KEY }).then((e) => {
      if (!e) return
      cache[id] = { ...cache[id], ...e }
      const s = scoreTitles(cache).get(id)
      if (s) k.scores.set(id, s)
    })
  }
  const series = kind === 'tv' ? (await showList(cfg).catch(() => null))?.movies.find((s) => s.titleId === id)?.id : undefined
  const episodes = kind === 'tv' && !series ? await tmdbEpisodes(tmdb, t.seasons) : []
  const people = new Map<number, { id: number; name: string; role: string }>()
  for (const d of t.directors) people.set(d.id, { id: d.id, name: d.name, role: kind === 'tv' ? 'Creator' : 'Director' })
  for (const c of t.cast) if (!people.has(c.id)) people.set(c.id, { id: c.id, name: c.name, role: c.character })
  return {
    titleId: id,
    plot: t.plot,
    secs: kind === 'movie' ? t.runtime * 60 : 0,
    genre: t.genres.slice(0, 2).join(', '),
    backdrop: t.backdrop && `${IMG}w1280${t.backdrop}`,
    cast: t.cast.slice(0, 4).map((c) => c.name).join(', '),
    director: t.directors.map((d) => d.name).join(', '),
    people: [...people.values()],
    more: t.recommendations.map((r) => decorate(k, { id: r.title, k: r.title.startsWith('tv:') ? 't' : 'm', n: r.name, ...(Number(r.year) && { y: Number(r.year) }), ...(r.poster && { p: r.poster }) })),
    ...k.scores.get(id),
    ...(series && { series }),
    ...(episodes.length && {
      episodes: episodes.map((e) => ({ id: -(e.season * 10_000 + e.episode), titleId: episodeTitle(id, e.season, e.episode), ...e })),
    }),
  }
}

interface Credit extends TmdbItem { vote_count?: number; job?: string }
const NOT_THEIR_WORK = new Set([10763, 10764, 10767]) // news, reality, talk: appearances

/** A person's films and shows (acted in, directed or created), best score first, then the most voted; at most 60. */
export function personTitles(credits: { cast?: Credit[]; crew?: Credit[] }, k: Known): LibTitle[] {
  const seen = new Map<string, LibTitle & { v: number }>()
  for (const c of [...(credits.cast ?? []), ...(credits.crew ?? []).filter((c) => c.job === 'Director' || c.job === 'Creator')]) {
    if ((c.media_type !== 'movie' && c.media_type !== 'tv') || c.adult || !c.poster_path || c.genre_ids?.some((g) => NOT_THEIR_WORK.has(g))) continue
    const t = fromTmdb(c.media_type, c)
    if (!seen.has(t.id)) seen.set(t.id, { ...decorate(k, t), v: c.vote_count ?? 0 })
  }
  return byScore([...seen.values()].sort((a, b) => b.v - a.v)).slice(0, 60).map(({ v: _v, ...t }) => t)
}

/** Search words for rows whose id or name isn't the word itself. */
const ROW_WORDS: Record<string, string> = { 'sci-fi': 'scifi', 'sci fi': 'scifi', 'science fiction': 'scifi', comedies: 'comedy', documentaries: 'documentary', new: 'new', latest: 'new', popular: 'trending' }
/** Languages a search can name ("korean"), beyond the library's own language rows. */
const LANGUAGES = ['fr', 'de', 'es', 'it', 'pt', 'ja', 'ko', 'zh', 'hi', 'tr', 'sv', 'da', 'no', 'fi', 'nl', 'pl', 'fa', 'he', 'th', 'id', 'el', 'cs', 'hu', 'ro', 'uk', 'ta', 'te', 'ml', 'cn']
const languageName = new Intl.DisplayNames(['en'], { type: 'language' })

export interface SearchDeps {
  known: Known
  /** TMDB GET (absent without TMDB_TOKEN). */
  tmdb?: <T>(path: string) => Promise<T>
  /** The provider's catalogue search. */
  vod?: (q: string) => Promise<VodItem[]>
}

const norm = (s: string) => s.normalize('NFD').replace(/\p{M}/gu, '').toLowerCase().replace(/\s+/g, ' ').trim()

/**
 * Titles for a search (R20). A category word gives its row ("arabic", "comedy"), or TMDB's discover for a
 * genre or language the rows don't have ("korean", "fantasy"), best score first. Anything else is TMDB's
 * title search (films and shows, its own order), then the provider's catalogue and the library's titles it
 * didn't find; at most 60.
 */
export async function searchTitles(query: string, d: SearchDeps): Promise<LibTitle[]> {
  const q = norm(query)
  if (!q) return []
  const lib = d.known.lib
  const row = lib?.rows.find((r) => r.id === (ROW_WORDS[q] ?? q) || norm(r.n) === q)
  if (row) return row.i.map((i) => lib!.titles[i])
  const genres = Object.entries(lib?.gn ?? {}).filter(([, n]) => norm(n) === q).map(([id]) => id)
  const lang = LANGUAGES.find((c) => norm(languageName.of(c) ?? '') === q)
  if (d.tmdb && (genres.length || lang)) {
    const by = lang ? `with_original_language=${lang}` : `with_genres=${genres.join('%7C')}`
    const lists = await Promise.all((['movie', 'tv'] as const).map((kind) =>
      d.tmdb!<{ results?: TmdbItem[] }>(`discover/${kind}?sort_by=popularity.desc&vote_count.gte=${kind === 'movie' ? 100 : 30}&${by}`)
        .then((r) => (r.results ?? []).map((x) => fromTmdb(kind, x)), () => [])))
    return byScore(lists.flat().map((t) => decorate(d.known, t)))
  }
  const [found, vod] = await Promise.all([
    d.tmdb?.<{ results?: Credit[] }>(`search/multi?query=${encodeURIComponent(query.trim())}&include_adult=false`).then((r) => r.results ?? [], () => []) ?? [],
    d.vod?.(query).catch(() => []) ?? [],
  ])
  const out = new Map<string, LibTitle>()
  for (const r of found) if ((r.media_type === 'movie' || r.media_type === 'tv') && r.poster_path) out.set(fromTmdb(r.media_type, r).id, fromTmdb(r.media_type, r))
  for (const v of vod) if (!out.has(v.titleId)) out.set(v.titleId, { id: v.titleId, k: 'm', n: v.name, ...(v.poster && { p: v.poster }) })
  const words = q.split(' ')
  for (const t of d.known.titles.values()) if (out.size < 60 && !out.has(t.id) && words.every((w) => norm(t.n).includes(w))) out.set(t.id, t)
  return [...out.values()].slice(0, 60).map((t) => decorate(d.known, t))
}
