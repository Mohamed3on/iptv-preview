// Builds the Movies & Shows library (api/_library.ts) into .cache/library.json: TMDB's rows, then the
// provider's 4K and Blu-ray films and its shows, each looked up on TMDB once (a title the last build already
// knew keeps its details). Run nightly on the box by deploy/iptv-library.timer. Without TMDB_TOKEN, or when
// every TMDB list fails, nothing is written and the app keeps the last library (or the provider's rows).
// Then it scores the titles (api/_scores.ts: Trakt, MDBList, and IMDb's histograms with SCRAPE_IMDB=1), sorts each
// row by score and cuts it to 60. Each key is optional: without one its source is skipped, and rows keep their order.
// First it reads the owner's Trakt and Letterboxd history into .cache/history.json (api/_history.ts).
// Usage: bun scripts/build-library.ts   (XTREAM_*, TMDB_TOKEN, TRAKT_CLIENT_ID, MDBLIST_KEY from env / .env.local)
import { readFile } from 'node:fs/promises'
import { configFromEnv } from '../api/_lib.js'
import { assemble, fromTmdb, LIBRARY_FILE, LIBRARY_FULL_FILE, providerTitles, rowDefs, saveLibrary, withProviderCopies, type Library, type LibTitle, type TmdbItem } from '../api/_library.js'
import { loadScores, onScreen, ranked, refreshScores, saveScores, scoreTitles } from '../api/_scores.js'
import { parseTitleId, tmdbGet, type Kind } from '../api/_tmdb.js'
import { loadHistory, refreshHistory, saveHistory } from '../api/_history.js'
import { readOnlyTokens } from '../api/_trakt.js'

// The owner's history first (seconds): Trakt read whole, recommendations included, and the Letterboxd feed.
// Only the server refreshes Trakt's tokens.
readOnlyTokens()
await refreshHistory(await loadHistory(), { full: true }).then((h) => saveHistory(h)).catch((e) => console.log(`history not read: ${e}`))

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

const prev = await readFile(LIBRARY_FULL_FILE, 'utf8').catch(() => readFile(LIBRARY_FILE, 'utf8')).then((s) => JSON.parse(s) as Library, () => null)
// TMDB details by title id: this run's rows, then the last build's (without its tiers and scores).
const known = new Map<string, LibTitle>()
for (const { t: _t, e: _e, s: _s, sn: _n, se: _se, lb: _l, ir: _i, ...meta } of [...seeds.flat(), ...(prev?.titles ?? [])]) if (meta.l && !known.has(meta.id)) known.set(meta.id, meta)
const cfg = configFromEnv()
const provider = await providerTitles(cfg)
const missing = [...new Set(provider.map((p) => p.t.id))].filter((id) => parseTitleId(id) && !known.has(id))
await pool(missing, 6, async (id) => {
  const { kind, tmdb } = parseTitleId(id)!
  const r = await tmdbGet<TmdbItem>(`${kind}/${tmdb}`).catch(() => null)
  if (r) known.set(id, fromTmdb(kind, r))
})

const full = await withProviderCopies(assemble(defs, seeds, provider.map(({ t, group }) => ({ group, t: known.has(t.id) ? { ...known.get(t.id)!, ...(t.t && { t: t.t }), ...(t.e && { e: t.e }) } : t })), prev, gn), cfg)
await saveLibrary(full, LIBRARY_FULL_FILE)
// Rows sorted by the scores known so far go out at once. The APIs are asked in that order (about an hour on
// the first nights, at one Trakt request a second), then the rows are sorted again.
const scores = await loadScores()
await saveLibrary(ranked(full, scoreTitles(scores)))
const fresh = new Set(full.rows.find((r) => r.id === 'new')?.i.map((i) => full.titles[i].id))
const env = process.env
const asked = await refreshScores(onScreen(ranked(full, scoreTitles(scores), Infinity)), fresh, scores,
  { trakt: env.TRAKT_CLIENT_ID, mdblist: env.MDBLIST_KEY, scrape: env.SCRAPE_IMDB === '1' },
  { mdbMax: Number(env.MDBLIST_DAILY) || 1000, save: () => saveScores(scores) })
await saveScores(scores)
const lib = ranked(full, scoreTitles(scores))
await saveLibrary(lib)
console.log(`library: ${full.titles.length} titles in ${full.rows.length} rows, ${lib.titles.length} on show; ${missing.length} provider titles looked up; ${failed} TMDB pages failed; asked Trakt ${asked.trakt}, MDBList ${asked.mdblist}, IMDb ${asked.imdb}`)
