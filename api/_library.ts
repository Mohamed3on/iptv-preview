// The Movies & Shows library (KTD17): title-first rows built nightly by scripts/build-library.ts from TMDB
// (trending, new releases, genres, then original language: English, Arabic, Russian, everything else), with
// the provider's 4K and Blu-ray films and its shows merged in, each title with its best known picture tier.
// Kept in .cache/library.json; /api/app/library serves it from memory, or the provider's own rows when it
// is missing or older than 3 days (TMDB down or unset). Its shape is [Library]: titles once each, rows as
// indexes into them, short keys, image paths without TMDB's host and size.
import { readFile, stat } from 'node:fs/promises'
import { fileURLToPath } from 'node:url'
import { writeAtomic } from './_fs.js'
import { savedVodChannels, MOVIE_4K, type XtreamConfig } from './_lib.js'
import { measuredStream, pictureTier, probedPicture, type Tier } from './_quality-labels.js'
import { measuredFilms, showList } from './_shows.js'
import { filmTitle, showTitle, type Kind } from './_tmdb.js'
import { bestFilmCopies, blurayMovies, filmTitleOf } from './_vod.js'

/** A film or show; empty fields are left out. */
export interface LibTitle {
  /** Title id (see _tmdb.ts). */
  id: string
  /** 'm' a film, 't' a show. */
  k: 'm' | 't'
  n: string
  y?: number
  /** Poster: a TMDB path ("/abc.jpg", shown at img + size + path) or a full URL (the provider's own). */
  p?: string
  /** Backdrop, a TMDB path. */
  b?: string
  /** Original language, ISO 639-1. */
  l?: string
  /** TMDB genre ids (films' and shows' differ; names in Library.gn). */
  g?: number[]
  /** The best picture tier of any copy seen; absent when none is known. */
  t?: Tier
  /** 1 when [t] is a category's claim (its resolution's ceiling), not measured. */
  e?: 1
  /** Combined score (api/_scores.ts) on /10, one decimal, and × 10 as an integer (the sort key); absent when unknown. */
  s10?: number
  s?: number
  /** IMDb average (/10) and votes, Letterboxd average (0–5) and votes, from MDBList. */
  ir?: number
  iv?: number
  lb?: number
  lv?: number
  /** TrueScore's adjusted score, all-time and recent net-loved ratios (0–1), for a film in its store (api/_scores.ts). */
  ta?: number
  tr?: number
  tq?: number
  /** A film the provider has: its best copy's VOD id and container, which Play starts when the box can't answer (KTD13). */
  v?: number
  x?: string
  /** Runtime in minutes (a show's: an episode's), from TMDB's kept details; absent when unknown. */
  rt?: number
  /** TMDB popularity: picks a row's candidates without a score in the build (library-full.json); not served. */
  pp?: number
}
export interface Row { id: string; n: string; /** Indexes into Library.titles. */ i: number[] }
export interface Library { v: 1; src: 'tmdb' | 'provider'; at: string; img: string; gn: Record<string, string>; titles: LibTitle[]; rows: Row[] }

const IMG = 'https://image.tmdb.org/t/p/'
/** A TMDB image URL as its path, so the app picks the size; any other URL as is. */
const image = (url: string) => url.match(/^https?:\/\/image\.tmdb\.org\/t\/p\/[^/]+(\/[^/]+)$/)?.[1] ?? url

/** A TMDB list result or details, as far as the library needs it. */
export interface TmdbItem {
  id: number
  title?: string
  name?: string
  release_date?: string
  first_air_date?: string
  poster_path?: string | null
  backdrop_path?: string | null
  original_language?: string
  genre_ids?: number[]
  genres?: { id: number }[]
  media_type?: string
  adult?: boolean
  popularity?: number
}

export function fromTmdb(kind: Kind, r: TmdbItem): LibTitle {
  const g = r.genre_ids ?? r.genres?.map((x) => x.id) ?? []
  return {
    id: kind === 'tv' ? showTitle(r.id, 0) : filmTitle(r.id, 0),
    k: kind === 'tv' ? 't' : 'm',
    n: r.title ?? r.name ?? '',
    y: Number((r.release_date || r.first_air_date || '').slice(0, 4)) || undefined,
    p: r.poster_path || undefined,
    b: r.backdrop_path || undefined,
    l: r.original_language || undefined,
    g: g.length ? g : undefined,
    pp: r.popularity,
  }
}

/** A row: TMDB queries whose results lead it, then every library title it [has]. */
export interface RowDef { id: string; n: string; q: [Kind, string][]; has?: (t: LibTitle) => boolean }

