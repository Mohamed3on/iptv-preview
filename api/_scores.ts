// Conviction scores for the library (KTD16), TrueScore's formula on Trakt's 1–10 votes: loved (9–10) minus
// hated (1–2), weighted by that net share, so volume counts. Below 50 votes a title is estimated from
// MDBList's Letterboxd (else IMDb) average, fitted nightly to Trakt's net share and scaled by the title's IMDb
// votes on Trakt's scale. Rows sort by score; a title with none sorts at its row's median. What the APIs said
// is kept in .cache/scores.json (refreshed weekly, daily for new releases), so a night without keys, quota or
// network only sorts by what is known. scripts/build-library.ts runs it all; without keys, rows keep their order.
import { readFile } from 'node:fs/promises'
import { fileURLToPath } from 'node:url'
import { writeAtomic } from './_fs.js'
import type { LibTitle, Library } from './_library.js'
import { parseTitleId } from './_tmdb.js'

/** What the APIs said about one title; times in ms. */
export interface ScoreEntry {
  /** Trakt id, 0 when Trakt doesn't know the title. */
  tk?: number
  /** IMDb id, from Trakt's lookup. */
  im?: string
  /** Trakt's votes for 1 to 10. */
  d?: number[]
  /** When Trakt was asked. */
  ta?: number
  /** MDBList's Letterboxd average (0–5), IMDb average (0–10) and IMDb votes, and when it was asked. */
  lb?: number
  ir?: number
  iv?: number
  ma?: number
  /** IMDb's own vote histogram, 1 to 10 (scraped, R25), and when. */
  h?: number[]
  ha?: number
}
export type ScoreCache = Record<string, ScoreEntry>
/** A title's score as the library carries it: [s] conviction, [sn] net share in %, [se] 1 when estimated, plus the averages shown. */
export type Score = Pick<LibTitle, 's' | 'sn' | 'se' | 'lb' | 'ir'>

/** Votes a Trakt score needs before it stands on its own. */
const MIN_VOTES = 50
/** Titles a fit needs. */
const MIN_FIT = 10
/** Titles a row keeps after sorting (the rest stay findable through search). */
export const ROW_CAP = 60

/** TrueScore's net score: net × (net / total), sign kept. */
export const netScore = (net: number, total: number) => (total > 0 ? Math.sign(net) * Math.round((net * net) / total) : 0)
const sum = (d: number[]) => d.reduce((a, b) => a + b, 0)
const median = (xs: number[]) => xs.slice().sort((a, b) => a - b)[Math.floor(xs.length / 2)]

/** Least squares y = a + b·x over [pts], or null with too few. */
function fit(pts: [number, number][]): ((x: number) => number) | null {
  if (pts.length < MIN_FIT) return null
  const n = pts.length, mx = sum(pts.map((p) => p[0])) / n, my = sum(pts.map((p) => p[1])) / n
  const vx = sum(pts.map(([x]) => (x - mx) ** 2))
  if (!vx) return null
  const b = sum(pts.map(([x, y]) => (x - mx) * (y - my))) / vx
  return (x) => my + b * (x - mx)
}

/** Every cached title's score, on Trakt's scale. */
export function scoreTitles(cache: ScoreCache): Map<string, Score> {
  const entries = Object.entries(cache)
  const traktTotal = (e: ScoreEntry) => (e.d ? sum(e.d) : 0)
  const thick = entries.map(([, e]) => e).filter((e) => traktTotal(e) >= MIN_VOTES)
  // Trakt votes per IMDb vote, so IMDb's volume lands on Trakt's scale.
  const ratios = thick.filter((e) => e.iv).map((e) => traktTotal(e) / e.iv!)
  const ratio = ratios.length ? median(ratios) : 0
  const share = (d: number[]) => (d[8] + d[9] - d[0] - d[1]) / sum(d)
  const fromLb = fit(thick.filter((e) => e.lb).map((e) => [e.lb!, share(e.d!)]))
  const fromIr = fit(thick.filter((e) => e.ir).map((e) => [e.ir!, share(e.d!)]))
  const out = new Map<string, Score>()
  for (const [id, e] of entries) {
    const shown = { ...(e.lb && { lb: e.lb }), ...(e.ir && { ir: e.ir }) }
    // Trakt's votes, pooled with IMDb's histogram brought to Trakt's scale when one was scraped.
    const d = Array.from({ length: 10 }, (_, i) => (e.d?.[i] ?? 0) + (e.h && ratio ? e.h[i] * ratio : 0))
    const total = sum(d), net = d[8] + d[9] - d[0] - d[1]
    const fitted = e.lb && fromLb ? fromLb(e.lb) : e.ir && fromIr ? fromIr(e.ir) : null
    if (total < MIN_VOTES && fitted !== null && e.iv && ratio) {
      const sh = Math.max(-1, Math.min(1, fitted))
      out.set(id, { s: Math.round(sh * Math.abs(sh) * e.iv * ratio), sn: Math.round(sh * 100), se: 1, ...shown })
    } else if (total > 0) out.set(id, { s: netScore(net, total), sn: Math.round((net / total) * 100), ...shown })
    else if (e.lb || e.ir) out.set(id, shown)
  }
  return out
}

