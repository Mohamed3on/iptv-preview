// The Fire TV app's endpoints, gated by APP_TOKENS (never PLAYLIST_TOKEN) and private:
// no CORS header, and only the server's own cache may keep a response.
// GET /api/app/channels?t=TOKEN — groups, channels, feed families, badges and both provider fronts.
// GET /api/app/guide?t=TOKEN — the guide as a gzip SQLite file the stick opens without parsing.
import { buildAppFeed, buildAppGuide } from './_app-feed.js'
import { appTokenOk, configFromEnv, fetchCuratedChannels, withCompetitionGroups } from './_lib.js'
import { curatedGuide } from './epg.js'

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

export async function guide(req: Request): Promise<Response> {
  if (!appTokenOk(req.url)) return forbidden()
  try {
    const { channels, xml } = await curatedGuide()
    return new Response(buildAppGuide(channels, xml), {
      headers: { 'Content-Type': 'application/gzip', 'Cache-Control': 'private, s-maxage=900' },
    })
  } catch (e) {
    return new Response(e instanceof Error ? e.message : 'upstream error', { status: 502 })
  }
}
