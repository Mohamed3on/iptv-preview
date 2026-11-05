// The Fire TV app's shows (series). Every show in a category of the user's languages (English,
// Arabic, German, Spanish, Italian), once per language: a show filed in several of a language's
// categories keeps the 4K one's copy. A category's 4K and plain versions are one group. The
// provider's series list is ~72MB, so it is pulled at most once a day and the trimmed list kept
// on disk (the old copy is served if a pull fails); a show's episodes are fetched when it's opened.
import { readFile, stat, writeFile } from 'node:fs/promises'
import { apiGet, plainCategory, type XtreamConfig } from './_lib.js'

interface Category {
  category_id: string
  category_name: string
}

interface Series {
  series_id: number
  name: string
  cover?: string
  category_id: string
  tmdb?: string
}

/** The same shape as /api/app/movies, so the app reads both with one parser. */
export interface ShowList {
  groups: string[]
  movies: { id: number; name: string; poster: string; group: string }[]
}

export interface Episode {
  id: number
  season: number
  episode: number
  title: string
  ext: string
  /** Running time in seconds; 0 when the provider doesn't say. */
  secs: number
  /** A still from the episode; empty when the provider has none. */
  image: string
}

const FILE = new URL('../.cache/shows.json', import.meta.url)
const MAX_AGE_MS = 24 * 3600_000
const K4 = /⁴ᴷ|³⁸⁴⁰|\b4K\b|3840|\bUHD\b|2160|فائقة? الوضوح/i
const SKIP = /PODCAST|بودكاست|\(TR\)/i
const ENGLISH = /^(NETFLIX|DISNEY\+|APPLE\+|AMAZON|HBO MAX|OSN\+|PARAMOUNT\+|PEACOCK|SHOWTIME|SKY$|MARVEL|ENGLISH|NICKELODEON|DISCOVERY\+|FIFA\+|CRUNCHYROLL)/
/** The big services' main rows lead each language; the rest follow in the provider's order. */
const FIRST = ['NETFLIX SERIES', 'HBO MAX SERIES', 'APPLE+ SERIES', 'DISNEY+ SERIES', 'AMAZON SERIES', 'PARAMOUNT+', 'ENGLISH SERIES']
/** The provider's tag before a title: "EN - ", "4K-AR - ", "D+ - ", "AR-SUBS - ". */
const NAME_PREFIX = /^(?:4K-)?[A-Z0-9+]{1,6}(?:-SUBS)?\s+-\s+/
/** Language order: English, Arabic, then German, Spanish, Italian. */
const LANGS = ['en', 'ar', 'de', 'es', 'it'] as const

function language(cat: string): (typeof LANGS)[number] | null {
  if (SKIP.test(cat)) return null
  if (cat.startsWith('GERMANY')) return 'de'
  if (cat.startsWith('ESPAÑA')) return 'es'
  if (cat.startsWith('ITALY')) return 'it'
  if (ENGLISH.test(cat)) return 'en'
  // Arabic-script names are Arabic categories, except the Persian, Kurdish and Hebrew ones (prefixed).
  if (/[؀-ۿ]/.test(cat) && !/^(PERSIAN|KURDISH|HEBREW)/.test(cat)) return 'ar'
  return null
}

export function buildShowList(cats: Category[], series: Series[]): ShowList {
  const catOrder = new Map(cats.map((c, i) => [c.category_id, i]))
  const catName = new Map(cats.map((c) => [c.category_id, c.category_name]))
  type Pick = { s: Series; cat: string; lang: number; k4: boolean }
  const rank = (p: Pick) => {
    const i = FIRST.indexOf(plainCategory(p.cat))
    return i < 0 ? FIRST.length : i
  }
  const best = new Map<string, Pick>()
  for (const s of series) {
    const cat = catName.get(String(s.category_id))
    const lang = cat ? language(cat) : null
    if (!cat || !lang || !String(s.name ?? '').trim()) continue
    const key = `${lang}|${s.tmdb && s.tmdb !== '0' ? s.tmdb : String(s.name).trim().toLowerCase()}`
    const k4 = K4.test(cat)
    if (!best.has(key) || (k4 && !best.get(key)!.k4)) best.set(key, { s, cat, lang: LANGS.indexOf(lang), k4 })
  }
  const picks = [...best.values()].sort(
    (a, b) => a.lang - b.lang || rank(a) - rank(b) || catOrder.get(String(a.s.category_id))! - catOrder.get(String(b.s.category_id))!,
  )
  const movies = picks.map(({ s, cat }) => ({ id: s.series_id, name: String(s.name).trim().replace(NAME_PREFIX, ''), poster: s.cover ?? '', group: plainCategory(cat) }))
  return { groups: [...new Set(movies.map((m) => m.group))], movies }
}

