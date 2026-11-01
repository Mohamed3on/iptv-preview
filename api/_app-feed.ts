// Builders for the Fire TV app's own feeds (/api/app/*). They read the same curated
// channels and guide as the TiviMate feeds, so both apps always show the same thing.
import { chanKey, couldBeLiveMatch, saneFps, VPN_HOST, type Channel, type XtreamConfig } from './_lib.js'
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
      }
    }),
  }
}
