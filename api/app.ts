// The Fire TV app's endpoints, gated by APP_TOKENS (never PLAYLIST_TOKEN) and private:
// no CORS header, and only the server's own cache may keep a response.
// GET /api/app/channels?t=TOKEN — groups, channels, feed families, badges and both provider fronts.
import { buildAppFeed } from './_app-feed.js'
import { appTokenOk, configFromEnv, fetchCuratedChannels, withCompetitionGroups } from './_lib.js'

const forbidden = () => new Response('Forbidden', { status: 403 })

export async function channels(req: Request): Promise<Response> {
  if (!appTokenOk(req.url)) return forbidden()
  try {
    const cfg = configFromEnv()
    const feed = buildAppFeed(cfg, withCompetitionGroups(await fetchCuratedChannels(cfg, false)))
    return Response.json(feed, {
      headers: { 'Cache-Control': 'private, s-maxage=1800, stale-while-revalidate=3600' },
    })
  } catch (e) {
    return new Response(e instanceof Error ? e.message : 'upstream error', { status: 502 })
  }
}
