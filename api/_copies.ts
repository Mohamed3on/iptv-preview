// Every copy of a film or episode the app could play (KTD1, KTD3, KTD5): the provider's (IPTV) and the
// torrents Torrentio knows (Real-Debrid candidates; the stick checks which are cached). The box
// describes, the stick ranks. IPTV copies never wait on Torrentio: it gets a short budget, after which
// the answer says `sources.rd: 'pending'` and the lookup carries on into the cache for the next ask.
import { mkdir, readFile, rename } from 'node:fs/promises'
import { dirname } from 'node:path'
import { fileURLToPath } from 'node:url'
import { getJson } from './_http.js'
import type { XtreamConfig } from './_lib.js'
import { claimedPicture, measuredStream, streamLabels, type Measured, type ProbedStream } from './_quality-labels.js'
import { flagLanguages, parseRelease, type Attrs } from './_release-name.js'
import { episodeEntries, measuredFilms } from './_shows.js'
import { tmdbTitle } from './_tmdb.js'
import { filmEntries } from './_vod.js'

export interface IptvCopy extends Attrs {
  src: 'iptv'
  /** VOD or episode stream id. */
  id: number
  ext: string
  /** A film's provider name, tags included ("4K-EN - Inception (2010)"); an episode's show category. */
  name: string
  /** Where the attributes come from: the app's own reading, the provider's probe, or the name's and category's claim. */
  basis: 'measured' | 'probed' | 'claimed'
  /** 0 unknown. */
  kbps: number
}

export interface RdCopy extends Attrs {
  src: 'rd'
  hash: string
  /** Torrentio's file index and file name inside the torrent. */
  file?: number
  filename?: string
  /** The release name. */
  name: string
  /** Bytes. */
  size: number
  seeds: number
  /** Torrentio's "[RD+]": someone resolved it on Real-Debrid lately, so it is likely ready. Absent: unknown. */
  cached?: true
  /** Size over the title's TMDB runtime; 0 unknown. */
  kbps: number
}

type Status = 'ok' | 'pending' | 'unavailable'
export interface Copies {
  titleId: string
  /** ISO 639-1 from TMDB; '' unknown. */
  originalLanguage: string
  /** Minutes (an episode's for shows); 0 unknown. */
  runtime: number
  sources: { iptv: Status; rd: Status }
  copies: (IptvCopy | RdCopy)[]
}

// The provider's language tag ("4K-DE - …", "AR-SUBS - …", category "ES - PELÍCULAS"). Dubbing markets
// name the audio; subtitling markets keep the original audio and add their subtitles; English copies
// keep the original and claim nothing.
const DUBBED: Record<string, string> = { DE: 'de', ES: 'es', FR: 'fr', IT: 'it', PL: 'pl', RU: 'ru', TR: 'tr', IN: 'hi', PT: 'pt' }
const SUBBED: Record<string, string> = { AR: 'ar', GR: 'el', NL: 'nl', SE: 'sv', IS: 'is', BG: 'bg', AL: 'sq', EX: 'hr', IR: 'fa', KU: 'ku' }

function withTag(a: Attrs, tag: string): Attrs {
  const t = tag.toUpperCase()
  if (DUBBED[t]) return { ...a, langs: [...new Set([DUBBED[t], ...a.langs])] }
  if (SUBBED[t]) return { ...a, subs: [...new Set([SUBBED[t], ...(a.subs ?? [])])] }
  return a
}

/** The first language tag of a name ("4K-EN - …", "DE-DO - …") or its category ("ES - …"); '' for none. */
function tagOf(name: string, category: string): string {
  const tags = [...(name.match(/^([A-Z0-9+-]{2,12})\s+-\s/)?.[1].split('-') ?? []), category.match(/^([A-Z]{2})\s*-/)?.[1] ?? '']
  return tags.find((t) => t === 'EN' || DUBBED[t] || SUBBED[t]) ?? ''
}

/** A probed stream's attributes, read through its labels ("4K", "Dolby Vision", "HEVC", "Atmos", "E-AC3 5.1"). */
const probed = (s: ProbedStream): Attrs => parseRelease(streamLabels(s).filter((l) => !l.endsWith('Mbps')).join(' '))

/** A catalogue copy of a film: as the app measured it, else what its name and category claim. */
export function filmCopy(e: { id: number; name: string; category: string; ext: string }, m?: Measured): IptvCopy {
  const base = { src: 'iptv' as const, id: e.id, ext: e.ext, name: e.name }
  const tag = tagOf(e.name, e.category)
  if (m) return { ...base, basis: 'measured', ...withTag(probed(measuredStream(m)), tag), kbps: m.kbps ?? 0 }
  const a = parseRelease(`${e.name}\n${e.category}`)
  return { ...base, basis: 'claimed', ...withTag({ ...a, res: a.res || (/^4K\b/.test(e.name) ? 2160 : claimedPicture(e.category)?.height ?? 0) }, tag), kbps: 0 }
}

