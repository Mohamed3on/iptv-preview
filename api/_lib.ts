// Shared logic for the trimmed StrongTV playlist + EPG endpoints.
// Curation: KEEP ids + auto-include rules -> mapped into viewer-friendly
// BUCKETS; channels quality-sorted (FHD+ first), language as tie-break
// (EN > AR > DE/ES), dead PPV slots dropped, catch-up flags passed through.
// UFC PPV replays + the provider's 4K/UHD movie libraries (all languages) are
// appended as on-demand VOD entries. Live kids channels (EN/US/AR/ES/DE, plus
// Russian kids pulled by name from the general RU category) get their own buckets.
// Credentials come from env (XTREAM_HOST, XTREAM_USER, XTREAM_PASS, PLAYLIST_TOKEN).

// Real probed video quality (height/fps) keyed by streamId, so buckets sort by
// ACTUAL resolution×fps instead of the provider's unreliable "UHD"/"RAW" markers
// (many "UHD 3840P" feeds are really 1080p30 or 720p). Refreshed offline by
// scripts/probe-quality.ts; a missing entry just falls back to the name marker.
import { QUALITY } from './quality-cache.js'

// Provider category_ids to keep.
export const KEEP: number[] = [
  // World Cup
  2343, // 8K| WORLD CUP 2026 8K
  2361, // 8K| WORLD CUP 2026 UHD 3840P
  2352, // 8K| WORLD CUP PPV 2026 8K
  2346, // 8K| WORLD CUP 2026 REPLAY 8K
  2353, // UK| WORLD CUP PPV
  2354, // US| WORLD CUP PPV (FOX/Telemundo 60fps)
  2355, // DE| FUSSBALL.TV WORLD CUP VIP
  2362, // ES| DAZN MUNDIAL
  1958, // ES| DAZN EXCLUSIVE MUNDIAL HD/RAW
  547,  // ES| DAZN MUNDIAL PPV
  1334, // ES| SOCCER MUNDIAL PPV
  // UK sports
  1729, // UK| SPORT HD VIP (Sky Sports)
  1728, // UK| TNT SPORT HD VIP
  1830, // UK| TNT SPORT EVENT
  1964, // UK| NOW TV SPORT HD/RAW
  1965, // UK| NOW TV SPORT UHD
  1441, // UK| SKY SPORT+ PPV RAW
  952,  // UK| LIVE FOOTBALL PPV
  1865, // UK| EPL PREMIER LEAGUE PPV VIP
  755,  // UK| EPL PREMIER LEAGUE PPV
  769,  // UK| CHAMPIONSHIP PPV
  921,  // UK| UEFA PPV
  575,  // UK| DAZN PPV VIP
  976,  // UK| LA LIGA TEAM PPV
  977,  // UK| SERIE A TEAM PPV
  1614, // UK| LIGUE 1 PPV
  1972, // UK| SOCCER REPLAY RAW
  // Tennis
  2334, // FR| ROLAND GARROS 2026 RAW
  1429, // TS| TENNIS TV PPV (ATP)
  1096, // TS| TENNIS LIVE
  1927, // US| TENNIS PPV
  // UFC & fights
  1139, // US| UFC PPV
  929,  // UK| UFC PPV
  903,  // US| PPV EVENT
  380,  // UK| PPV EVENT
  // Arabic sports — beIN (pinned top) + StarzPlay / AD Sports bundle
  108,  // AR| BEIN SPORTS 8K & HD
  2157, // AR| BEIN SPORTS MAX 8K
  548,  // AR| STARZPLAY SPORT 8K & 4K (AD Sports Premium/Fight)
  1231, // AR| STARZPLAY SPORT 8K & TK (AD Sports, Asia, Golf)
  1657, // AR| STARZPLAY SPORT M & RAW (AD Premium/Asia)
  1791, // AR| STARZPLAY SPORT BE & RAW (AD Sport, Serie A)
  2163, // AR| STARZPLAY SPORT F & RAW (StarzPlay Sports 1/2/3)
  // German sports
  333,  // DE| SPORT HD/4K
  1137, // DE| BUNDESLIGA HD/4K
  1115, // DE| BUNDESLIGA REPLAY
  667,  // DE| SKY GO SPORT VIP
  1267, // DE| WOW SPORT HD
  1633, // DE| DAZN EXCLUSIVE HD/RAW
  433,  // DE| DAZN PPV
  1672, // DE| SOCCER PPV
  2018, // DE| LEAGUES FOOTBALL PPV
  2231, // DE| RTL+ SPORT PPV RAW
  // Spanish sports
  870,  // ES| M+ LALIGA VIP
  552,  // ES| DAZN LA LIGA VIP
  1286, // ES| DAZN ESPAÑA VIP
  553,  // ES| M+ LIGA DE CAMPEONES VIP
  1601, // ES| COPA DEL REY VIP
  1616, // ES| LALIGA+ PPV VIP
  1232, // ES| LALIGA REPLAY VIP
  1287, // ES| MOVISTAR DEPORTES VIP
  1290, // ES| M+ VAMOS VIP
  1291, // ES| OTROS DEPORTES VIP
  // Italian sports
  265,  // IT| SPORT HD/4K
  476,  // IT| DAZN VIP HD/4K
  681,  // IT| SERIE A/B/C
  2242, // IT| DAZN PPV
  457,  // IT| AMAZON PRIME PPV (UCL)
  // Russian channels (general entertainment, isolated in their own group)
  6,    // RU| RUSSIAN HD/4K
  // Kids (live) — one provider category per language. There's no dedicated RU kids
  // category, so Russian kids are pulled by name out of category 6 (see RU_KIDS).
  304,  // UK| KIDS HD/RAW (English)
  490,  // US| KIDS HD/RAW 60fps (English)
  105,  // AR| KIDS 4K
  42,   // ES| INFANTIL VIP
  334,  // DE| KIDS HD/4K
  // US 24/7 single-show loop channels (cartoon marathons + kids/family)
  2063, // US| 24/7 CARTOON RAW 60fps
  2064, // US| 24/7 KIDS/FAMILY RAW 60fps
]

