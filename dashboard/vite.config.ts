import { createReadStream, existsSync, readdirSync, statSync } from 'node:fs'
import { join, resolve } from 'node:path'
import react from '@vitejs/plugin-react'
import { defineConfig, type Plugin } from 'vite'

const resultsDir = resolve(import.meta.dirname, '../results')

function serveResults(): Plugin {
  return {
    name: 'serve-results',
    configureServer(server) {
      server.middlewares.use('/results', (req, res, next) => {
        const name = decodeURIComponent((req.url ?? '/').split('?')[0].replace(/^\//, ''))
        if (name === '') {
          const files = existsSync(resultsDir) ? readdirSync(resultsDir) : []
          res.setHeader('Content-Type', 'application/json')
          res.end(JSON.stringify(files.map((file) => ({ name: file, type: 'file', mtime: statSync(join(resultsDir, file)).mtime.toUTCString() }))))
          return
        }
        const path = join(resultsDir, name)
        if (name.includes('/') || !name.endsWith('.csv') || !existsSync(path)) return next()
        res.setHeader('Content-Type', 'text/csv')
        createReadStream(path).pipe(res)
      })
    },
  }
}

export default defineConfig({
  base: './',
  plugins: [react(), serveResults()],
  build: { chunkSizeWarningLimit: 800 },
})