/** The provider's copies of a film (`movie:<tmdb>`, `iptv:<id>`) or an episode (`…:s<n>e<n>`, one per language its show is listed in). */
export async function iptvCopies(cfg: XtreamConfig, title: string): Promise<IptvCopy[]> {
  const ep = title.match(/^(.+):s(\d+)e(\d+)$/)
  if (ep) {
    return (await episodeEntries(cfg, ep[1], Number(ep[2]), Number(ep[3]))).map((e) => {
      const known = !!(e.probe?.video && Object.keys(e.probe.video).length)
      return { src: 'iptv', id: e.id, ext: e.ext, name: e.category, basis: known ? 'probed' : 'claimed', ...withTag(known ? probed(e.probe!) : parseRelease(e.category), e.lang), kbps: Math.round(Number(e.probe?.bitrate) || 0) }
    })
  }
  const [entries, measured] = await Promise.all([filmEntries(cfg, title), measuredFilms()])
  return entries.map((e) => filmCopy(e, measured[String(e.id)]))
}

interface Stream { name?: string; title?: string; infoHash?: string; fileIdx?: number; url?: string; behaviorHints?: { filename?: string } }
type Candidate = Omit<RdCopy, 'kbps'>
const UNIT: Record<string, number> = { KB: 2 ** 10, MB: 2 ** 20, GB: 2 ** 30, TB: 2 ** 40 }

/**
 * Torrentio's streams as candidates, one per torrent file; CAM, telesync, screener and 3D releases left
 * out. A title reads "<release>[\n<file in a pack>]\n👤 seeds 💾 size ⚙️ tracker[\n<audio flags>]"; the file name
 * (behaviorHints) is read with it, as it often names the audio the release doesn't. The
 * debrid list carries hash and file index only in its resolve URL (`/resolve/realdebrid/<key>/<hash>/null/<fileIdx>/…`),
 * which is read for them and then dropped with the key it holds; "[RD+]" marks a copy Torrentio saw resolve lately.
 */