/**
 * [lib] with [scores] on its titles and each row sorted by score (a title without one at the row's median),
 * cut to [cap] titles, then only the titles some row still holds.
 */
export function ranked(lib: Library, scores: Map<string, Score>, cap = ROW_CAP): Library {
  const all = lib.titles.map(({ s: _s, sn: _n, se: _e, lb: _l, ir: _i, ...t }) => ({ ...t, ...scores.get(t.id) }))
  const rows = lib.rows.map((r) => {
    const known = r.i.map((i) => all[i].s).filter((s): s is number => s !== undefined)
    const mid = known.length ? median(known) : 0
    return { ...r, i: (known.length ? r.i.slice().sort((a, b) => (all[b].s ?? mid) - (all[a].s ?? mid)) : r.i).slice(0, cap) }
  })
  const kept = [...new Set(rows.flatMap((r) => r.i))].sort((a, b) => a - b)
  const index = new Map(kept.map((old, i) => [old, i]))
  return { ...lib, titles: kept.map((i) => all[i]), rows: rows.map((r) => ({ ...r, i: r.i.map((i) => index.get(i)!) })) }
}

/** Title ids as the app shows them: every row's first card, then every row's second, and so on. */
export function onScreen(lib: Library): string[] {
  const ids = new Set<string>()
  for (let p = 0; p < Math.max(0, ...lib.rows.map((r) => r.i.length)); p++) for (const r of lib.rows) if (p < r.i.length) ids.add(lib.titles[r.i[p]].id)
  return [...ids]
}

export interface Keys { trakt?: string; mdblist?: string; scrape?: boolean }
export interface RefreshOpts {
  fetch?: typeof fetch
  now?: number
  /**
   * Milliseconds between one service's requests. Trakt allows 1000 per 5 minutes, but 3 a second for an hour got
   * the box's IP blocked by Trakt's Cloudflare (429, error 1015): one a second leaves the server's own Trakt calls
   * (sign-in, history) most of the budget. Trakt's backoff after a 429 is 30 × this, doubling.
   */
  gap?: number
  /** MDBList requests this run may use (the free tier has 1000 a day). */
  mdbMax?: number
  /** Called every so often, to keep what was learned if the run dies. */
  save?: () => Promise<void>
}

const DAY = 864e5
const TRAKT = 'https://api.trakt.tv'
const MDB = 'https://api.mdblist.com'
const IMDB = 'https://caching.graphql.imdb.com/'

/**
 * Asks Trakt, MDBList and (with [keys].scrape) IMDb about the titles in [ids], in that order (the order the app
 * shows them in), whose answers in [cache] are due: Trakt weekly, daily for [fresh] titles; MDBList monthly,
 * titles with under 50 Trakt votes first, within its quota; IMDb weekly. Updates [cache] in place.
 */
