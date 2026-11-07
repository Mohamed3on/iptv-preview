// bun lab/sofa/serve.ts — the Sofa logo lab on http://localhost:5173: tune the mark, save configs to
// lab/sofa/saved (listed in the rail), then write the app's drawables with export.ts.
import { mkdirSync, readdirSync } from 'node:fs'
import { join, relative } from 'node:path'
import page from './index.html'

const dir = join(import.meta.dir, 'saved')
const root = join(import.meta.dir, '../..')

const server = Bun.serve({
  port: 5173,
  routes: {
    '/': page,
    '/__lab/sofa': {
      GET: async () => {
        mkdirSync(dir, { recursive: true })
        const names = readdirSync(dir).filter((f) => f.endsWith('.json')).sort()
        return Response.json(await Promise.all(names.map(async (name) => ({ name, config: await Bun.file(join(dir, name)).json() }))))
      },
      POST: async (req) => {
        mkdirSync(dir, { recursive: true })
        const cfg = await req.json()
        const mode = String(cfg.mode).replace(/[^a-z]/g, ''), seed = Number(cfg.seed) | 0
        const file = join(dir, `${Date.now()}-${mode}-${seed}.json`)
        await Bun.write(file, JSON.stringify(cfg, null, 2) + '\n')
        return Response.json({ path: relative(root, file) })
      },
    },
  },
})
console.log(`Sofa logo lab: ${server.url}`)
