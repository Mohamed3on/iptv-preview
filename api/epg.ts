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
} from './_lib.js'

export default async function handler(req: Request): Promise<Response> {
  if (!tokenOk(req.url)) return new Response('Forbidden', { status: 403 })
  try {
    const cfg = configFromEnv()
    const epgUrls = (process.env.EPG_URLS ?? '').split(',').map((s) => s.trim()).filter(Boolean)
    // provider EPG doesn't need the channel list — kick it off first so its ~70MB
    // download overlaps curation; myepg needs the channels, so it waits for them
    const providerEpgPromise = fetchProviderEpg(cfg)
    const channels = await fetchCuratedChannels(cfg, false) // VOD entries carry no guide
    const [providerXml, myepg] = await Promise.all([
      providerEpgPromise,
      fetchMyepgGuide(epgUrls, channels),
    ])
    // EPG changes slowly (programmes are known days ahead) but each cache miss
    // pulls ~47MB (2 myepg guides + provider), and a burst of guide downloads gets
    // the myepg account blocked — so cache 6h; stale-while-revalidate serves
    // instantly while refreshing.
    return new Response(buildEventEpg(channels, providerXml, myepg), {
      headers: {
        'Content-Type': 'application/xml; charset=utf-8',
        'Cache-Control': 'public, s-maxage=21600, stale-while-revalidate=86400',
        'Access-Control-Allow-Origin': '*',
      },
    })
  } catch (e) {
    return new Response(e instanceof Error ? e.message : 'upstream error', { status: 502 })
  }
}