export async function refreshScores(ids: string[], fresh: Set<string>, cache: ScoreCache, keys: Keys, opts: RefreshOpts = {}) {
  const { fetch: get = fetch, now = Date.now(), gap = 1000, mdbMax = 1000, save } = opts
  const tmdb = ids.flatMap((id) => {
    const p = parseTitleId(id)
    return p && p.season == null ? [{ id, kind: p.kind, n: p.tmdb }] : []
  })
  const paced = () => {
    let next = 0
    return async () => {
      const wait = next - Date.now()
      next = Math.max(next, Date.now()) + gap
      if (wait > 0) await Bun.sleep(wait)
    }
  }
  let changed = 0
  const done = async () => { if (++changed % 200 === 0) await save?.() }
  const counts = { trakt: 0, mdblist: 0, imdb: 0 }

  if (keys.trakt) {
    const pace = paced()
    const headers = { 'content-type': 'application/json', 'trakt-api-version': '2', 'trakt-api-key': keys.trakt }
    /**
     * Trakt's JSON, null when it hasn't the title; throws on anything else. A 429 (Trakt's or its Cloudflare's 1015)
     * waits its Retry-After or a doubling backoff, whichever is longer; the third in a row throws "Trakt limited".
     */
    let limited = 0
    const trakt = async <T>(path: string): Promise<T | null> => {
      await pace()
      const r = await get(TRAKT + path, { headers })
      if (r.status === 429) {
        if (++limited >= 3) throw new Error('Trakt limited')
        await Bun.sleep(Math.max(Number(r.headers.get('retry-after')) || 0, (gap / 1000) * 30 * 2 ** (limited - 1)) * 1000)
        return trakt<T>(path)
      }
      limited = 0
      if (r.status === 404) return null
      if (!r.ok) throw new Error(`Trakt ${r.status}`)
      return r.json() as Promise<T>
    }
    let fails = 0
    for (const { id, kind, n } of tmdb) {
      const e = (cache[id] ??= {})
      if (e.ta && now - e.ta < (fresh.has(id) ? 1 : 7) * DAY) continue
      const type = kind === 'tv' ? 'show' : 'movie'
      try {
        if (!e.tk) {
          const hit = (await trakt<Record<string, { ids?: { trakt?: number; imdb?: string } }>[]>(`/search/tmdb/${n}?type=${type}`))?.[0]?.[type]?.ids
          e.tk = hit?.trakt ?? 0
          if (hit?.imdb) e.im = hit.imdb
        }
        if (e.tk) {
          const r = await trakt<{ distribution?: Record<string, number> }>(`/${type}s/${e.tk}/ratings`)
          e.d = Array.from({ length: 10 }, (_, i) => r?.distribution?.[i + 1] ?? 0)
        }
        e.ta = now
        counts.trakt++
        fails = 0
        await done()
      } catch (err) {
        // A bad key, Trakt down or limiting us: what's scored is kept, the rest wait for tomorrow.
        if (/Trakt (40[13]|limited)/.test(String(err)) || ++fails >= 5) { console.warn(`scores: ${err}; Trakt stopped after ${counts.trakt}`); break }
      }
    }
  }

  if (keys.mdblist) {
    const pace = paced()
    const thin = (id: string) => !cache[id]?.d || sum(cache[id].d!) < MIN_VOTES
    const due = tmdb.filter(({ id }) => !cache[id]?.ma || now - cache[id].ma! > 30 * DAY)
    let left = mdbMax
    for (const { id, kind, n } of [...due.filter((t) => thin(t.id)), ...due.filter((t) => !thin(t.id))]) {
      if (left-- <= 0) break
      await pace()
      const r = await get(`${MDB}/tmdb/${kind === 'tv' ? 'show' : 'movie'}/${n}?apikey=${keys.mdblist}`).catch(() => null)
      if (!r || r.status === 429 || r.status === 401 || r.status === 403) { console.warn(`scores: MDBList ${r?.status ?? 'unreachable'}; the rest wait for tomorrow`); break }
      if (!r.ok && r.status !== 404) continue
      const ratings = r.ok ? ((await r.json()) as { ratings?: { source: string; value?: number | null; votes?: number | null }[] }).ratings ?? [] : []
      const of = (source: string) => ratings.find((x) => x.source === source)
      const { lb: _l, ir: _i, iv: _v, ...rest } = cache[id] ?? {}
      cache[id] = { ...rest, ma: now, ...(of('letterboxd')?.value && { lb: of('letterboxd')!.value! }), ...(of('imdb')?.value && { ir: of('imdb')!.value! }), ...(of('imdb')?.votes && { iv: of('imdb')!.votes! }) }
      counts.mdblist++
      await done()
      if (r.headers.get('x-ratelimit-remaining') === '0') break
    }
  }

  if (keys.scrape) {
    const due = tmdb.map(({ id }) => id).filter((id) => cache[id]?.im && (!cache[id].ha || now - cache[id].ha! > 7 * DAY))
    for (let i = 0; i < due.length; i += 100) {
      const batch = due.slice(i, i + 100)
      const r = await get(IMDB, {
        method: 'POST',
        headers: { 'content-type': 'application/json', 'x-imdb-client-name': 'imdb-web-next-localized' },
        body: JSON.stringify({
          query: 'query($ids: [ID!]!) { titles(ids: $ids) { id aggregateRatingsBreakdown { histogram { histogramValues { rating voteCount } } } } }',
          variables: { ids: batch.map((id) => cache[id].im) },
        }),
      }).then((r) => (r.ok ? r.json() : null), () => null) as { data?: { titles?: ({ id: string; aggregateRatingsBreakdown?: { histogram?: { histogramValues?: { rating: number; voteCount: number }[] } } } | null)[] } } | null
      if (!r?.data?.titles) { console.warn('scores: IMDb histograms unavailable; skipped tonight'); break }
      const byImdb = new Map(r.data.titles.filter((t) => !!t).map((t) => [t.id, t.aggregateRatingsBreakdown?.histogram?.histogramValues ?? []]))
      for (const id of batch) {
        const h = Array<number>(10).fill(0)
        for (const { rating, voteCount } of byImdb.get(cache[id].im!) ?? []) if (rating >= 1 && rating <= 10) h[rating - 1] = voteCount || 0
        cache[id] = { ...cache[id], ha: now, ...(sum(h) && { h }) }
        counts.imdb++
      }
      await done()
    }
  }
  return counts
}

export const SCORES_FILE = fileURLToPath(new URL('../.cache/scores.json', import.meta.url))
export const loadScores = (file = SCORES_FILE): Promise<ScoreCache> => readFile(file, 'utf8').then((s) => JSON.parse(s) as ScoreCache, () => ({}))
/** Writes the cache atomically (temp + rename). */
export const saveScores = (cache: ScoreCache, file = SCORES_FILE) => writeAtomic(file, cache)
