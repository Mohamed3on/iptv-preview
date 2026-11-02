// Builders for the Fire TV app's own feeds (/api/app/*). They read the same curated
// channels and guide as the TiviMate feeds, so both apps always show the same thing.
import { Database } from 'bun:sqlite'
import { chanKey, COMPS, couldBeLiveMatch, learnedCompetitions, saneFps, unxml, VPN_HOST, type Channel, type XtreamConfig } from './_lib.js'
import { medianMbps } from './_quality.js'
import { QUALITY } from './quality-cache.js'

export interface AppChannel {
  id: number
  name: string
  logo: string
  group: string
  tvgId: string
  slot: boolean
  /** The channel's own (non-🏆) group, when it is in the feed; 🏆-only channels have none. */
  home?: string
  /** Feeds of one channel share a family: home group + chanKey. */
  family: string
  /** Position in the family, best feed first (server quality order). */
  rank: number
  /** Probed video height, null when never measured. */
  h: number | null
  /** Name-marker quality tier (qualityScore). */
  q: number
  /** Event slot whose current name reads as a fixture. */
  fixture?: true
  badges: string[]
  /** The feed's bitrate in Mbps, measured while the app played it; absent when never measured. */
  mbps?: number
}

export interface AppFeed {
  groups: string[]
  route: { plain: string; vpn: string; user: string; pass: string }
  channels: AppChannel[]
}

const isComp = (group: string) => group.startsWith('🏆')

function badges(streamId: number): string[] {
  const m = QUALITY[String(streamId)]
  if (!m) return []
  const out: string[] = []
  if (m.h >= 2160) out.push('4K')
  else if (m.h >= 1080) out.push('FHD')
  const fps = saneFps(m.fps)
  if (fps >= 50) out.push(`${Math.round(fps)}fps`)
  if (m.atmos) out.push('Atmos')
  return out
}

export function buildAppFeed(cfg: XtreamConfig, channels: Channel[]): AppFeed {
  const live = channels.filter((c) => !c.vodExt)
  const home = new Map<number, string>()
  for (const c of live) if (!isComp(c.group) && !home.has(c.streamId)) home.set(c.streamId, c.group)
  const familyOf = (c: Channel) => `${home.get(c.streamId) ?? c.group}|${chanKey(c.name)}`
  // Members in server order: home groups first (they are quality-sorted), then 🏆-only channels.
  // A 🏆 copy shares its original's streamId, so it is never counted as a backup.
  const members = new Map<string, number[]>()
  for (const c of [...live.filter((c) => !isComp(c.group)), ...live.filter((c) => isComp(c.group))]) {
    const f = familyOf(c)
    const list = members.get(f) ?? members.set(f, []).get(f)!
    if (!list.includes(c.streamId)) list.push(c.streamId)
  }
  return {
    groups: [...new Set(live.map((c) => c.group))],
    route: { plain: cfg.host, vpn: VPN_HOST, user: cfg.user, pass: cfg.pass },
    channels: live.map((c) => {
      const family = familyOf(c)
      return {
        id: c.streamId,
        name: c.name,
        logo: c.logo,
        group: c.group,
        tvgId: c.tvgId,
        slot: c.isEventSlot,
        home: home.get(c.streamId),
        family,
        rank: members.get(family)!.indexOf(c.streamId),
        h: QUALITY[String(c.streamId)]?.h ?? null,
        q: c.q ?? 2,
        ...(c.isEventSlot && couldBeLiveMatch(c.name) ? { fixture: true as const } : {}),
        badges: badges(c.streamId),
        mbps: medianMbps(QUALITY[String(c.streamId)]),
      }
    }),
  }
}

/** XMLTV "20261101120000 +0200" → epoch seconds. */
const xmltvSeconds = (v: string) => {
  const m = v.match(/^(\d{4})(\d\d)(\d\d)(\d\d)(\d\d)(\d\d)\s*([+-]\d\d)(\d\d)/)
  return m ? Date.parse(`${m[1]}-${m[2]}-${m[3]}T${m[4]}:${m[5]}:${m[6]}${m[7]}:${m[8]}`) / 1000 : NaN
}
const ATTR = { start: /\bstart="([^"]*)"/, stop: /\bstop="([^"]*)"/, channel: /\bchannel="([^"]*)"/ }

/** Search form of a title: lowercased, accents stripped ("Bayern München" → "bayern munchen"). */
export const searchNorm = (s: string) => s.normalize('NFD').replace(/\p{M}/gu, '').toLowerCase()

/**
 * The app's guide as a gzip SQLite file, parsed from the same XMLTV TiviMate gets so it
 * carries the same corrections. Rows flag name-as-title filler (synthetic) and titles
 * that could be a live match, and name the 🏆 group of the competition a live match
 * belongs to (comp, by the same rules as the 🏆 groups); programmes that ended over 2h
 * ago are dropped.
 */
const compGroup = new Map(COMPS.map((c) => [c.key, c.group]))

export function buildAppGuide(channels: Channel[], xmltv: string, now = Date.now()): Uint8Array {
  const filler = new Set(channels.map((c) => `${c.tvgId}\n${c.name}\n${c.group}`))
  const db = new Database(':memory:')
  db.run(`CREATE TABLE programmes (tvg_id TEXT NOT NULL, start INTEGER NOT NULL, stop INTEGER NOT NULL,
    title TEXT NOT NULL, norm TEXT NOT NULL, descr TEXT NOT NULL, synthetic INTEGER NOT NULL, match INTEGER NOT NULL, comp TEXT)`)
  const insert = db.prepare('INSERT INTO programmes VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)')
  const cutoff = now / 1000 - 2 * 3600
  db.transaction(() => {
    for (const m of xmltv.matchAll(/<programme\b([^>]*)>([\s\S]*?)<\/programme>/g)) {
      const attr = (k: keyof typeof ATTR) => unxml(m[1].match(ATTR[k])?.[1] ?? '')
      const stop = xmltvSeconds(attr('stop'))
      if (!(stop > cutoff)) continue
      const id = attr('channel')
      const title = unxml(m[2].match(/<title\b[^>]*>([\s\S]*?)<\/title>/)?.[1] ?? '').trim()
      const descr = unxml(m[2].match(/<desc\b[^>]*>([\s\S]*?)<\/desc>/)?.[1] ?? '').trim()
      const synthetic = filler.has(`${id}\n${title}\n${descr}`)
      const comp = synthetic ? undefined : learnedCompetitions(title)[0]
      insert.run(id, xmltvSeconds(attr('start')), stop, title, searchNorm(title), descr,
        synthetic ? 1 : 0, couldBeLiveMatch(title) ? 1 : 0, comp ? compGroup.get(comp)! : null)
    }
  })()
  db.run('CREATE INDEX programmes_channel ON programmes (tvg_id, start)')
  db.run('CREATE INDEX programmes_start ON programmes (start)')
  const file = db.serialize()
  db.close()
  return Bun.gzipSync(file)
}
