// GET /api/proxy?url=ENCODED — same-origin fetch proxy for the web app
// (playlists/EPG), avoiding CORS. SSRF-hardened: destination must be on the
// host allowlist AND resolve to a public IP; redirects are followed manually
// and re-validated per hop; errors are generic so upstream hosts don't leak.
import { tokenOk } from './_lib.js'
import dns from 'node:dns/promises'
import net from 'node:net'

// Allowed destination hosts: provider + EPG hosts (from env, so they track
// XTREAM_HOST/EPG_URLS), plus myepg, github raw, and this deployment itself.
function allowedSuffixes(selfHost: string): string[] {
  const out = new Set(['myepg.top', 'github.com', 'raw.githubusercontent.com'])
  if (selfHost) out.add(selfHost.split(':')[0].toLowerCase())
  const add = (u: string) => {
    try {
      out.add(new URL(u).hostname.toLowerCase())
    } catch {
      /* ignore malformed */
    }
  }
  if (process.env.XTREAM_HOST) add(process.env.XTREAM_HOST)
  for (const u of (process.env.EPG_URLS ?? '').split(',')) if (u) add(u)
  return [...out]
}

// Block loopback / private / link-local / CGNAT / metadata / multicast ranges.
function ipBlocked(addr: string): boolean {
  const mapped = addr.match(/^::ffff:(\d+\.\d+\.\d+\.\d+)$/i)
  const ip = mapped ? mapped[1] : addr
  if (net.isIPv4(ip)) {
    const [a, b, c] = ip.split('.').map(Number)
    if ([a, b, c].some((n) => Number.isNaN(n))) return true
    if (a === 0 || a === 10 || a === 127) return true
    if (a === 169 && b === 254) return true // link-local + cloud metadata
    if (a === 172 && b >= 16 && b <= 31) return true
    if (a === 192 && b === 168) return true
    if (a === 100 && b >= 64 && b <= 127) return true // CGNAT
    if (a === 192 && b === 0 && c === 0) return true
    if (a >= 224) return true // multicast / reserved
    return false
  }
  if (net.isIPv6(ip)) {
    const v = ip.toLowerCase()
    if (v === '::1' || v === '::') return true
    if (/^f[cd]/.test(v)) return true // fc00::/7 unique-local
    if (/^fe[89ab]/.test(v)) return true // fe80::/10 link-local
    return false
  }
  return true // unrecognized → block
}

async function destinationOk(hostname: string, selfHost: string): Promise<boolean> {
  const host = hostname.toLowerCase()
  const onAllowlist = allowedSuffixes(selfHost).some(
    (s) => host === s || host.endsWith('.' + s),
  )
  if (!onAllowlist) return false
  // DNS-rebinding guard: reject if the host resolves to any non-public address.
  try {
    const addrs = await dns.lookup(host, { all: true })
    return addrs.length > 0 && addrs.every((a) => !ipBlocked(a.address))
  } catch {
    return false
  }
}

export default async function handler(req: Request): Promise<Response> {
  const sameOrigin = req.headers.get('sec-fetch-site') === 'same-origin'
  // Soft caller gate (defense-in-depth; the allowlist below is the real SSRF
  // control, since Sec-Fetch-Site is spoofable by non-browser clients).
  if (!sameOrigin && !tokenOk(req.url)) return new Response('Forbidden', { status: 403 })
  let target = new URL(req.url).searchParams.get('url')
  if (!target || !/^https?:\/\//i.test(target)) return new Response('Bad url', { status: 400 })
  const selfHost = req.headers.get('x-forwarded-host') ?? req.headers.get('host') ?? ''
  try {
    let resp: Response | undefined
    for (let hop = 0; hop < 5; hop++) {
      const u = new URL(target)
      if (!(await destinationOk(u.hostname, selfHost))) {
        return new Response('Destination not allowed', { status: 403 })
      }
      resp = await fetch(target, {
        headers: { 'User-Agent': 'VLC/3.0.18' },
        redirect: 'manual',
      })
      const loc = resp.headers.get('location')
      if (resp.status >= 300 && resp.status < 400 && loc) {
        target = new URL(loc, target).toString()
        if (!/^https?:\/\//i.test(target)) break
        continue
      }
      break
    }
    if (!resp) return new Response('Upstream error', { status: 502 })
    const ct = resp.headers.get('content-type')
    // Streamed through, never buffered: it relays ~20MB guides and GitHub files of any size.
    return new Response(resp.body, { status: resp.status, headers: ct ? { 'Content-Type': ct } : {} })
  } catch {
    return new Response('Upstream error', { status: 502 })
  }
}
