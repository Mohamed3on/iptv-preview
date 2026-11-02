// The server the app's profiling builds talk to on an emulator (http://10.0.2.2:8791): today's real
// channels and guide, fetched once from the real server, with the provider route pointed at a dead
// local port. So no stream ever reaches the provider from the emulator (the account has one stream
// slot, and the stick may be using it), and nothing the emulator does reaches the box's event log.
// Usage: bun firetv/baselineprofile/fixture-server.ts  (reads IPTV_SERVER_URL / IPTV_APP_TOKEN from
// the environment or ~/.gradle/gradle.properties, like the Gradle build).
import { homedir } from 'node:os'
import { join } from 'node:path'

const PORT = 8791
const DEAD = 'http://10.0.2.2:9' // the emulator's host, a port nothing listens on: refused at once

async function secret(name: string): Promise<string> {
  if (process.env[name]) return process.env[name]!
  const props = await Bun.file(join(homedir(), '.gradle', 'gradle.properties')).text().catch(() => '')
  const line = props.split('\n').find((l) => l.startsWith(`${name}=`))
  if (!line) throw new Error(`${name} is not set (env or ~/.gradle/gradle.properties)`)
  return line.slice(name.length + 1).trim()
}

const base = await secret('IPTV_SERVER_URL')
const token = await secret('IPTV_APP_TOKEN')
const get = async (kind: string) => {
  const res = await fetch(`${base}/api/app/${kind}?t=${encodeURIComponent(token)}`)
  if (!res.ok) throw new Error(`${kind}: HTTP ${res.status}`)
  return res
}

const feed = (await (await get('channels')).json()) as { route: Record<string, unknown> }
feed.route = { ...feed.route, plain: DEAD, vpn: DEAD, user: 'x', pass: 'x' }
const channels = JSON.stringify(feed)
const guide = new Uint8Array(await (await get('guide')).arrayBuffer())

Bun.serve({
  port: PORT,
  hostname: '127.0.0.1',
  fetch(req) {
    const { pathname } = new URL(req.url)
    if (req.method === 'POST') return new Response(null, { status: 204 }) // events, quality readings
    if (pathname === '/api/app/channels') return new Response(channels, { headers: { 'Content-Type': 'application/json' } })
    if (pathname === '/api/app/guide') return new Response(guide, { headers: { 'Content-Type': 'application/gzip' } })
    return new Response('Not found', { status: 404 }) // version, apk: no update
  },
})
console.log(`fixture server on :${PORT} (${channels.length} B channels, ${guide.length} B guide)`)