const NO_TALK = 'without_genres=10763,10764,10767' // news, reality, talk
/** Language rows: [code, name, minimum film votes on TMDB (shows a third)]. */
const LANGS: [string, string, number][] = [['en', 'English', 300], ['ar', 'Arabic', 10], ['ru', 'Russian', 50]]
/** Genre rows: [id, name, TMDB film genre, TMDB show genre]. */
const GENRES: [string, string, number?, number?][] = [
  ['action', 'Action', 28, 10759], ['comedy', 'Comedy', 35, 35], ['drama', 'Drama', 18, 18], ['thriller', 'Thriller', 53],
  ['crime', 'Crime', 80, 80], ['scifi', 'Science fiction', 878, 10765], ['horror', 'Horror', 27], ['romance', 'Romance', 10749],
  ['animation', 'Animation', 16, 16], ['documentary', 'Documentary', 99, 99],
]

export function rowDefs(today = new Date()): RowDef[] {
  const day = (ago: number) => new Date(today.getTime() - ago * 864e5).toISOString().slice(0, 10)
  const pop = 'sort_by=popularity.desc'
  const films = (q: string): [Kind, string] => ['movie', `discover/movie?${pop}&${q}`]
  const shows = (q: string): [Kind, string] => ['tv', `discover/tv?${pop}&${q}&${NO_TALK}`]
  return [
    // TrueScore's films by its recent-adjusted, then its all-time score: filled and ordered by scripts/build-library.ts.
    { id: 'ts-recent', n: 'TrueScore · recent', q: [] },
    { id: 'ts-all', n: 'TrueScore · all-time', q: [] },
    { id: 'trending', n: 'Trending', q: [['movie', 'trending/movie/week'], ['tv', 'trending/tv/week']] },
    // Films out on digital or disc in the last 60 days; shows with an episode out in them.
    { id: 'new', n: 'New releases', q: [films(`with_release_type=4%7C5&release_date.gte=${day(60)}&release_date.lte=${day(0)}&vote_count.gte=20`), shows(`air_date.gte=${day(60)}&air_date.lte=${day(0)}&vote_count.gte=100`)] },
    ...GENRES.map(([id, n, film, show]): RowDef => ({
      id, n,
      q: [...(film ? [films(`with_genres=${film}&vote_count.gte=300`)] : []), ...(show ? [shows(`with_genres=${show}&vote_count.gte=100`)] : [])],
      has: (t) => !!t.g?.includes((t.k === 'm' ? film : show) ?? -1),
    })),
    ...LANGS.map(([l, n, votes]): RowDef => ({
      id: l, n,
      q: [films(`with_original_language=${l}&vote_count.gte=${votes}`), shows(`with_original_language=${l}&vote_count.gte=${Math.ceil(votes / 3)}`)],
      has: (t) => t.l === l,
    })),
    { id: 'other', n: 'Other languages', q: [], has: (t) => !LANGS.some(([l]) => l === t.l) },
  ]
}

const RANK: Record<Tier, number> = { Reference: 0, Great: 1, OK: 2, Soft: 3 }
/** [base] with [other]'s tier when that one is better known: measured beats claimed, then the higher tier. */
function withTier(base: LibTitle, other: LibTitle): LibTitle {
  const better = !!other.t && (!base.t || (!other.e && !!base.e) || (!other.e === !base.e && RANK[other.t] < RANK[base.t]))
  if (!better) return base
  const { t: _t, e: _e, ...rest } = base
  return { ...rest, t: other.t, ...(other.e && { e: 1 as const }) }
}

function toLibrary(src: Library['src'], titles: Map<string, LibTitle>, rows: { id: string; n: string; ids: string[] }[], gn: Record<string, string>): Library {
  const list = [...titles.values()]
  const index = new Map(list.map((t, i) => [t.id, i]))
  return {
    v: 1, src, at: new Date().toISOString(), img: IMG, gn, titles: list,
    rows: rows.map(({ id, n, ids }) => ({ id, n, i: [...new Set(ids.map((x) => index.get(x)!))] })).filter((r) => r.i.length),
  }
}

/** A row that comes in its own order (TrueScore's, by one exact score), kept as built, whole, rather than sorted by [s] and cut (the app leaves out watched titles, then shows 60). */
export const ordered = (rowId: string) => rowId.startsWith('ts-')

/** Candidates a row keeps, the most convincing first (api/_scores.ts [s]; TMDB popularity after, for titles without), before scoring cuts it to ROW_CAP. */
export const ROW_CANDIDATES = 150

/** One copy the provider has: a 4K or Blu-ray film, or a show, with the row it sits in today. */
export interface ProviderTitle { t: LibTitle; group: string }

/**
 * The library from TMDB's rows ([seeds], one list per def) and the provider's titles (already given their TMDB
 * details where known), keeping tiers seen in the [prev] build; [scores] pick each row's candidates.
 */
