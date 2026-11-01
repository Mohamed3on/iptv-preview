// GET /api/playlist?t=TOKEN — trimmed M3U, rebuilt from the provider on each
// cache miss so channel names (esp. PPV event slots) stay current.
// Add &vpn=1 for a copy whose streams use the provider's VPN-only front, for watching with
// a VPN on (the normal fronts block VPN IPs, and the VPN front blocks everyone else).
import {
  buildM3U,
  configFromEnv,
  fetchCuratedChannels,
  providerEpgUrl,
  tokenOk,
  withCompetitionGroups,
} from './_lib.js'

const VPN_HOST = 'http://apps.g1ming-hub.sbs'

export default async function handler(req: Request): Promise<Response> {
  if (!tokenOk(req.url)) return new Response('Forbidden', { status: 403 })
  try {
    const cfg = configFromEnv()
    const channels = withCompetitionGroups(await fetchCuratedChannels(cfg))
    const proto = req.headers.get('x-forwarded-proto') ?? 'https'
    // Host, not the client-supplied X-Forwarded-Host: the cached playlist is shared by URL.
    const host = req.headers.get('host')
    const ownEpg = `${proto}://${host}/api/epg?t=${process.env.PLAYLIST_TOKEN}`
    // EPG_URLS: comma-separated preferred EPG sources (e.g. myepg.top links);
    // falls back to the provider's xmltv. Own PPV epg is always appended.
    const preferred = (process.env.EPG_URLS ?? providerEpgUrl(cfg)).split(',').filter(Boolean)
    const vpn = new URL(req.url).searchParams.get('vpn') === '1'
    return new Response(buildM3U(vpn ? { ...cfg, host: VPN_HOST } : cfg, channels, [...preferred, ownEpg]), {
      headers: {
        'Content-Type': 'audio/x-mpegurl; charset=utf-8',
        'Content-Disposition': 'inline; filename="playlist.m3u"',
        'Cache-Control': 'public, s-maxage=1800, stale-while-revalidate=3600',
        'Access-Control-Allow-Origin': '*',
      },
    })
  } catch (e) {
    return new Response(e instanceof Error ? e.message : 'upstream error', { status: 502 })
  }
}
