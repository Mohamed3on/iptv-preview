// GET /api/epg?t=TOKEN — XMLTV for the curated playlist: synthetic PPV/event
// guide (channel name as title) PLUS the provider's real programmes folded in
// for our regular channels (scoped by exact id, so the payload stays small).
import {
  buildEventEpg,
  configFromEnv,
  fetchCuratedChannels,
  fetchMyepgGuide,
  fetchProviderEpg,
  tokenOk,
  type MyepgGuide,
} from './_lib.js'

// The guide downloads (~47MB of myepg + the ~70MB provider guide) change slowly, and a
// burst of them gets the myepg account blocked, so they are reused for 6h. The response
// itself is NOT: a PPV slot's guide entry is its channel name, which changes from match to
// match, so a guide built hours ago shows the previous match (and its 24h window runs out).
const GUIDE_TTL = 6 * 3600_000
let guides: { at: number; job: Promise<[string, MyepgGuide]> } | undefined

export default async function handler(req: Request): Promise<Response> {
  if (!tokenOk(req.url)) return new Response('Forbidden', { status: 403 })
  try {
    const cfg = configFromEnv()
    const epgUrls = (process.env.EPG_URLS ?? '').split(',').map((s) => s.trim()).filter(Boolean)
    const channelsPromise = fetchCuratedChannels(cfg, false) // VOD entries carry no guide
    if (!guides || Date.now() - guides.at > GUIDE_TTL) {
      // provider EPG doesn't need the channel list, so its download overlaps curation;
      // myepg needs the channels, so it waits for them
      const providerEpgPromise = fetchProviderEpg(cfg)
      const job = channelsPromise.then((channels) =>
        Promise.all([providerEpgPromise, fetchMyepgGuide(epgUrls, channels)]))
      guides = { at: Date.now(), job }
      job.catch(() => { if (guides?.job === job) guides = undefined })
    }
    const [channels, [providerXml, myepg]] = await Promise.all([channelsPromise, guides.job])
    // Rebuilt every 15 min with no stale serving: a stale copy is exactly the old-names guide.
    return new Response(buildEventEpg(channels, providerXml, myepg), {
      headers: {
        'Content-Type': 'application/xml; charset=utf-8',
        'Cache-Control': 'public, s-maxage=900',
        'Access-Control-Allow-Origin': '*',
      },
    })
  } catch (e) {
    return new Response(e instanceof Error ? e.message : 'upstream error', { status: 502 })
  }
}
