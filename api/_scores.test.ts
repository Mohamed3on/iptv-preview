import { expect, test } from 'bun:test'
import type { Library } from './_library'
import { ranked, refreshScores, scoreTitles, type ScoreCache } from './_scores'

/** Trakt votes 1..10 with [loved] 10s, [hated] 1s, the rest 7s. */
const votes = (total: number, loved: number, hated = 0) => [hated, 0, 0, 0, 0, 0, total - loved - hated, 0, 0, loved]

test('a Trakt distribution scores (loved − hated) × net share', () => {
  // {10: 500, 9: 300, 1: 50, 2: 50, others: 100}
  const d = [50, 50, 20, 20, 20, 20, 20, 0, 300, 500]
  expect(scoreTitles({ a: { d } }).get('a')).toEqual({ s: 490, sn: 70 })
})

test('a thin title is estimated from its Letterboxd average fitted to Trakt, scaled by its IMDb votes', () => {
  // Eleven well-voted titles where net share = 0.4 × Letterboxd − 1, and Trakt has 1 vote per 100 on IMDb.
  const cache: ScoreCache = {}
  for (let k = 0; k <= 10; k++) cache[`t${k}`] = { d: votes(1000, 40 * k), lb: 2.5 + 0.1 * k, iv: 100_000 }
  cache.thin = { d: votes(30, 20), lb: 4.1, iv: 100_000 }
  cache.thinner = { lb: 4.1, iv: 20_000 }
  const s = scoreTitles(cache)
  // share 0.64 → 0.64 × 0.64 × 100,000 × 0.01
  expect(s.get('thin')).toEqual({ s: 410, sn: 64, se: 1, lb: 4.1 })
  expect(s.get('thinner')!.s).toBe(82)
})

test('rows sort by score, a title with none at its row median; rows are cut and unreferenced titles dropped', () => {
  const lib = {
    titles: ['none', 'a', 'b', 'c', 'x'].map((id) => ({ id, k: 'm', n: id })),
    rows: [{ id: 'en', n: 'English', i: [0, 1, 2, 3] }, { id: 'ar', n: 'Arabic', i: [4, 0] }],
  } as Library
  const out = ranked(lib, scoreTitles({ a: { d: votes(100, 100) }, b: { d: votes(100, 50) }, c: { d: votes(100, 10) } }), 3)
  const row = (id: string) => out.rows.find((r) => r.id === id)!.i.map((i) => out.titles[i].id)
  expect(row('en')).toEqual(['a', 'none', 'b'])
  expect(row('ar')).toEqual(['x', 'none']) // no scores: order kept
  expect(out.titles.map((t) => t.id)).toEqual(['none', 'a', 'b', 'x'])
  // Without any scores (no keys), every row keeps its order.
  expect(ranked(lib, new Map()).rows[0].i).toEqual([0, 1, 2, 3])
})

test('Trakt and MDBList answers are cached; an exhausted MDBList quota keeps earlier scores and fills the rest next night', async () => {
  const json = (body: unknown, status = 200) => new Response(JSON.stringify(body), { status })
  let mdbLeft = 1
  const asked: string[] = []
  const fake = (async (url: string) => {
    asked.push(url)
    if (url.includes('/search/tmdb/1')) return json([{ type: 'movie', score: null, movie: { title: 'A', year: 2010, ids: { trakt: 11, slug: 'a-2010', imdb: 'tt1', tmdb: 1 } } }])
    if (url.includes('/search/tmdb/2')) return json([])
    if (url.includes('/movies/11/ratings')) return json({ rating: 7.3, votes: 20, distribution: { 1: 2, 2: 0, 3: 0, 4: 0, 5: 0, 6: 0, 7: 8, 8: 0, 9: 0, 10: 10 } })
    if (url.includes('api.mdblist.com')) {
      if (mdbLeft-- <= 0) return json({ error: 'Daily API limit exceeded!' }, 429)
      return json({ id: 1, title: 'X', ratings: [{ source: 'imdb', value: 7.8, score: 78, votes: 5000 }, { source: 'letterboxd', value: 3.9, score: 78, votes: null }, { source: 'tomatoes', value: null }] })
    }
    throw new Error(url)
  }) as unknown as typeof fetch
  const cache: ScoreCache = { 'movie:3': { ma: 1, lb: 4.4, ir: 8.1, iv: 9000 } } // asked an earlier night
  const now = Date.UTC(2026, 10, 5)
  const opts = { fetch: fake, gap: 0, now }
  await refreshScores(['movie:1', 'movie:2', 'iptv:5'], new Set(), cache, { trakt: 'k', mdblist: 'm' }, opts)
  expect(cache['movie:1']).toMatchObject({ tk: 11, im: 'tt1', d: [2, 0, 0, 0, 0, 0, 8, 0, 0, 10], ta: now, lb: 3.9, ir: 7.8, iv: 5000 })
  expect(cache['movie:2']).toEqual({ tk: 0, ta: now }) // Trakt doesn't know it; MDBList ran out
  expect(scoreTitles(cache).get('movie:3')).toEqual({ lb: 4.4, ir: 8.1 })
  expect(scoreTitles(cache).get('movie:1')).toMatchObject({ s: 3, sn: 40 }) // thin, no fit yet: Trakt's own
  // Next night: Trakt isn't due for a week, MDBList fills what it missed.
  mdbLeft = 5
  asked.length = 0
  await refreshScores(['movie:1', 'movie:2'], new Set(), cache, { trakt: 'k', mdblist: 'm' }, { ...opts, now: now + 864e5 })
  expect(asked).toEqual(['https://api.mdblist.com/tmdb/movie/2?apikey=m'])
  expect(cache['movie:2']).toMatchObject({ lb: 3.9 })
  expect(cache['movie:3']).toMatchObject({ lb: 4.4 })
})

test('Trakt limiting us (429, Cloudflare 1015) stops its phase after three in a row, keeping what was scored', async () => {
  let asked = 0
  const fake = (async (url: string) => {
    asked++
    if (url.includes('/search/tmdb/1')) return new Response(JSON.stringify([{ movie: { ids: { trakt: 11 } } }]))
    if (url.includes('/movies/11/ratings')) return new Response(JSON.stringify({ distribution: { 10: 5 } }))
    return new Response('error code: 1015', { status: 429, headers: { 'retry-after': '0' } })
  }) as unknown as typeof fetch
  const cache: ScoreCache = {}
  const counts = await refreshScores(['movie:1', 'movie:2', 'movie:3'], new Set(), cache, { trakt: 'k' }, { fetch: fake, gap: 0, now: 1 })
  expect(counts.trakt).toBe(1)
  expect(cache['movie:1'].ta).toBe(1)
  expect(cache['movie:2'].ta).toBeUndefined() // asked again next night
  expect(asked).toBe(5) // movie:1's two, then three 429s
})
