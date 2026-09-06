import { afterEach, describe, expect, it, vi } from 'vitest'
import { build } from 'vite'
import { mkdtemp, readFile, rm } from 'node:fs/promises'
import { tmpdir } from 'node:os'
import { join } from 'node:path'
import { fileURLToPath } from 'node:url'

const FRONTEND_ROOT = fileURLToPath(new URL('..', import.meta.url))

let outputDirectory

afterEach(async () => {
  vi.unstubAllEnvs()

  if (outputDirectory) {
    await rm(outputDirectory, { recursive: true, force: true })
    outputDirectory = undefined
  }
})

describe('production Content-Security-Policy', () => {
  it('allows backend API calls and review images without exposing MinIO', async () => {
    vi.stubEnv('VITE_API_BASE_URL', 'https://api.hangatjeju.com')
    outputDirectory = await mkdtemp(join(tmpdir(), 'hangat-csp-'))

    await build({
      root: FRONTEND_ROOT,
      logLevel: 'silent',
      build: {
        outDir: outputDirectory,
        emptyOutDir: true
      }
    })

    const html = await readFile(join(outputDirectory, 'index.html'), 'utf8')

    expect(html).toContain(
      "connect-src 'self' https://api.hangatjeju.com "
    )
    expect(html).toContain(
      "img-src 'self' https://api.hangatjeju.com "
    )
    expect(html).not.toContain('minio.fileinnout.svc.cluster.local')
  })
})