// Viewer-facing buckets, in display order.
const B = {
  wc: '⚽ World Cup 2026',
  wcPpv: '⚽ World Cup — Match PPV',
  wcReplay: '📼 World Cup — Replays',
  uk: '🏴 Sky Sports & UK Sports',
  fbPpv: '⚽ Football — Match PPV',
  fbReplay: '📼 Football — Replays',
  ar: '🇸🇦 beIN & Arabic Sports',
  de: '🇩🇪 German Sports',
  es: '🇪🇸 Spanish Sports',
  it: '🇮🇹 Italian Sports',
  tennis: '🎾 Tennis',
  ufc: '🥊 UFC & Fight PPV',
  ufcVod: '🎬 UFC PPV Replays (VOD)',
  ru: '🇷🇺 Russian Channels',
  kidsEn: '🧒 Kids — English',
  kidsAr: '🧒 Kids — Arabic',
  kidsEs: '🧒 Kids — Spanish',
  kidsDe: '🧒 Kids — German',
  kidsRu: '🧒 Kids — Russian',
  cartoon247: '📺 24/7 Cartoons',
  kidsFamily247: '📺 24/7 Kids & Family',
} as const
const BUCKET_ORDER: string[] = Object.values(B)

const CAT_BUCKET: Record<number, string> = {
  2343: B.wc, 2361: B.wc, 2355: B.wc, 2362: B.wc, 1958: B.wc,
  2157: B.wc, // beIN SPORTS MAX = beIN's World Cup overflow feeds
  2352: B.wcPpv, 2353: B.wcPpv, 2354: B.wcPpv, 547: B.wcPpv, 1334: B.wcPpv,
  2346: B.wcReplay,
  1729: B.uk, 1728: B.uk, 1964: B.uk, 1965: B.uk,
  1830: B.fbPpv, 1441: B.fbPpv, 952: B.fbPpv, 1865: B.fbPpv, 755: B.fbPpv,
  769: B.fbPpv, 921: B.fbPpv, 575: B.fbPpv, 976: B.fbPpv, 977: B.fbPpv,
  1614: B.fbPpv, 433: B.fbPpv, 1672: B.fbPpv, 2018: B.fbPpv, 2231: B.fbPpv,
  1616: B.fbPpv, 2242: B.fbPpv, 457: B.fbPpv,
  1972: B.fbReplay, 1232: B.fbReplay, 1115: B.fbReplay,
  108: B.ar, 548: B.ar, 1231: B.ar, 1657: B.ar, 1791: B.ar, 2163: B.ar,
  333: B.de, 1137: B.de, 667: B.de, 1267: B.de, 1633: B.de,
  870: B.es, 552: B.es, 1286: B.es, 553: B.es, 1601: B.es,
  1287: B.es, 1290: B.es, 1291: B.es,
  265: B.it, 476: B.it, 681: B.it,
  2334: B.tennis, 1429: B.tennis, 1096: B.tennis, 1927: B.tennis,
  1139: B.ufc, 929: B.ufc, 903: B.ufc, 380: B.ufc,
  6: B.ru,
  304: B.kidsEn, 490: B.kidsEn, 105: B.kidsAr, 42: B.kidsEs, 334: B.kidsDe,
  2063: B.cartoon247, 2064: B.kidsFamily247,
}

// Bucket for categories not in CAT_BUCKET (i.e. auto-included new ones).
function bucketForCategory(name: string): string {
  if (/WORLD CUP|MUNDIAL/i.test(name)) {
    if (/REPLAY/i.test(name)) return B.wcReplay
    if (/PPV/i.test(name)) return B.wcPpv
    return B.wc
  }
  if (/REPLAY/i.test(name)) return B.fbReplay
  if (/UFC|PPV EVENT/i.test(name)) return B.ufc
  if (/TENNIS|ROLAND|WIMBLEDON/i.test(name)) return B.tennis
  if (/^AR\|/i.test(name)) return B.ar
  if (/^DE\|/i.test(name)) return /PPV/i.test(name) ? B.fbPpv : B.de
  if (/^ES\|/i.test(name)) return /PPV/i.test(name) ? B.fbPpv : B.es
  if (/^IT\|/i.test(name)) return /PPV/i.test(name) ? B.fbPpv : B.it
  if (/PPV|EVENT/i.test(name)) return B.fbPpv
  return B.uk
}

// Auto-include NEW provider categories beyond KEEP: relevant region prefix +
// football/tennis/UFC topic in the name (e.g. "UK| CLUB WORLD CUP PPV").
const AUTO_REGION = /^(8K|UK|ES|DE|IT|TS|FR)\|/i
const AUTO_TOPIC =
  /WORLD CUP|MUNDIAL|FUSSBALL|UEFA|CHAMPIONS|EPL|PREMIER LEAGUE|LA ?LIGA|SERIE A|BUNDESLIGA|LIGUE 1|FOOTBALL|SOCCER|CALCIO|COPA|TENNIS|ROLAND GARROS|WIMBLEDON|UFC|PPV EVENT/i

