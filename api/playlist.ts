// GET /api/playlist?t=TOKEN — trimmed M3U, rebuilt from the provider on each
// cache miss so channel names (esp. PPV event slots) stay current.
import {
  buildM3U,
  configFromEnv,
  fetchCuratedChannels,
  providerEpgUrl,
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
    const channels = await fetchCuratedChannels(cfg)
    const proto = req.headers['x-forwarded-proto'] ?? 'https'
    const host = req.headers['x-forwarded-host'] ?? req.headers.host
    const ownEpg = `${proto}://${host}/api/epg?t=${process.env.PLAYLIST_TOKEN}`
    // EPG_URLS: comma-separated preferred EPG sources (e.g. myepg.top links);
    // falls back to the provider's xmltv. Own PPV epg is always appended.
    const preferred = (process.env.EPG_URLS ?? providerEpgUrl(cfg)).split(',').filter(Boolean)
    const body = buildM3U(cfg, channels, [...preferred, ownEpg])
    res.setHeader('Content-Type', 'audio/x-mpegurl; charset=utf-8')
    res.setHeader('Content-Disposition', 'inline; filename="playlist.m3u"')
    res.setHeader('Cache-Control', 'public, s-maxage=1800, stale-while-revalidate=3600')
    res.setHeader('Access-Control-Allow-Origin', '*')
    res.end(body)
  } catch (e) {
    res.statusCode = 502
    res.end(e instanceof Error ? e.message : 'upstream error')
  }
}
