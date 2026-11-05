// Builds the Movies & Shows library (api/_library.ts) into .cache/library.json: TMDB's rows, then the
// provider's 4K and Blu-ray films and its shows, each looked up on TMDB once (a title the last build already
// knew keeps its details). Run nightly on the box by deploy/iptv-library.timer. Without TMDB_TOKEN, or when
// every TMDB list fails, nothing is written and the app keeps the last library (or the provider's rows).
// Usage: bun scripts/build-library.ts   (XTREAM_*, TMDB_TOKEN from env / .env.local)
import { readFile } from 'node:fs/promises'
import { configFromEnv } from '../api/_lib.js'
import { assemble, fromTmdb, LIBRARY_FILE, providerTitles, rowDefs, saveLibrary, type Library, type LibTitle, type TmdbItem } from '../api/_library.js'
import { tmdbGet, type Kind } from '../api/_tmdb.js'

if (!process.env.TMDB_TOKEN) {
  console.log('TMDB_TOKEN unset: library not built')
  process.exit(0)
}

/** [fn] over [items], [n] at a time (TMDB allows about 40 requests a second). */
async function pool<T, R>(items: T[], n: number, fn: (x: T) => Promise<R>): Promise<R[]> {
  const out: R[] = []
  let next = 0
  await Promise.all(Array.from({ length: n }, async () => {
    for (let i = next++; i < items.length; i = next++) out[i] = await fn(items[i])
  }))
  return out
}

const PAGES = 2
let failed = 0
async function list([kind, path]: [Kind, string]): Promise<LibTitle[]> {
  const pages = await Promise.all(Array.from({ length: PAGES }, (_, i) =>
    tmdbGet<{ results: TmdbItem[] }>(`${path}${path.includes('?') ? '&' : '?'}page=${i + 1}`).catch(() => (failed++, { results: [] }))))
  return pages.flatMap((p) => p.results).filter((r) => (r.media_type ?? kind) === kind && r.poster_path && !r.adult).map((r) => fromTmdb(kind, r))
}

const defs = rowDefs()
// A row's queries interleaved (film, show, film, …), so a mixed row isn't all films first.
const seeds = await pool(defs, 3, async (d) => {
  const lists = await Promise.all(d.q.map(list))
  return Array.from({ length: Math.max(0, ...lists.map((l) => l.length)) }, (_, i) => lists.map((l) => l[i])).flat().filter((t): t is LibTitle => !!t)
})
if (!seeds.some((s) => s.length)) throw new Error(`TMDB answered no list (${failed} failed): library kept as it was`)

const gn: Record<string, string> = Object.fromEntries((await Promise.all(['movie', 'tv'].map((k) =>
  tmdbGet<{ genres: { id: number; name: string }[] }>(`genre/${k}/list`).then((r) => r.genres, () => [])))).flat().map((g) => [g.id, g.name]))

const prev = await readFile(LIBRARY_FILE, 'utf8').then((s) => JSON.parse(s) as Library, () => null)
// TMDB details by title id: this run's rows, then the last build's (without its tiers and scores).
const known = new Map<string, LibTitle>()
for (const { t: _t, e: _e, s: _s, ...meta } of [...seeds.flat(), ...(prev?.titles ?? [])]) if (meta.l && !known.has(meta.id)) known.set(meta.id, meta)
const provider = await providerTitles(configFromEnv())
const missing = [...new Set(provider.map((p) => p.t.id))].filter((id) => /^(movie|tv):/.test(id) && !known.has(id))
await pool(missing, 6, async (id) => {
  const [kind, tmdb] = id.split(':') as [Kind, string]
  const r = await tmdbGet<TmdbItem>(`${kind}/${tmdb}`).catch(() => null)
  if (r) known.set(id, fromTmdb(kind, r))
})

const lib = assemble(defs, seeds, provider.map(({ t, group }) => ({ group, t: known.has(t.id) ? { ...known.get(t.id)!, ...(t.t && { t: t.t }), ...(t.e && { e: t.e }) } : t })), prev, gn)
await saveLibrary(lib)
console.log(`library: ${lib.titles.length} titles in ${lib.rows.length} rows; ${missing.length} provider titles looked up; ${failed} TMDB pages failed`)