let pulling: Promise<ShowList> | null = null

/** The show list, pulled again once the saved copy is a day old. */
export async function showList(cfg: XtreamConfig): Promise<ShowList> {
  const saved = await readFile(FILE, 'utf8').then((t) => JSON.parse(t) as ShowList).catch(() => null)
  const age = Date.now() - ((await stat(FILE).catch(() => null))?.mtimeMs ?? 0)
  if (saved && age < MAX_AGE_MS) return saved
  pulling ??= (async () => {
    const [cats, series] = await Promise.all([
      apiGet<Category[]>(cfg, 'get_series_categories'),
      apiGet<Series[]>(cfg, 'get_series'),
    ])
    const list = buildShowList(cats, series)
    if (!list.movies.length) throw new Error('no shows')
    await writeFile(FILE, JSON.stringify(list))
    return list
  })().finally(() => (pulling = null))
  return pulling.catch((e) => {
    if (saved) return saved // the provider sometimes answers empty for hours; keep the last good list
    throw e
  })
}

interface SeriesInfo {
  info?: { plot?: string; genre?: string; cast?: string; rating?: string | number; backdrop_path?: string[] | string }
  episodes?: Record<string, { id: string; episode_num: number | string; title?: string; container_extension?: string; season?: number; info?: { duration_secs?: number | string; movie_image?: string } }[]>
}

/** The first of the provider's backdrops (a list, sometimes one string); "" when none. */
const firstBackdrop = (b: string[] | string | undefined) => String((Array.isArray(b) ? b[0] : b) ?? '').trim()
/** The first few names of a cast list. */
const castOf = (c: string | undefined) => String(c ?? '').split(',').map((s) => s.trim()).filter(Boolean).slice(0, 4).join(', ')
const ratingOf = (r: string | number | undefined) => (Number(r) > 0 ? Number(r).toFixed(1) : '')

/** A show's plot, backdrop, genres, cast and rating, and its episodes in season and episode order. */
export async function showEpisodes(cfg: XtreamConfig, seriesId: number) {
  const info = await apiGet<SeriesInfo>(cfg, `get_series_info&series_id=${seriesId}`)
  const episodes = Object.entries(info.episodes ?? {})
    .flatMap(([season, eps]) =>
      eps.map((e) => ({
        id: Number(e.id),
        season: Number(e.season ?? season),
        episode: Number(e.episode_num),
        // "A+ - Severance (2022) (US) - S01E01 - Good News About Hell" → "Good News About Hell"; empty when untitled.
        title: String(e.title ?? '').match(/S\d+\s*E\d+\s*-\s*(.+)$/i)?.[1]?.trim() ?? '',
        ext: e.container_extension || 'mkv',
        secs: Number(e.info?.duration_secs) || 0,
        image: String(e.info?.movie_image ?? '').trim(),
      })),
    )
    .filter((e) => e.id > 0)
    .sort((a, b) => a.season - b.season || a.episode - b.episode)
  const i = info.info ?? {}
  return { plot: String(i.plot ?? '').trim(), backdrop: firstBackdrop(i.backdrop_path), genre: String(i.genre ?? '').trim(), cast: castOf(i.cast), rating: ratingOf(i.rating), episodes }
}

interface VodInfo {
  info?: { plot?: string; description?: string; genre?: string; duration_secs?: number | string; rating?: string | number; backdrop_path?: string[] | string; cast?: string; actors?: string; director?: string }
}

/** A film's plot, running time (seconds, 0 when unknown), genres, rating, backdrop, cast and director ("" when unknown). */
export async function movieInfo(cfg: XtreamConfig, vodId: number) {
  const info = (await apiGet<VodInfo>(cfg, `get_vod_info&vod_id=${vodId}`)).info ?? {}
  return {
    plot: String(info.plot || info.description || '').trim(),
    secs: Number(info.duration_secs) || 0,
    genre: String(info.genre ?? '').trim(),
    rating: ratingOf(info.rating),
    backdrop: firstBackdrop(info.backdrop_path),
    cast: castOf(info.cast || info.actors),
    director: String(info.director ?? '').trim(),
  }
}