export function torrentioCandidates(streams: Stream[]): Candidate[] {
  const seen = new Set<string>()
  return streams.flatMap((s) => {
    const [label = '', quality = ''] = (s.name ?? '').split('\n')
    const lines = (s.title ?? '').split('\n')
    const stats = lines.findIndex((l) => l.includes('💾'))
    const resolve = s.url?.match(/\/resolve\/realdebrid\/[^/]+\/([0-9a-f]{40})\/[^/]+\/([^/]+)\//i)
    const hash = (s.infoHash ?? resolve?.[1] ?? '').toLowerCase()
    const file = s.fileIdx ?? (/^\d+$/.test(resolve?.[2] ?? '') ? Number(resolve![2]) : undefined)
    const key = `${hash}:${file}`
    if (!hash || stats < 1 || seen.has(key) || /^(cam|telesync|telecine|scr|3d)/i.test(quality)) return []
    seen.add(key)
    const size = lines[stats].match(/💾 ([\d.]+) ([KMGT]B)/)
    const flags = lines.slice(stats + 1).join(' ')
    const a = parseRelease([...lines.slice(0, stats), quality, s.behaviorHints?.filename ?? ''].join('\n'))
    const subs = [...(a.subs ?? []), ...(/multi subs/i.test(flags) ? ['multi'] : [])]
    return [{
      src: 'rd' as const,
      hash,
      ...(file != null && { file }),
      ...(s.behaviorHints?.filename && { filename: s.behaviorHints.filename }),
      name: lines[0],
      size: size ? Math.round(Number(size[1]) * UNIT[size[2]]) : 0,
      seeds: Number(lines[stats].match(/👤 (\d+)/)?.[1] ?? 0),
      ...(label.startsWith('[RD+]') && { cached: true as const }),
      ...a,
      langs: [...new Set([...a.langs, ...flagLanguages(flags)])],
      ...(subs.length ? { subs: [...new Set(subs)] } : {}),
    }]
  })
}

/** Writes [data] to [file] atomically (temp + rename), one write at a time per file. */
const writes = new Map<string, Promise<void>>()
function writeAtomic(file: string, data: unknown) {
  const next = (writes.get(file) ?? Promise.resolve()).then(async () => {
    await mkdir(dirname(file), { recursive: true })
    await Bun.write(file + '.tmp', JSON.stringify(data))
    await rename(file + '.tmp', file)
  }).catch(() => {})
  writes.set(file, next)
  return next
}
const readJson = <T>(file: string, empty: T) => readFile(file, 'utf8').then((s) => JSON.parse(s) as T).catch(() => empty)

// Torrentio answers per IMDb id (and season and episode), asked with the Real-Debrid key so copies carry
// its "[RD+]" flag (Torrentio makes no Real-Debrid call for a list), else, without a key or when that
// fails, bare. Cached 2 h, as the flags change, in this process and on disk (so a restart stays warm); an
// older answer is served at once while a fresh one is fetched, and kept a week for when Torrentio
// fails. A failed lookup isn't retried for 5 minutes. The key never leaves this function.
const FRESH_MS = 2 * 3600_000
const KEEP_MS = 7 * 864e5
const RETRY_MS = 5 * 60_000
const cacheFile = () => process.env.TORRENTIO_CACHE_FILE ?? fileURLToPath(new URL('../.cache/torrentio.json', import.meta.url))
type Entry = { at: number; copies: Candidate[] }
let store: Promise<Record<string, Entry>> | null = null
const pending = new Map<string, Promise<Candidate[]>>()
const failed = new Map<string, number>()

async function torrentio(path: string): Promise<Candidate[]> {
  const all = await (store ??= readJson<Record<string, Entry>>(cacheFile(), {}))
  const hit = all[path]
  if ((hit && Date.now() - hit.at < FRESH_MS) || Date.now() - (failed.get(path) ?? 0) < RETRY_MS) {
    if (hit) return hit.copies
    throw new Error('Torrentio failed recently')
  }
  let job = pending.get(path)
  if (!job) {
    const key = process.env.REAL_DEBRID_TOKEN
    const list = (prefix: string) => getJson<{ streams?: Stream[] }>(`https://torrentio.strem.fun/${prefix}stream/${path}.json`, { timeoutMs: 8000, retries: 1 })
    job = (key ? list(`realdebrid=${key}/`).catch(() => list('')) : list(''))
      .then((r) => {
        all[path] = { at: Date.now(), copies: torrentioCandidates(r.streams ?? []) }
        for (const [k, e] of Object.entries(all)) if (Date.now() - e.at > KEEP_MS) delete all[k]
        writeAtomic(cacheFile(), all)
        return all[path].copies
      }, (e) => {
        failed.set(path, Date.now())
        throw e
      })
      .finally(() => pending.delete(path))
    pending.set(path, job)
  }
  if (hit) {
    job.catch(() => {})
    return hit.copies
  }
  return job
}

// Torrent hashes Real-Debrid refused here (451 infringing_file), posted by the stick: dropped from every
// title's candidates for 60 days, so no prewarm tries them again.
const INFRINGING_MS = 60 * 864e5
const infringingFile = () => process.env.RD_INFRINGING_FILE ?? fileURLToPath(new URL('../.cache/rd-infringing.json', import.meta.url))
let refused: Promise<Record<string, number>> | null = null
const infringing = () => (refused ??= readJson<Record<string, number>>(infringingFile(), {}))

/** Keeps [hashes] as refused; true when any was new. */
export async function markInfringing(hashes: string[]): Promise<boolean> {
  const all = await infringing()
  const fresh = hashes.map((h) => h.toLowerCase()).filter((h) => !all[h])
  if (!fresh.length) return false
  for (const h of fresh) all[h] = Date.now()
  for (const [h, at] of Object.entries(all)) if (Date.now() - at > INFRINGING_MS) delete all[h]
  await writeAtomic(infringingFile(), all)
  return true
}

/**
 * A title's copies: the IPTV ones (given, so the caller picks how they're found) and the Real-Debrid
 * candidates, which get [waitMs] before the answer goes out without them (`sources.rd: 'pending'`).
 */
export async function titleCopies(title: string, iptv: Promise<IptvCopy[]>, waitMs = 2500): Promise<Copies> {
  const m = title.match(/^(movie|tv):(\d+)/)
  const meta = m ? tmdbTitle(m[1] as 'movie' | 'tv', m[2]) : Promise.resolve(null)
  const rd = meta.then(async (t): Promise<RdCopy[]> => {
    if (!m) return [] // a provider-only title: nothing to look up
    if (!t) throw new Error('no TMDB details')
    if (!t.imdb) return []
    const ep = title.match(/:s(\d+)e(\d+)$/)
    const [copies, refused] = await Promise.all([torrentio(ep ? `series/${t.imdb}:${ep[1]}:${ep[2]}` : `movie/${t.imdb}`), infringing()])
    const now = Date.now()
    return copies
      .filter((c) => !(now - (refused[c.hash] ?? 0) < INFRINGING_MS))
      .map((c) => ({ ...c, kbps: t.runtime > 0 ? Math.round((c.size * 8) / (t.runtime * 60) / 1000) : 0 }))
  })
  const late = Bun.sleep(waitMs).then(() => 'pending' as const)
  const settle = <T>(p: Promise<T>) => Promise.race([p.then((v) => ({ v }), () => 'unavailable' as const), late])
  const [i, r, t] = await Promise.all([iptv.then((v) => ({ v }), () => 'unavailable' as const), settle(rd), settle(meta)])
  const tmdb = typeof t === 'object' ? t.v : null
  return {
    titleId: title,
    originalLanguage: tmdb?.originalLanguage ?? '',
    runtime: tmdb?.runtime ?? 0,
    sources: { iptv: typeof i === 'object' ? 'ok' : i, rd: typeof r === 'object' ? 'ok' : r },
    copies: [...(typeof i === 'object' ? i.v : []), ...(typeof r === 'object' ? r.v : [])],
  }
}
