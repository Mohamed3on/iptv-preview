// Regenerate the trimmed playlist as a local file (offline copy of /api/playlist).
// Usage: bun scripts/build-local.ts   (reads creds from .env.local)
import { buildM3U, configFromEnv, fetchCuratedChannels, providerEpgUrl } from '../api/_lib.js'

const cfg = configFromEnv()
const channels = await fetchCuratedChannels(cfg)
const dest = `${process.env.HOME}/Downloads/StrongTV Sports Trimmed.m3u`
await Bun.write(dest, buildM3U(cfg, channels, [providerEpgUrl(cfg)]))

const groups = new Map<string, number>()
for (const c of channels) groups.set(c.group, (groups.get(c.group) ?? 0) + 1)
console.log(`Wrote ${channels.length} channels in ${groups.size} groups to ${dest}`)
for (const [g, n] of groups) console.log(`  ${String(n).padStart(4)}  ${g}`)
