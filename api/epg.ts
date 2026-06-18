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

export default async function handler(req: any, res: any) {
  if (!tokenOk(req.url)) {
    res.statusCode = 403
    res.end('Forbidden')
    return
  }
  try {
    const cfg = configFromEnv()
    const epgUrls = (process.env.EPG_URLS ?? '').split(',').map((s) => s.trim()).filter(Boolean)
    // provider EPG doesn't need the channel list — kick it off first so its ~70MB
    // download overlaps curation; myepg needs the channels, so it waits for them
    const providerEpgPromise = fetchProviderEpg(cfg)
    const channels = await fetchCuratedChannels(cfg)
    const [providerXml, myepg] = await Promise.all([
      providerEpgPromise,
      fetchMyepgGuide(epgUrls, channels),
    ])
    res.setHeader('Content-Type', 'application/xml; charset=utf-8')
    // EPG changes slowly (programmes are known days ahead) but each cache miss
    // pulls ~47MB (2 myepg guides + provider) — so cache 6h to bound Vercel
    // bandwidth; stale-while-revalidate serves instantly while refreshing.
    res.setHeader('Cache-Control', 'public, s-maxage=21600, stale-while-revalidate=86400')
    res.setHeader('Access-Control-Allow-Origin', '*')
    res.end(buildEventEpg(channels, providerXml, myepg))
  } catch (e) {
    res.statusCode = 502
    res.end(e instanceof Error ? e.message : 'upstream error')
  }
}
