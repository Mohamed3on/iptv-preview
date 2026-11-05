// The app's view of the provider's whole VOD catalogue (saved, trimmed, by each playlist pull; see
// vodCatalogueFile): the Blu-ray row beside the 4K groups, and search over every film, so a film
// with no 4K copy ("Eternal Sunshine of the Spotless Mind") can still be found and played.
import { readFile, stat } from 'node:fs/promises'
import { MOVIE_4K, movieGroup, plainCategory, vodCatalogueFile, type VodCatalogue, type XtreamConfig } from './_lib.js'
import { filmTitle } from './_tmdb.js'

/** An item in /api/app/movies' shape. */
export interface VodItem {
  id: number
  /** The film's title id (see _tmdb.ts). */
  titleId: string
  name: string
  poster: string
  group: string
  ext: string
}

/** The provider's 1080p Blu-ray films (multi-subs): one row after the 4K groups. */
const BLURAY = /^TOP MOVIES BLURAY/i
export const BLURAY_GROUP = '🎬 BLU-RAY MOVIES'

let cached: { mtime: number; catalogue: VodCatalogue; tmdb?: Map<number, string> } | null = null

/** The saved catalogue, read again only when a playlist pull has replaced it. */
async function catalogue(cfg: XtreamConfig): Promise<VodCatalogue> {
  const file = await vodCatalogueFile(cfg)
  const mtime = (await stat(file).catch(() => null))?.mtimeMs ?? 0
  if (cached?.mtime !== mtime) cached = { mtime, catalogue: JSON.parse(await readFile(file, 'utf8').catch(() => '{"cats":{},"vod":[]}')) }
  return cached.catalogue
}

/** The provider's tags before a title, often stacked: "TOP - ", "4K-FR - HDR - ", "EN-TOP - ", "4K -SC- ", "AR-SUBS - ". */
const TAG = /^([A-Z0-9+]{1,6}(?:\s?-[A-Z0-9+]{1,6})*)\s*-\s+/
/**
 * A title as the app shows it: tags off, except a first bare language ("EN - "), which the app makes a
 * badge; a list's numbering ("110. Amélie") and dotted file names ("Eternal.Sunshine.of.the.Spotless.Mind.2004") undone.
 */
function cleanName(raw: string): string {
  let s = raw.trim()
  let lang = ''
  for (let m = s.match(TAG); m; m = s.match(TAG)) {
    if (!lang && /^[A-Z]{2}$/.test(m[1])) lang = m[1]
    s = s.slice(m[0].length).trim()
  }
  s = s.replace(/^4K[\s-]*[A-Z]{2,3}\s+(?=\S)/, '').replace(/^\d{1,3}\.\s*/, '')
  if (!s.includes(' ') && s.includes('.')) s = s.replace(/\.((?:19|20)\d{2})$/, ' ($1)').replace(/\./g, ' ')
  return (lang ? `${lang} - ` : '') + (s.replace(/\s+/g, ' ') || raw.trim())
}

/** A film's title id by VOD id, from the catalogue's tmdb column (`iptv:<id>` for a film it lacks). */
export async function filmTitleOf(cfg: XtreamConfig): Promise<(id: number) => string> {
  await catalogue(cfg)
  const c = cached!
  c.tmdb ??= new Map(c.catalogue.vod.map((r) => [r[0], r[6]]))
  return (id) => filmTitle(c.tmdb!.get(id), id)
}

/** Every catalogue copy of a film: each entry with its TMDB id (`movie:<tmdb>`), or the one entry of an `iptv:<id>` title. Raw names keep the provider's tags. */
export async function filmEntries(cfg: XtreamConfig, title: string): Promise<{ id: number; name: string; category: string; ext: string }[]> {
  const tmdb = title.match(/^movie:(\d+)$/)?.[1]
  const vodId = Number(title.match(/^iptv:(\d+)$/)?.[1])
  if (!tmdb && !vodId) return []
  const { cats, vod } = await catalogue(cfg)
  return vod.filter((r) => (tmdb ? r[6] === tmdb : r[0] === vodId)).map(([id, name, , cat, ext]) => ({ id, name, category: cats[cat] ?? '', ext }))
}

/** A VOD category's name, "" when unknown. */
export async function vodCategoryName(cfg: XtreamConfig, id: number): Promise<string> {
  return (await catalogue(cfg)).cats[id] ?? ''
}