export function autoIncluded(categoryName: string): boolean {
  return AUTO_REGION.test(categoryName) && AUTO_TOPIC.test(categoryName)
}

// Language priority: EN(0) > AR(1) > DE/ES(2) > rest(3). The provider tags
// language three ways and the World Cup group mixes all of them: a leading prefix
// (UK:/US:/ES:), a parenthetical region ("4K-WC: (UK) BBC"), or a trailing code
// (beIN "... MAX 5 EN" / "... 1 AR") — so an EN/AR feed sorts above DE/ES only if
// we catch all three (else German wrongly leads on its clean "DE:" prefix alone).
export function languageRank(s: string): number {
  const t = s.replace(/^8K[:|]\s*/i, '')
  if (/^(UK|US|EN)\b/i.test(t) || /\((?:UK|US|EN|GB|IE)\)/i.test(t) || /\bEN\b/.test(t)) return 0
  if (/^AR\b|عرب|العرب/i.test(t) || /\bAR\b/.test(t)) return 1
  if (/^(DE|ES)\b/i.test(t) || /\((?:DE|AT|ES)\)/i.test(t)) return 2
  return 3
}

// Tie-break at equal quality + language: demote US feeds (Mohamed prefers UK over US).
// ONLY US is moved — UK and everything else (beIN, ES, FR…) stay at 0, so no feed is
// pushed above another non-US one (an earlier version wrongly sank beIN below UK too).
// US tags appear as a prefix ("US: FOX") or a parenthetical ("4K-WC: (US) FOX").
function regionPref(s: string): number {
  return /^US\b/i.test(s) || /\(US\)/i.test(s) ? 1 : 0
}

// Quality from name markers (the API has no real res/fps metadata).
// 0 = explicit SD/LQ -> dropped. Higher = listed first within each bucket.
// Channel-name markers win; group name is the fallback for unmarked channels.
function qualityScore(channelName: string, groupName: string): number {
  const score = (s: string): number | null => {
    if (/\bSD\b|▼|\bLQ\b/.test(s)) return 0
    let v: number | null = null
    if (/⁸ᴷ|ᵁᴴᴰ|UHD|4K|³⁸⁴⁰|3840|2160/i.test(s)) v = 5
    else if (/FHD|ᶠᴴᴰ|1080/i.test(s)) v = 4
    else if (/ᴿᴬᵂ|\bRAW\b/i.test(s)) v = 4 // source-quality feed, typically 1080p50
    else if (/ʰᵉᵛᶜ|\bHEVC\b/i.test(s)) v = 4 // HEVC feeds here run ~1080p — rank above plain HD
    else if (/ᴴᴰ|\bHD\b/i.test(s)) v = 3
    if (/(⁶⁰|⁵⁰)\s*ᶠᵖˢ|(60|50)\s*FPS/i.test(s)) v = (v ?? 3) + 1
    return v
  }
  // strip the provider's "8K:" branding prefix so it doesn't read as 8K quality
  return score(channelName.replace(/^8K[:|]\s*/i, '')) ?? score(groupName) ?? 2
}

// Resolution/fps used to order feeds WITHIN a marker tier (the marker sets the tier).
// Probed value when we have it — so the genuinely-highest-res stream floats up — else
// the marker's NOMINAL resolution, so a "4K" feed unprobed or no-signal at probe time
// is trusted at 4K, not buried under a verified lower-res feed.
//
// trustMarker (event/World-Cup feeds): these idle or show low-res filler between
// matches, so a probe BELOW the marker is that filler, not the feed's live quality —
// fall back to the nominal tier (fps unknown) instead of demoting it. A probe AT/ABOVE
// the marker is genuine live quality and is always kept.
function realRes(streamId: number, markerQ: number, trustMarker: boolean): { h: number; fps: number } {
  const nominal = markerQ >= 5 ? 2160 : markerQ === 4 ? 1080 : markerQ === 3 ? 720 : markerQ === 2 ? 540 : 360
  const m = QUALITY[String(streamId)]
  if (m && !(trustMarker && m.h < nominal)) return { h: m.h, fps: m.fps }
  return { h: nominal, fps: 0 }
}

export interface XtreamConfig {
  host: string
  user: string
  pass: string
}

export function configFromEnv(): XtreamConfig {
  const { XTREAM_HOST, XTREAM_USER, XTREAM_PASS } = process.env
  if (!XTREAM_HOST || !XTREAM_USER || !XTREAM_PASS) {
    throw new Error('Missing XTREAM_HOST/XTREAM_USER/XTREAM_PASS env vars')
  }
  return { host: XTREAM_HOST.replace(/\/$/, ''), user: XTREAM_USER, pass: XTREAM_PASS }
}

interface Category {
  category_id: string
  category_name: string
}

interface Stream {
  stream_id: number
  name: string
  stream_icon?: string
  epg_channel_id?: string | null
  category_id: string
}

interface VodStream {
  stream_id: number
  name: string
  stream_icon?: string
  category_id: string
  container_extension?: string
  added?: string
}

export interface Channel {
  streamId: number
  name: string
  logo: string
  tvgId: string
  group: string
  /** PPV/event slot — channel name carries the event, gets synthetic EPG */
  isEventSlot: boolean
  /** marker-based quality tier (qualityScore): 0=SD … 4=FHD/RAW, 5=UHD/8K; unset for VOD */
  q?: number
  /** VOD container extension (mkv/mp4) — served from /movie/ instead of /live/ */
  vodExt?: string
}

