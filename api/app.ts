// The Fire TV app's endpoints, gated by APP_TOKENS (never PLAYLIST_TOKEN) and private:
// no CORS header, and only the server's own cache may keep a response.
// GET /api/app/channels?t=TOKEN — groups, channels, feed families, badges and both provider fronts.
// GET /api/app/guide?t=TOKEN — the guide as a gzip SQLite file the stick opens without parsing.
// GET /api/app/version, /api/app/apk ?t=TOKEN — the latest build, copied to the box by firetv.yml.
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

// Builds live outside dist/ (deploys prune that), at fixed paths never taken from the request.
// No file extension in the route, so Cloudflare never caches the APK.
const appFile = (name: string) => Bun.file(`${process.env.APP_DIR ?? `${import.meta.dir}/../app`}/${name}`)

async function serveFile(req: Request, name: string, type: string): Promise<Response> {
  if (!appTokenOk(req.url)) return forbidden()
  const file = appFile(name)
  if (!(await file.exists())) return new Response('Not found', { status: 404 })
  return new Response(file, { headers: { 'Content-Type': type, 'Cache-Control': 'no-store' } })
}

export const version = (req: Request) => serveFile(req, 'version.json', 'application/json')
export const apk = (req: Request) => serveFile(req, 'app.apk', 'application/vnd.android.package-archive')
