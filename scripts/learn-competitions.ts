// Learn which channels carry which competition from the EPG. The guides only reach
// ~36h ahead, so one look can't see a cup round or a UEFA week — instead this scans
// our own /api/epg for live-match programmes and accumulates (tvg-id -> competition
// -> last-seen date) into api/competition-cache.ts, dropping entries older than
// 60 days. Event slots (ppv.*) are skipped: their name IS the event, so only their
// current name counts (see staticCompetitions).
// Usage: bun scripts/learn-competitions.ts   (PLAYLIST_TOKEN from env / .env.local;
//        EPG_ENDPOINT overrides the deployed endpoint)
import { learnedCompetitions } from '../api/_lib.js'
import { LEARNED } from '../api/competition-cache.js'

const endpoint = process.env.EPG_ENDPOINT ?? 'https://iptv-preview.vercel.app/api/epg'
const token = process.env.PLAYLIST_TOKEN
if (!token && !endpoint.startsWith('file:')) throw new Error('PLAYLIST_TOKEN missing')
// A file: endpoint (an EPG saved locally) needs no token.
const resp = await fetch(endpoint.startsWith('file:') ? endpoint : `${endpoint}?t=${token}`)
if (!resp.ok) throw new Error(`${endpoint} -> HTTP ${resp.status}`)
const xml = await resp.text()

const KEEP_DAYS = 60
const cutoff = new Date(Date.now() - KEEP_DAYS * 864e5).toISOString().slice(0, 10)
const next: Record<string, Record<string, string>> = {}
for (const [id, comps] of Object.entries(LEARNED)) {
  for (const [k, seen] of Object.entries(comps)) if (seen >= cutoff) (next[id] ??= {})[k] = seen
}
let hits = 0
const unescape = (s: string) => s.replace(/&apos;/g, "'").replace(/&quot;/g, '"').replace(/&lt;/g, '<').replace(/&gt;/g, '>').replace(/&amp;/g, '&')
for (const m of xml.matchAll(/<programme start="(\d{4})(\d{2})(\d{2})[^"]*"[^>]*channel="([^"]*)"[^>]*>\s*<title[^>]*>([^<]*)<\/title>/g)) {
  const [, y, mo, d, id, title] = m
  if (id.startsWith('ppv.')) continue
  const seen = `${y}-${mo}-${d}`
  for (const k of learnedCompetitions(unescape(title))) {
    const e = (next[id] ??= {})
    if (!e[k] || e[k] < seen) e[k] = seen
    hits++
  }
}
// Sorted keys -> deterministic file, minimal diffs.
const sorted = Object.fromEntries(
  Object.keys(next).sort().map((id) => [id, Object.fromEntries(Object.entries(next[id]).sort())]),
)
const header = await Bun.file(new URL('../api/competition-cache.ts', import.meta.url)).text()
const out = header.slice(0, header.indexOf('export const LEARNED')) +
  `export const LEARNED: Record<string, Record<string, string>> = ${JSON.stringify(sorted, null, 1)}\n`
await Bun.write(new URL('../api/competition-cache.ts', import.meta.url), out)
console.log(`${hits} live-match programme hits; ${Object.keys(sorted).length} channels in cache`)
