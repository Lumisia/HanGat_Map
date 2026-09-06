import { createHash } from 'node:crypto'
import { fileURLToPath, URL } from 'node:url'
import { defineConfig } from 'vite'
import vue from '@vitejs/plugin-vue'

/**
 * index.html 의 %CSP% 를 모드에 맞는 Content-Security-Policy 로 바꾼다.
 *
 * - script-src: 'self' + Cloudflare RUM·카카오 SDK + 인라인 테마 스크립트의 sha256 해시.
 *   해시를 쓰면 'unsafe-inline' 없이도 그 블록 하나만 허용된다.
 * - style-src: 'unsafe-inline' 이 필요하다. 개발 모드에서 Vue SFC 의 <style> 이
 *   런타임 JS 로 주입되고, 일부 데이터 시각화가 동적 style 속성을 사용한다.
 *   script-src 의 'unsafe-inline' 과 달리 style-src 쪽은 XSS 실행 경로가 아니라 위험이 훨씬 낮다.
 * - connect-src: 설정된 백엔드 API + HIBP Pwned Passwords(k-익명성 유출 조회)
 *   + 개발 모드 HMR 웹소켓.
 * - img-src: 백엔드 후기 사진 + 지도 타일 CDN + data:/blob: 미리보기.
 *
 * ⚠️ meta 태그로 전달한 CSP 는 frame-ancestors 를 무시한다(스펙상).
 *    클릭재킹 차단은 서버 헤더로 넣어야 한다. README 참고.
 */
function apiOriginForCsp (apiBaseUrl) {
  const value = apiBaseUrl?.trim()

  // /api 같은 동일 출처 경로는 'self'가 이미 허용한다.
  if (!value || value.startsWith('/')) return ''

  const url = new URL(value)
  if (!['http:', 'https:'].includes(url.protocol)) {
    throw new Error('VITE_API_BASE_URL은 HTTP(S) URL 또는 상대 경로여야 합니다.')
  }

  return url.origin
}

function htmlCspPlugin () {
  const apiOrigin = apiOriginForCsp(process.env.VITE_API_BASE_URL)

  return {
    name: 'html-csp',
    enforce: 'post',
    transformIndexHtml (html, ctx) {
      {
        const dev = !!ctx.server

        // 인라인 <script> 본문을 그대로 해시한다 (여는/닫는 태그 제외)
        const hashes = [...html.matchAll(/<script(?![^>]*\bsrc=)[^>]*>([\s\S]*?)<\/script>/gi)]
          .map(m => `'sha256-${createHash('sha256').update(m[1], 'utf8').digest('base64')}'`)

        const directives = [
          "default-src 'none'",
          /*
            통합(2026-08-17): 지도 화면이 들어오며 카카오 출처를 열었다.
            SDK 는 dapi.kakao.com 에서 내려오고, 그 스크립트가 다시 타일 이미지를
            daumcdn / kakaocdn 에서 가져온다.
            폰트는 index.html 의 <link> 로 불러오므로 googleapis / gstatic 도 필요하다.
          */
          /* dapi.kakao.com 의 sdk.js 는 로더일 뿐이고, 실제 지도 코드는
             t1.daumcdn.net/mapjsapi/... 에서 2차로 내려온다. 둘 다 열어야 한다. */
          /* t1.kakaocdn.net: 카카오톡 공유 SDK(kakao.min.js) - useKakaoShare.js 가 내려받는다 */
          /* static.cloudflareinsights.com: Cloudflare가 자동 삽입하는 Web Analytics RUM 비콘 */
          `script-src 'self' https://static.cloudflareinsights.com https://dapi.kakao.com http://t1.daumcdn.net https://t1.daumcdn.net https://t1.kakaocdn.net ${hashes.join(' ')}`.trim(),
          "style-src 'self' 'unsafe-inline' https://fonts.googleapis.com",
          /* 지도 타일은 mts.daumcdn.net, 마커·아이콘은 t1.daumcdn.net 에서 온다.
             카카오 SDK 가 http 로 요청하므로 http 와일드카드도 필요하다
             (운영 빌드는 upgrade-insecure-requests 가 https 로 승격시킨다). */
          /* tong.visitkorea.or.kr: TourAPI 장소 사진(한산 장소 캐러셀 등) - 공사 이미지는 http 원본이라
             http도 열되, 운영 빌드는 upgrade-insecure-requests 가 https 로 승격시킨다. */
          /* 후기는 비공개 MinIO가 아니라 백엔드 조회 API에서 읽는다. */
          `img-src 'self'${apiOrigin ? ` ${apiOrigin}` : ''} data: blob: http://*.daumcdn.net https://*.daumcdn.net http://*.kakaocdn.net https://*.kakaocdn.net http://tong.visitkorea.or.kr https://tong.visitkorea.or.kr${dev ? ' http://localhost:8080 http://127.0.0.1:8080' : ''}`,
          /* 동일 출처 /api는 'self'로, 별도 API 서브도메인은 VITE_API_BASE_URL의
             origin을 빌드 시점에 추가해 운영 요청이 CSP에 막히지 않게 한다. */
          `connect-src 'self'${apiOrigin ? ` ${apiOrigin}` : ''} https://api.pwnedpasswords.com https://dapi.kakao.com http://dapi.kakao.com${dev ? ' http://localhost:8080 ws: wss:' : ''}`,
          "font-src 'self' https://fonts.gstatic.com",
          /* sharer.kakao.com: 카카오톡 공유가 새 창으로 폼 전송하는 목적지 -
             form-action 은 대상 창이 달라도 전송 자체를 막는다 */
          "form-action 'self' https://sharer.kakao.com",
          "base-uri 'none'",
          "object-src 'none'",
          "frame-src 'none'",
          "manifest-src 'self'",
          ...(dev ? [] : ['upgrade-insecure-requests'])
        ]

        // content="%CSP%" 형태만 정확히 바꾼다.
        // 단순 replace('%CSP%') 를 쓰면 주석에 적힌 설명 문구가 먼저 걸린다(실제로 겪은 버그).
        return html.replace(
          /content="%CSP%"/,
          `content="${directives.join('; ')}"`
        )
      }
    }
  }
}

export default defineConfig(() => ({
  plugins: [vue(), htmlCspPlugin()],
  resolve: {
    alias: {
      '@': fileURLToPath(new URL('./src', import.meta.url))
    }
  },
  server: {
    port: 4173,
    // 포트를 못 잡으면 조용히 다른 번호로 옮기지 말고 실패시킨다
    strictPort: true,
    headers: {
      // 개발 서버에도 배포와 같은 헤더를 걸어 두면 로컬에서 미리 깨진다
      'X-Frame-Options': 'DENY',
      'X-Content-Type-Options': 'nosniff',
      'Referrer-Policy': 'no-referrer',
      'Permissions-Policy': 'geolocation=(self), camera=(), microphone=(), payment=()',
      'Cross-Origin-Opener-Policy': 'same-origin'
    }
  },
  preview: {
    port: 5173,
    strictPort: true
  },
  test: {
    environment: 'node',
    include: ['src/**/*.spec.js', 'src/**/*.spec.ts', 'src/**/*.test.ts']
  }
}))
