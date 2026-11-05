import fs from 'fs'
import path from 'path'
import { defineConfig, type Plugin } from 'vite'
import react from '@vitejs/plugin-react'
import tailwindcss from '@tailwindcss/vite'
import { Readable } from 'stream'

// dev-only: the /lab/sofa/ logo generator saves and lists configs in lab/sofa/saved
function labSave(): Plugin {
  const dir = path.resolve(__dirname, 'lab/sofa/saved')
  return {
    name: 'lab-save',
    apply: 'serve',
    configureServer(server) {
      server.middlewares.use('/__lab/sofa', async (req, res) => {
        fs.mkdirSync(dir, { recursive: true })
        res.setHeader('content-type', 'application/json')
        if (req.method === 'POST') {
          let body = ''
          for await (const chunk of req) body += chunk
          const cfg = JSON.parse(body)
          const mode = String(cfg.mode).replace(/[^a-z]/g, ''), seed = Number(cfg.seed) | 0
          const file = path.join(dir, `${Date.now()}-${mode}-${seed}.json`)
          fs.writeFileSync(file, JSON.stringify(cfg, null, 2) + '\n')
          res.end(JSON.stringify({ path: path.relative(__dirname, file) }))
          return
        }
        const names = fs.readdirSync(dir).filter(f => f.endsWith('.json')).sort()
        res.end(JSON.stringify(names.map(name => ({ name, config: JSON.parse(fs.readFileSync(path.join(dir, name), 'utf8')) }))))
      })
    },
  }
}

export default defineConfig({
  plugins: [react(), tailwindcss(), labSave()],
  resolve: {
    alias: {
      '@': path.resolve(__dirname, './src'),
    },
  },
  server: {
    proxy: {
      '/stream': {
        target: 'http://localhost:5173',
        bypass: async (req, res) => {
          const url = req.url?.replace('/stream?url=', '')
          if (!url || !res) return
          try {
            const decoded = decodeURIComponent(url)
            const resp = await fetch(decoded, { redirect: 'follow' })
            if (!resp.ok) { res.statusCode = resp.status; res.end(`Upstream ${resp.status}`); return }
            const ct = resp.headers.get('content-type')
            if (ct) res.setHeader('content-type', ct)
            res.setHeader('access-control-allow-origin', '*')
            // stream the response instead of buffering
            if (resp.body) {
              Readable.fromWeb(resp.body as any).pipe(res)
            } else {
              res.end()
            }
          } catch (e: unknown) {
            res.statusCode = 502
            res.end(e instanceof Error ? e.message : 'proxy error')
          }
        },
      },
      '/proxy': {
        target: 'http://localhost:5173',
        bypass: async (req, res) => {
          const url = req.url?.replace('/proxy?url=', '')
          if (!url || !res) return
          try {
            let decoded = decodeURIComponent(url)
            const ghMatch = decoded.match(/^https?:\/\/github\.com\/([^/]+)\/([^/]+)\/raw\/(.+)$/)
            if (ghMatch) decoded = `https://raw.githubusercontent.com/${ghMatch[1]}/${ghMatch[2]}/${ghMatch[3]}`
            const resp = await fetch(decoded)
            if (!resp.ok) { res.statusCode = resp.status; res.end(`Upstream ${resp.status}`); return }
            const ct = resp.headers.get('content-type')
            if (ct) res.setHeader('content-type', ct)
            res.end(Buffer.from(await resp.arrayBuffer()))
          } catch (e: unknown) {
            res.statusCode = 502
            res.end(e instanceof Error ? e.message : 'proxy error')
          }
        },
      },
    },
  },
})
