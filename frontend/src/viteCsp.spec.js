import { afterEach, describe, expect, it, vi } from 'vitest'
import { build } from 'vite'
import { mkdtemp, readFile, rm } from 'node:fs/promises'
import { tmpdir } from 'node:os'
import { join } from 'node:path'
import { fileURLToPath } from 'node:url'

const FRONTEND_ROOT = fileURLToPath(new URL('..', import.meta.url))

/**
 * 이 케이스는 프론트를 실제로 프로덕션 빌드한다 - vitest 기본 5초로는 모자란다.
 * 개발 머신에서는 2초쯤이지만 CI 컨테이너(node:22-alpine 콜드 스타트, Docker 호스트 공유)에서는
 * 5초를 넘긴다. 실제로 빌드 #11이 5067ms로 67ms 모자라 실패해 배포가 막혔다(PR #60·#62에도
 * "기존 타임아웃"으로 언급된 그 케이스다). 재시도로 넘길 성질이 아니라 기준이 잘못 잡힌 것이라
 * 번들링에 걸리는 실제 시간에 맞춘다.
 */
const BUILD_TIMEOUT_MS = 60_000

let outputDirectory

afterEach(async () => {
  vi.unstubAllEnvs()

  if (outputDirectory) {
    const target = outputDirectory
    outputDirectory = undefined
    try {
      // 타임아웃으로 중단되면 vite가 아직 파일을 쓰는 중이라 ENOTEMPTY가 난다. 잠깐 기다렸다 재시도한다.
      await rm(target, { recursive: true, force: true, maxRetries: 5, retryDelay: 100 })
    } catch (error) {
      // 임시 폴더 정리 실패가 진짜 실패 원인을 덮지 않게 한다 - OS가 tmpdir를 결국 회수한다.
      console.warn(`임시 빌드 폴더 정리 실패: ${target}`, error)
    }
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
  }, BUILD_TIMEOUT_MS)

  it('lets the kakao share form follow its login redirect', async () => {
    outputDirectory = await mkdtemp(join(tmpdir(), 'hangat-csp-'))

    await build({
      root: FRONTEND_ROOT,
      logLevel: 'silent',
      build: { outDir: outputDirectory, emptyOutDir: true }
    })

    const html = await readFile(join(outputDirectory, 'index.html'), 'utf8')

    // 로그인 안 된 사용자는 sharer → accounts 로 302 된다. 둘 중 하나만 있으면 팝업이 about:blank 로 멈춘다
    expect(html).toContain(
      "form-action 'self' https://sharer.kakao.com https://accounts.kakao.com"
    )
  }, BUILD_TIMEOUT_MS)
})