export function assemble(defs: RowDef[], seeds: LibTitle[][], provider: ProviderTitle[], prev: Library | null, gn: Record<string, string>, scores = new Map<string, { s?: number }>()): Library {
  const titles = new Map<string, LibTitle>()
  for (const t of seeds.flat()) if (!titles.has(t.id)) titles.set(t.id, t)
  for (const { t } of provider) titles.set(t.id, titles.has(t.id) ? withTier(titles.get(t.id)!, t) : t)
  for (const p of prev?.titles ?? []) if (titles.has(p.id)) titles.set(p.id, withTier(titles.get(p.id)!, p))
  const all = [...titles.values()]
  const s = (id: string) => scores.get(id)?.s, key = (id: string) => s(id) ?? titles.get(id)!.pp ?? -1
  const candidates = (ids: string[]) => [...new Set(ids)].sort((a, b) => Number(s(b) !== undefined) - Number(s(a) !== undefined) || key(b) - key(a)).slice(0, ROW_CANDIDATES)
  return toLibrary('tmdb', titles, defs.map((d, i) => ({ id: d.id, n: d.n, ids: ordered(d.id) ? seeds[i].map((t) => t.id) : candidates([...seeds[i].map((t) => t.id), ...(d.has ? all.filter(d.has).map((t) => t.id) : [])]) })), gn)
}

/** The provider's 4K and Blu-ray films (tier measured on play, else the category's claim) and its shows. */
export async function providerTitles(cfg: XtreamConfig): Promise<ProviderTitle[]> {
  const [vod, titleOf, bluray, shows, measured] = await Promise.all([
    savedVodChannels(cfg), filmTitleOf(cfg), blurayMovies(cfg).catch(() => []), showList(cfg).catch(() => null), measuredFilms(),
  ])
  const film = (vodId: number, id: string, n: string, poster: string, group: string, claim: Tier): ProviderTitle => {
    const m = measured[String(vodId)]
    const t = m && pictureTier(probedPicture(measuredStream(m, m.kbps))!)
    return { t: { id, k: 'm', n, ...(poster && { p: image(poster) }), ...(t ? { t } : { t: claim, e: 1 as const }) }, group }
  }
  return [
    ...vod.filter((c) => MOVIE_4K.test(c.group)).map((c) => film(c.streamId, titleOf(c.streamId), c.name, c.logo, c.group, 'Reference')),
    ...bluray.map((m) => film(m.id, m.titleId, m.name, m.poster, m.group, 'Great')),
    ...(shows?.movies ?? []).map((s) => ({ t: { id: s.titleId, k: 't' as const, n: s.name, ...(s.poster && { p: image(s.poster) }) }, group: s.group })),
  ]
}

/** [lib] with each film's best provider copy ([LibTitle.v], [LibTitle.x]). */
export async function withProviderCopies(lib: Library, cfg: XtreamConfig): Promise<Library> {
  const best = await bestFilmCopies(cfg)
  return { ...lib, titles: lib.titles.map((t) => (best.has(t.id) ? { ...t, v: best.get(t.id)![0], x: best.get(t.id)![1] } : t)) }
}

/** The provider's rows as a library, for when TMDB's is missing or stale. */
export async function providerLibrary(cfg: XtreamConfig): Promise<Library> {
  const titles = new Map<string, LibTitle>()
  const rows = new Map<string, string[]>()
  for (const { t, group } of await providerTitles(cfg)) {
    titles.set(t.id, titles.has(t.id) ? withTier(titles.get(t.id)!, t) : t)
    rows.get(group)?.push(t.id) ?? rows.set(group, [t.id])
  }
  return withProviderCopies(toLibrary('provider', titles, [...rows].map(([n, ids], i) => ({ id: `p${i}`, n, ids })), {}), cfg)
}

export const LIBRARY_FILE = fileURLToPath(new URL('../.cache/library.json', import.meta.url))
/** The build's unscored library (every title; rows hold their ROW_CANDIDATES, not yet cut to ROW_CAP): the next build's memory of TMDB details and tiers. */
export const LIBRARY_FULL_FILE = fileURLToPath(new URL('../.cache/library-full.json', import.meta.url))

/** Writes the library atomically (temp + rename). */
export const saveLibrary = (lib: Library, file = LIBRARY_FILE) => writeAtomic(file, lib)

const STALE_MS = 3 * 864e5
const PROVIDER_MS = 30 * 60e3
export interface Served { body: Uint8Array; etag: string }
const served = (body: Uint8Array): Served => ({ body, etag: `"${Bun.hash(body).toString(36)}"` })
let fromFile: (Served & { mtime: number }) | null = null
let fallback: { at: number; job: Promise<Served> } | null = null

/** The library's bytes: library.json, read again only when it changes; else [provider]'s, rebuilt at most every 30 minutes. */
export async function servedLibrary(provider: () => Promise<Library>, file = LIBRARY_FILE): Promise<Served> {
  const mtime = (await stat(file).catch(() => null))?.mtimeMs ?? 0
  if (Date.now() - mtime < STALE_MS) {
    if (fromFile?.mtime !== mtime) fromFile = { mtime, ...served(await readFile(file)) }
    return fromFile
  }
  fromFile = null
  if (!fallback || Date.now() - fallback.at > PROVIDER_MS) {
    const job = provider().then((lib) => served(Buffer.from(JSON.stringify(lib))))
    fallback = { at: Date.now(), job }
    job.catch(() => fallback?.job === job && (fallback = null))
  }
  return fallback.job
}