const EVENT_GROUP = /PPV|EVENT|REPLAY/i
// Decorative separator entries like "##### beIN SPORTS #####"
const SEPARATOR = /^[#*=─—-]{3,}.*[#*=─—-]{3,}$/
// Finished/idle event slots — they reappear when renamed for the next event
const DEAD_SLOT = /^[\s#]*\b(END(?:ED)?|FINISHED|OFF\s*AIR|NO\s+EVENTS?|TBA)\b\s*[|:.\-]?/i
// Backup source groups sort below primaries within the same bucket
const BACKUP_GROUP = /ᴮᴱ|ᴮᴷ|⁽ᴮᴷ⁾|\(BK\)|BACKUP/i
// beIN pins to the top of the Arabic bucket — the only AR channel that matters
const BEIN = /bein/i
// Channels dropped even when they ride in via a kept category — feeds that look
// fine by name/marker but are sub-FHD in reality. AU SBS is 1280x720 25fps on
// every feed (probed) despite a "RAW" tag on its World Cup stream, and BBC/ITV
// RAW (true 1080p50) already cover English WC, so SBS adds nothing at quality.
const DROP_CHANNEL = /^AU:\s*SBS\b/i
// Russian kids channels (Nick/Cartoon Network/Disney) sit in the general RU
// category — no dedicated RU kids category exists — so route them out by name.
const RU_KIDS = /nick|cartoon\s*network|disney\s*(?:channel|jr|junior|xd)|карусел|мульт|малыш/i

// Dedupe key: same cleaned name (+ quality tier, when given) counts as a dup.
// Leading region/feed-family labels (TK/BE/M/F = StarzPlay feed variants) and
// source tags (STC/STZ uplinks) are stripped so the same channel collapses
// across the provider's parallel feeds. Omit `tier` to collapse a channel
// across all its qualities (used where one channel ships at many tiers).
function dedupeKey(name: string, tier?: number): string {
  const cleaned = name
    .replace(/^(8K|UK|US|DE|ES|IT|AR|FR|TK|BE|M|F)[:|]\s*/i, '')
    .toLowerCase()
    .replace(/\b(4k|uhd|fhd|hd|sd|raw|hevc|vip|stc|stz)\b/g, '')
    .replace(/[^a-z0-9؀-ۿ]+/g, ' ')
    .trim()
  return tier === undefined ? cleaned : `${cleaned}|${tier}`
}

// Sort key that keeps a channel's parallel feeds together and in sequence (FOX 1/2,
// 4K 1/2/3) when their quality ties, instead of letting them scatter. Strips the
// region prefix, non-ascii markers and quality words; compared with localeCompare
// {numeric} so "2" sorts before "10".
const nameKey = (s: string): string =>
  s.replace(/^(8K|UK|US|DE|ES|IT|AR|FR|TK|BE|M|F)[:|]\s*/i, '')
    .replace(/[^\x00-\x7F]/g, ' ')
    .replace(/\b(8k|4k|uhd|fhd|hd|sd|raw|hevc|vip)\b/gi, '')
    .replace(/\s+/g, ' ')
    .trim()
    .toLowerCase()

async function apiGet<T>(cfg: XtreamConfig, action: string): Promise<T> {
  const url = `${cfg.host}/player_api.php?username=${cfg.user}&password=${cfg.pass}&action=${action}`
  const resp = await fetch(url, { headers: { 'User-Agent': 'VLC/3.0.18' } })
  if (!resp.ok) throw new Error(`${action} -> HTTP ${resp.status}`)
  return resp.json() as Promise<T>
}

// UFC PPV / Fight-Night replays live in the VOD catalog — the live PPV slots go
// black once the event window passes. The provider files them under many
// language prefixes ("EN -", "ES -", "SOC -") and sometimes mis-categorizes them
// (e.g. Freedom 250 sits under "NORDIC SPORT"), so match by NAME across the whole
// VOD list, collapse language/date variants to one entry per event (English
// first, newest upload), and serve them on-demand. The number identifies the card.
const UFC_VOD_EVENT = /\bUFC\s+(FREEDOM\s+|FIGHT\s*NIGHT\s+|ON\s+\w+\s+)?(\d{1,3})\b/i

function ufcReplays(vod: VodStream[]): Channel[] {
  type V = { c: Channel; key: string; lang: number; added: number }
  const items: V[] = []
  for (const s of vod) {
    const name = String(s.name ?? '').trim()
    const m = name.match(UFC_VOD_EVENT)
    if (!m) continue
    items.push({
      key: (m[1] ?? '').toLowerCase().replace(/\s+/g, '') + m[2],
      lang: languageRank(name),
      added: Number(s.added ?? 0),
      c: {
        streamId: s.stream_id,
        name,
        logo: String(s.stream_icon ?? ''),
        tvgId: '',
        group: B.ufcVod,
        isEventSlot: false,
        vodExt: s.container_extension || 'mp4',
      },
    })
  }
  items.sort((a, b) => a.lang - b.lang || b.added - a.added)
  const seen = new Set<string>()
  const out: Channel[] = []
  for (const it of items) {
    if (seen.has(it.key)) continue
    seen.add(it.key)
    out.push(it.c)
  }
  return out
}

// --- 4K/UHD movies (VOD) ---------------------------------------------------
// The provider marks movie quality on the CATEGORY name, not per title, using
// superscript tags (⁴ᴷ ³⁸⁴⁰ᴾ), plain 4K/3840/UHD, or Arabic فائقة الوضوح
// ("ultra-HD"). We pull every such category — all languages — as on-demand VOD
// so the playlist gains a clean, browsable 4K film library beside the sports.
const MOVIE_4K = /⁴ᴷ|³⁸⁴⁰|\b4K\b|3840|\bUHD\b|2160|فائقة الوضوح/i

// Decorative Unicode the provider uses for those tags (superscript digits +
// modifier letters: ⁴ᴷ ³⁸⁴⁰ᴾ ᴰᴼᴸᴮʸ ᴬᵁᴰᴵᴼ ⱽᴵˢᴵᴼᴺ ᴴᴰᴿ) — stripped for clean names.
const SUPER = /[²³¹⁰-₟ᴬ-ᵪʰ-˿ᶜ-ᶿⱽ]+/g

// Category name -> clean group label, e.g.
// "NETFLIX MOVIES ⁴ᴷ ³⁸⁴⁰ᴾ ᴰᵒˡᵇʸ ⱽᶦˢᶦᵒⁿ" -> "🎬 NETFLIX MOVIES 4K".
function movieGroup(cat: string): string {
  const base = cat
    .replace(SUPER, ' ')
    .replace(/\b(3840p?|dolby|audio|visi[oó]n|hdr|multi(?:-subs)?)\b/gi, ' ')
    .replace(/فائقة الوضوح/g, ' ')
    .replace(/(?<=^|\s)[A-ZÀ-Þ](?=\s|$)/g, ' ') // stray leftover capital (e.g. Ó from VISIÓN)
    .replace(/\(\s*\)/g, ' ')
    .replace(/\b4k\b/gi, ' ')
    .replace(/[-–]\s*$/, ' ')
    .replace(/\s+/g, ' ')
    .trim()
  return `🎬 ${base} 4K`
}

// Strip the provider's "4K-EN - " / "4K-D+ - " title prefix for clean, sortable names.
const MOVIE_PREFIX = /^4K-[A-Z0-9+]{1,5}\s*-\s*/i

// Every movie in a 4K-marked category, as on-demand VOD (served from /movie/).
// No tvg-id, so buildEventEpg gives them no (bogus) EPG row.
function movie4kChannels(vod: VodStream[], catName: Map<number, string>): Channel[] {
  const out: Channel[] = []
  for (const s of vod) {
    const cat = catName.get(Number(s.category_id))
    if (!cat || !MOVIE_4K.test(cat)) continue
    const name = String(s.name ?? '').replace(MOVIE_PREFIX, '').trim()
    if (!name || SEPARATOR.test(name)) continue
    out.push({
      streamId: s.stream_id,
      name,
      logo: String(s.stream_icon ?? ''),
      tvgId: '',
      group: movieGroup(cat),
      isEventSlot: false,
      vodExt: s.container_extension || 'mp4',
    })
  }
  return out
}

/** Fetch + filter provider channels into ordered, quality-sorted buckets. */
export async function fetchCuratedChannels(cfg: XtreamConfig): Promise<Channel[]> {
  const [cats, streams, vod, vodCats] = await Promise.all([
    apiGet<Category[]>(cfg, 'get_live_categories'),
    apiGet<Stream[]>(cfg, 'get_live_streams'),
    apiGet<VodStream[]>(cfg, 'get_vod_streams'),
    apiGet<Category[]>(cfg, 'get_vod_categories'),
  ])
  const catName = new Map(cats.map((c) => [Number(c.category_id), c.category_name]))
  const vodCatName = new Map(vodCats.map((c) => [Number(c.category_id), c.category_name]))
  const byCat = new Map<number, Stream[]>()
  for (const s of streams) {
    const id = Number(s.category_id)
    const list = byCat.get(id)
    if (list) list.push(s)
    else byCat.set(id, [s])
  }

  const keepSet = new Set(KEEP)
  const autoIds = [...catName.entries()]
    .filter(([id, name]) => !keepSet.has(id) && autoIncluded(name))
    .map(([id]) => id)

  type Item = { c: Channel; q: number; rh: number; rf: number; lang: number; region: number; backup: number; bein: number; nkey: string; idx: number }
  const byBucket = new Map<string, Item[]>()

  const ids = [...KEEP, ...autoIds]
  for (const [idx, id] of ids.entries()) {
    const group = catName.get(id)
    if (!group) continue
    const bucket = CAT_BUCKET[id] ?? bucketForCategory(group)
    const isEventSlot = EVENT_GROUP.test(group)
    // Event/World-Cup feeds idle between matches; trust their marker over a low idle
    // probe so they aren't buried while off-air (see realRes). The WC bucket holds
    // such feeds whose category name doesn't say PPV/EVENT (e.g. "UHD 3840P").
    const trustMarker = isEventSlot || bucket === B.wc
    const backup = BACKUP_GROUP.test(group) ? 1 : 0
    for (const s of byCat.get(id) ?? []) {
      const name = String(s.name ?? '').trim()
      if (!name || SEPARATOR.test(name) || DROP_CHANNEL.test(name)) continue
      if (isEventSlot && DEAD_SLOT.test(name)) continue
      const q = qualityScore(name, group)
      if (q === 0) continue // explicit SD/LQ feeds
      // Russian kids channels ride in the general RU category — route them to the kids group.
      const chBucket = id === 6 && RU_KIDS.test(name) ? B.kidsRu : bucket
      const { h: rh, fps: rf } = realRes(s.stream_id, q, trustMarker)
      const item: Item = {
        q,
        rh,
        rf,
        lang: languageRank(name),
        region: regionPref(name),
        backup,
        bein: BEIN.test(name) ? 0 : 1,
        nkey: nameKey(name),
        idx,
        c: {
          streamId: s.stream_id,
          name,
          logo: String(s.stream_icon ?? ''),
          // Event slots get a synthetic id served by /api/epg; others use provider EPG ids
          tvgId: isEventSlot ? `ppv.${s.stream_id}` : String(s.epg_channel_id ?? ''),
          group: chBucket,
          isEventSlot,
          q,
        },
      }
      const list = byBucket.get(chBucket)
      if (list) list.push(item)
      else byBucket.set(chBucket, [item])
    }
  }

  const channels: Channel[] = []
  for (const bucket of BUCKET_ORDER) {
    const items = byBucket.get(bucket)
    if (!items) continue
    // beIN first in the Arabic bucket, then by MARKER tier (trust the provider's
    // UHD/RAW/HEVC/HD label so a "4K" feed caught idle or unprobed still ranks as 4K),
    // then REAL probed resolution/fps to order within the tier, language on ties,
    // US demoted below UK on a further tie, primaries before backups, then sibling
    // feeds grouped + numbered (FOX 1/2, 4K 1/2/3) so equal-quality feeds don't scatter
    const isArabic = bucket === B.ar
    items.sort(
      (a, b) =>
        (isArabic ? a.bein - b.bein : 0) ||
        b.q - a.q || b.rh - a.rh || b.rf - a.rf ||
        a.lang - b.lang || a.region - b.region || a.backup - b.backup ||
        a.nkey.localeCompare(b.nkey, 'en', { numeric: true }) || a.idx - b.idx,
    )
    const seen = new Map<string, number>()
    for (const it of items) {
      // Arabic bundles the same AD/StarzPlay channel at many quality tiers, so
      // collapse it tier-agnostically there; elsewhere keep tiers distinct.
      const key = isArabic ? dedupeKey(it.c.name) : dedupeKey(it.c.name, it.q)
      const n = seen.get(key) ?? 0
      if (n >= 2) continue // keep best feed + one spare
      seen.set(key, n + 1)
      channels.push(it.c)
    }
  }

  // Mint stable synthetic tvg-ids for channels the provider left untagged, so they
  // can still carry EPG in TiviMate (which maps EPG to channels by id only). Prefer
  // an HD-twin's id (same region + name) so UHD/dup feeds share its guide; else a
  // per-stream id. Identical-name myepg matches then attach to these; the country
  // guard can't misfire on them (synthetic ids have no country suffix).
  const sibKey = (c: Channel) => regionPrefix(c.name) + '|' + epgExactNorm(c.name)
  const sibId = new Map<string, string>()
  for (const c of channels) {
    if (!isRegular(c)) continue
    const k = sibKey(c)
    if (!sibId.has(k)) sibId.set(k, c.tvgId)
  }
  for (const c of channels) {
    if (c.isEventSlot || c.tvgId) continue
    c.tvgId = sibId.get(sibKey(c)) ?? `sx.${c.streamId}`
  }

  // On-demand UFC PPV replays + the 4K movie library (appended after the live,
  // EPG-tagged channels; both are VOD with no tvg-id, so they get no EPG row).
  channels.push(...ufcReplays(vod))
  channels.push(...movie4kChannels(vod, vodCatName))
  return channels
}

const q = (v: string) => v.replace(/"/g, "'")

export function buildM3U(cfg: XtreamConfig, channels: Channel[], epgUrls: string[]): string {
  const lines = [`#EXTM3U url-tvg="${epgUrls.join(',')}"`]
  for (const c of channels) {
    const src = c.vodExt
      ? `${cfg.host}/movie/${cfg.user}/${cfg.pass}/${c.streamId}.${c.vodExt}`
      : `${cfg.host}/live/${cfg.user}/${cfg.pass}/${c.streamId}.ts`
    lines.push(
      `#EXTINF:-1 tvg-id="${q(c.tvgId)}" tvg-name="${q(c.name)}" tvg-logo="${q(c.logo)}" group-title="${q(c.group)}",${c.name}`,
      src,
    )
  }
  return lines.join('\n') + '\n'
}

const xml = (v: string) =>
  v.replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;').replace(/"/g, '&quot;')

const xmltvTime = (d: Date) =>
  d.toISOString().replace(/[-:]/g, '').replace(/\.\d+Z$/, '') + ' +0000'

// Name normalization for matching our channels against myepg display-names.
// Drops the provider's "REGION:" prefix, glues "ITV 1" -> "itv1"; fuzzy also
// strips plural 's' + reorders tokens so "UK: SKY SPORT F1" == "Sky Sports F1".
const EPG_QUAL = new Set(['8k', '4k', 'hd', 'sd', 'fhd', 'uhd', 'raw', 'vip', 'hevc', 'fps', 'mobile', 'mobil'])
const epgExactNorm = (s: string) =>
  s.replace(/^[a-z0-9+\-]{1,6}[:|]\s*/i, '').toLowerCase().replace(/⚽/g, 'o')
    .replace(/[^a-z0-9 ]/g, ' ').replace(/\s+/g, ' ').trim().replace(/ (\d)/g, '$1')
// Fuzzy form from an already-computed exact norm — callers that need both pass
// `ex` in so epgExactNorm runs once, not twice.
const epgFuzzyTokens = (ex: string) =>
  ex.split(' ').filter((t) => t && !EPG_QUAL.has(t)).map((t) => t.replace(/s$/, '')).sort().join('')
// Full-name normalizer (keeps region/quality words): an identical full-name match
// is the same channel even if the country suffix differs (e.g. beIN FR uses .qa).
const epgRawNorm = (s: string) =>
  s.toLowerCase().replace(/⚽/g, 'o').replace(/[^a-z0-9 ]/g, ' ').replace(/\s+/g, ' ').trim()
// Country suffix of an xmltv id, e.g. "dazn1.es" -> "es".
const epgCountry = (id: string) => id.match(/\.([a-z]{2,3})$/i)?.[1].toLowerCase() ?? ''
// Provider's leading "REGION:" tag, e.g. "ES: DAZN 1" -> "es".
const regionPrefix = (s: string) => s.match(/^([a-z0-9+\-]{1,6})[:|]/i)?.[1].toLowerCase() ?? ''
// A regular (non-event) channel that can carry real EPG — i.e. has a tvg-id.
const isRegular = (c: Channel) => !c.isEventSlot && !!c.tvgId
// Channel id of a <programme> block (what TiviMate maps EPG by).
const programmeChannel = (block: string) => block.match(/\bchannel="([^"]*)"/)?.[1] ?? ''

/** Real myepg programmes, re-tagged to OUR channel tvg-ids (TiviMate maps by id). */
export interface MyepgGuide {
  /** <programme> blocks with the channel attr rewritten to our tvg-id */
  programmes: string[]
  /** our tvg-ids that myepg covered, so provider EPG only fills the rest */
  covered: Set<string>
}

/** Our channels keyed by every way a myepg <channel> might name them. */
interface MyepgLookups {
  ids: Set<string> // tvg-id (exact id match)
  byRaw: Map<string, string> // identical full name -> tvg-id
  byExact: Map<string, string> // prefix-stripped name -> tvg-id
  byFuzzy: Map<string, string> // token-sorted name -> tvg-id
}

// Resolve one myepg <channel> block to one of OUR tvg-ids, in priority order:
// exact id, identical full name, then same-country looser name. First match wins.
function resolveMyepgChannel(
  block: string,
  remap: Map<string, string>,
  covered: Set<string>,
  lookups: MyepgLookups,
): void {
  const idm = block.match(/\bid="([^"]*)"/)
  if (!idm) return
  const mid = idm[1]
  if (remap.has(mid)) return
  let our = lookups.ids.has(mid) ? mid : undefined // exact id — always the same channel
  if (!our) {
    for (const dm of block.matchAll(/<display-name[^>]*>([^<]*)<\/display-name>/g)) {
      const n = dm[1]
      if (/\d{4}-\d{2}-\d{2}|\bvs\b| \| /i.test(n)) continue // skip event-style names
      // identical FULL name = same channel (country suffix may legitimately differ)
      our = lookups.byRaw.get(epgRawNorm(n))
      if (our) break
      // looser name match ONLY within the same country, else "DAZN 1" (ES) wrongly
      // grabs "DAZN 1" (UK), "Sky Sport" (DE) grabs (NZ)… — wrong EPG is worse than none.
      const ex = epgExactNorm(n)
      const cand = lookups.byExact.get(ex) ?? lookups.byFuzzy.get(epgFuzzyTokens(ex))
      if (cand && epgCountry(mid) && epgCountry(mid) === epgCountry(cand)) { our = cand; break }
    }
  }
  if (our && !covered.has(our)) {
    remap.set(mid, our)
    covered.add(our)
  }
}

/**
 * Stream each myepg guide, match its channels to OURS (by id, then exact, then
 * fuzzy display-name), and collect that channel's <programme> blocks re-tagged
 * with our tvg-id — so TiviMate, which maps EPG to channels by id only, shows
 * real schedules. XMLTV declares every <channel> before any <programme>, so a
 * single streaming pass builds the id remap, then re-tags programmes. Scoped to
 * our channels, so memory stays modest despite the ~170MB/guide payload.
 * Best-effort: a failed source just yields fewer programmes.
 */
export async function fetchMyepgGuide(urls: string[], channels: Channel[]): Promise<MyepgGuide> {
  const programmes: string[] = []
  const covered = new Set<string>()
  // OUR channels, keyed by every way a myepg <channel> might name them.
  const lookups: MyepgLookups = { ids: new Set(), byRaw: new Map(), byExact: new Map(), byFuzzy: new Map() }
  const put = (m: Map<string, string>, key: string, id: string) => { if (key && !m.has(key)) m.set(key, id) }
  for (const c of channels) {
    if (!isRegular(c)) continue
    const ex = epgExactNorm(c.name)
    lookups.ids.add(c.tvgId)
    put(lookups.byRaw, epgRawNorm(c.name), c.tvgId)
    put(lookups.byExact, ex, c.tvgId)
    put(lookups.byFuzzy, epgFuzzyTokens(ex), c.tvgId)
  }
  if (!lookups.ids.size) return { programmes, covered }

  for (const url of urls) {
    try {
      const resp = await fetch(url, { headers: { 'User-Agent': 'VLC/3.0.18' } })
      if (!resp.ok || !resp.body) continue
      const reader = resp.body.pipeThrough(new DecompressionStream('gzip')).getReader()
      const decoder = new TextDecoder()
      const remap = new Map<string, string>() // myepg channel id -> our tvg-id
      let buf = ''
      let inProgrammes = false
      while (true) {
        const { value, done } = await reader.read()
        if (done) break
        buf += decoder.decode(value, { stream: true })
        // Walk complete elements with a cursor, slicing the buffer ONCE per chunk —
        // slicing per element re-copies the tail (O(n²) over a ~170MB guide).
        if (!inProgrammes) {
          let pos = 0
          let i: number
          while ((i = buf.indexOf('</channel>', pos)) >= 0) {
            const s = buf.lastIndexOf('<channel', i)
            if (s >= 0) resolveMyepgChannel(buf.slice(s, i), remap, covered, lookups)
            pos = i + 10
          }
          buf = buf.slice(pos)
          const p = buf.indexOf('<programme')
          if (p >= 0) { inProgrammes = true; buf = buf.slice(p) }
        }
        if (inProgrammes) {
          let pos = 0
          let i: number
          while ((i = buf.indexOf('</programme>', pos)) >= 0) {
            const s = buf.lastIndexOf('<programme', i)
            if (s >= 0) {
              const block = buf.slice(s, i + 12)
              const cs = block.indexOf('channel="')
              if (cs >= 0) {
                const ce = block.indexOf('"', cs + 9)
                const our = remap.get(block.slice(cs + 9, ce))
                if (our) programmes.push(block.slice(0, cs) + `channel="${xml(our)}"` + block.slice(ce + 1))
              }
            }
            pos = i + 12
          }
          buf = buf.slice(pos)
        }
      }
      try { await reader.cancel() } catch { /* already drained */ }
    } catch {
      /* skip this source */
    }
  }
  return { programmes, covered }
}

/**
 * XMLTV for the curated playlist, self-contained for TiviMate (which maps EPG to
 * channels by tvg-id only). EVERY curated channel gets a guide entry:
 *  - event/PPV slots: a synthetic programme (channel name IS the title — the IPTV
 *    Editor "use channel name as EPG" trick), 1h blocks from -1h to +24h.
 *  - regular channels myepg covers: its real programmes (re-tagged to our ids).
 *  - else the provider's xmltv (exact-id match), scoped to our channels.
 *  - anything still uncovered falls back to the same name-as-title, so the guide
 *    never has a blank row.
 */
export function buildEventEpg(
  channels: Channel[],
  providerXml = '',
  myepg?: MyepgGuide,
): string {
  const slots = channels.filter((c) => c.isEventSlot)
  const covered = myepg?.covered ?? new Set<string>()
  // unique regular channels, one per tvg-id (HD/UHD feeds may share an id)
  const regular: Channel[] = []
  const seenReg = new Set<string>()
  for (const c of channels) {
    if (c.isEventSlot || !c.tvgId || seenReg.has(c.tvgId)) continue
    seenReg.add(c.tvgId)
    regular.push(c)
  }
  // provider fills ONLY channels myepg lacks
  const wantIds = new Set(regular.filter((c) => !covered.has(c.tvgId)).map((c) => c.tvgId))

  const out: string[] = ['<?xml version="1.0" encoding="UTF-8"?>', '<tv generator-info-name="iptv-preview">']
  // hourly start/stop xmltv stamps for the -1h..+24h window, computed once and
  // shared by every name-as-title channel (was recomputed per channel).
  const base = new Date()
  base.setUTCMinutes(0, 0, 0)
  base.setUTCHours(base.getUTCHours() - 1)
  const hourStamps = Array.from({ length: 25 }, (_, h) => {
    const a = new Date(base.getTime() + h * 3600_000)
    return ` start="${xmltvTime(a)}" stop="${xmltvTime(new Date(a.getTime() + 3600_000))}"`
  })
  const nameAsTitle = (c: Channel) => {
    const tail = ` channel="${xml(c.tvgId)}"><title>${xml(c.name)}</title><desc>${xml(c.group)}</desc></programme>`
    for (const stamp of hourStamps) out.push(`<programme${stamp}${tail}`)
  }

  // --- channels: declare every curated channel (PPV slots + all regular) ---
  for (const c of [...slots, ...regular]) {
    out.push(`<channel id="${xml(c.tvgId)}"><display-name>${xml(c.name)}</display-name></channel>`)
  }

  // --- programmes ---
  for (const c of slots) nameAsTitle(c)
  const gotProg = new Set<string>() // channels that actually received real programmes
  // myepg real programmes (channel attr already rewritten to our tvg-ids)
  if (myepg) {
    for (const p of myepg.programmes) {
      out.push(p)
      gotProg.add(programmeChannel(p))
    }
  }
  // provider programmes for channels myepg lacks
  const seenProg = new Set<string>()
  if (providerXml && wantIds.size) {
    for (const m of providerXml.matchAll(/<programme\b[^>]*\bchannel="([^"]*)"[^>]*>[\s\S]*?<\/programme>/g)) {
      if (!wantIds.has(m[1])) continue
      const startM = m[0].match(/\bstart="([^"]*)"/)
      const k = m[1] + '|' + (startM ? startM[1] : out.length)
      if (seenProg.has(k)) continue // skip duplicate programme declarations
      seenProg.add(k)
      gotProg.add(m[1])
      out.push(m[0])
    }
  }
  // fallback: any regular channel that received no real programmes gets
  // name-as-title, so no row is ever blank in TiviMate
  for (const c of regular) {
    if (!gotProg.has(c.tvgId)) nameAsTitle(c)
  }

  out.push('</tv>')
  return out.join('\n')
}

export function providerEpgUrl(cfg: XtreamConfig): string {
  return `${cfg.host}/xmltv.php?username=${cfg.user}&password=${cfg.pass}`
}

/** Fetch the provider's full XMLTV guide; '' on any failure (best-effort). */
export async function fetchProviderEpg(cfg: XtreamConfig): Promise<string> {
  try {
    const resp = await fetch(providerEpgUrl(cfg), { headers: { 'User-Agent': 'VLC/3.0.18' } })
    if (!resp.ok) return ''
    return await resp.text()
  } catch {
    return ''
  }
}

/** Shared token gate: true if the request's ?t= matches PLAYLIST_TOKEN. */
export function tokenOk(url: string | undefined): boolean {
  const token = process.env.PLAYLIST_TOKEN
  if (!token) return false
  const t = new URL(url ?? '/', 'http://x').searchParams.get('t')
  return t === token
}
