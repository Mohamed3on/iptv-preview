import { defineConfig } from 'vite'
import react from '@vitejs/plugin-react'

export default defineConfig({
  plugins: [react()],
  server: {
    proxy: {
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