/** Every film in the Blu-ray category, newest upload first. */
export async function blurayMovies(cfg: XtreamConfig): Promise<VodItem[]> {
  const { cats, vod } = await catalogue(cfg)
  return vod
    .filter(([, , , cat]) => BLURAY.test(cats[cat] ?? ''))
    .sort((a, b) => b[5] - a[5])
    .map(([id, name, poster, , ext, , tmdb]) => ({ id, titleId: filmTitle(tmdb, id), name: cleanName(name), poster, group: BLURAY_GROUP, ext }))
}

/** Accents stripped, lowercased, punctuation to spaces. */
const norm = (s: string) => s.normalize('NFD').replace(/\p{M}/gu, '').toLowerCase().replace(/[^\p{L}\p{N}]+/gu, ' ').trim()
const YEAR = /\((\d{4})\)/

/** Which copy of a film to offer: 4K, then Blu-ray, then English, then the rest. */
function tier(cat: string, name: string): number {
  if (MOVIE_4K.test(cat)) return 0
  if (BLURAY.test(cat)) return 1
  if (/^EN\b/.test(cat) || /^EN\s+-/.test(name)) return 2
  return 3
}

/** A copy filed under another language ("FR - FILM 4K", "4K-FR - …"): offered after the others in its tier. */
const foreign = (cat: string, name: string) => /^(?!EN\b)[A-Z]{2}\b/.test(cat) || /^(?!EN\b)[A-Z]{2}\s*-/.test(name.replace(/^4K-/, ''))

/** Each TMDB film's best catalogue copy (by [tier], other languages last) as [VOD id, container], by title id. */
export async function bestFilmCopies(cfg: XtreamConfig): Promise<Map<string, [number, string]>> {
  const { cats, vod } = await catalogue(cfg)
  const best = new Map<string, [number, string, number]>()
  for (const [id, name, , cat, ext, , tmdb] of vod) {
    const title = filmTitle(tmdb, id)
    if (!title.startsWith('movie:')) continue
    const rank = tier(cats[cat] ?? '', name) * 2 + Number(foreign(cats[cat] ?? '', name))
    if (rank < (best.get(title)?.[2] ?? Infinity)) best.set(title, [id, ext, rank])
  }
  return new Map([...best].map(([t, [id, ext]]) => [t, [id, ext]]))
}

/**
 * Films in the whole catalogue whose title has every typed word, one per film (same title and year; a
 * copy with no year joins a dated one of the same title), each on its best copy; titles where every
 * word starts a word come first, then by the copy's tier, other languages last, then newest. At most [limit].
 */
export async function searchVod(cfg: XtreamConfig, query: string, limit = 40): Promise<VodItem[]> {
  const words = norm(query).split(' ').filter(Boolean)
  if (!words.length) return []
  const { cats, vod } = await catalogue(cfg)
  type Hit = { item: VodItem; key: string; starts: boolean; tier: number; foreign: boolean; added: number }
  const better = (a: Hit, b: Hit) => a.tier - b.tier || Number(a.foreign) - Number(b.foreign) || b.added - a.added
  const best = new Map<string, Hit>()
  for (const [id, name, poster, cat, ext, added, tmdb] of vod) {
    const clean = cleanName(name)
    const title = norm(clean.replace(YEAR, ''))
    if (!words.every((w) => title.includes(w))) continue
    const catName = cats[cat] ?? ''
    const key = `${title.replace(/^[a-z]{2} /, '')}|${clean.match(YEAR)?.[1] ?? ''}`
    const hit: Hit = {
      item: { id, titleId: filmTitle(tmdb, id), name: clean, poster, group: MOVIE_4K.test(catName) ? movieGroup(catName) : BLURAY.test(catName) ? BLURAY_GROUP : `🎬 ${plainCategory(catName)}`, ext },
      key,
      starts: words.every((w) => ` ${title}`.includes(` ${w}`)),
      tier: tier(catName, name),
      foreign: foreign(catName, name),
      added,
    }
    const was = best.get(key)
    if (!was || better(hit, was) < 0) best.set(key, hit)
  }
  const dated = new Set([...best.keys()].filter((k) => !k.endsWith('|')).map((k) => k.slice(0, k.lastIndexOf('|'))))
  return [...best.values()]
    .filter((h) => !h.key.endsWith('|') || !dated.has(h.key.slice(0, -1)))
    .sort((a, b) => Number(b.starts) - Number(a.starts) || better(a, b))
    .slice(0, limit)
    .map((h) => h.item)
}
