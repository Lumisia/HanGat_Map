import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'

/* 지도용 카카오 SDK 로더 - "실패한 뒤 다시 시도하는가"와 "응답이 없으면 끊는가"만 본다(최종점검 #50).
   테스트 환경은 node 라 document·window 를 최소한으로 흉내낸다: 붙인 <script> 를 기록해 두고 성공·실패·무응답을 우리가 정한다 */
let scripts
function fakeDom () {
  scripts = []
  const mk = () => {
    const el = { src: '', async: false, onload: null, onerror: null, removed: false, remove () { this.removed = true } }
    return el
  }
  vi.stubGlobal('document', { createElement: () => mk(), head: { appendChild: el => scripts.push(el) } })
  vi.stubGlobal('window', {})
}
/** 카카오 SDK 가 정상 도착한 상황 - autoload=false 라 kakao.maps.load(cb) 를 불러야 Map 이 생긴다 */
function arriveSdk (el) {
  window.kakao = { maps: { load: cb => { window.kakao.maps.Map = function () {}; cb() } } }
  el.onload()
}

async function freshLoader () {
  vi.resetModules()
  vi.stubEnv('VITE_KAKAO_MAP_KEY', 'test-key')
  return import('./useKakaoLoader.js')
}

beforeEach(() => { vi.useFakeTimers(); fakeDom() })
afterEach(() => { vi.useRealTimers(); vi.unstubAllGlobals(); vi.unstubAllEnvs() })

describe('loadKakaoMap', () => {
  it('SDK 주소에 장소 검색 라이브러리(services)가 붙는다 - AI코스 검색이 쓴다(#14)', async () => {
    const { loadKakaoMap } = await freshLoader()
    const p = loadKakaoMap()
    expect(scripts[0].src).toContain('libraries=services')
    arriveSdk(scripts[0])
    await expect(p).resolves.toBe(window.kakao)
  })

  it('성공하면 다음 호출은 스크립트를 다시 붙이지 않는다', async () => {
    const { loadKakaoMap } = await freshLoader()
    const p = loadKakaoMap(); arriveSdk(scripts[0]); await p
    await loadKakaoMap()
    expect(scripts).toHaveLength(1)
  })

  it('불러오기 실패 뒤 다시 부르면 새로 시도한다 - 전엔 실패를 기억해 새로고침 전까지 실패 화면만 보였다', async () => {
    const { loadKakaoMap } = await freshLoader()
    const first = loadKakaoMap()
    scripts[0].onerror()
    await expect(first).rejects.toThrow('불러오지 못했어요')
    expect(scripts[0].removed).toBe(true)          // 죽은 태그는 치운다

    const second = loadKakaoMap()                  // 신호가 돌아온 뒤 메인 → 지도 재진입
    expect(scripts).toHaveLength(2)                // 새 <script> 를 붙였다
    arriveSdk(scripts[1])
    await expect(second).resolves.toBe(window.kakao)
  })

  it('8초 동안 성공도 실패도 없으면 "응답하지 않아요"로 끝낸다 - 안내 없이 빈 지도로 남지 않게', async () => {
    const { loadKakaoMap, TIMEOUT_MS } = await freshLoader()
    const p = loadKakaoMap()
    const rejected = expect(p).rejects.toThrow('응답하지 않아요')
    vi.advanceTimersByTime(TIMEOUT_MS)
    await rejected
    expect(scripts[0].removed).toBe(true)
    loadKakaoMap()
    expect(scripts).toHaveLength(2)                // 타임아웃 뒤에도 다음 진입은 다시 시도
  })

  it('제때 도착하면 타이머가 실패를 일으키지 않는다', async () => {
    const { loadKakaoMap, TIMEOUT_MS } = await freshLoader()
    const p = loadKakaoMap()
    arriveSdk(scripts[0])
    await p
    vi.advanceTimersByTime(TIMEOUT_MS + 1000)
    expect(scripts[0].removed).toBe(false)
  })

  it('키가 비어 있으면 스크립트를 붙이지 않고 바로 실패한다', async () => {
    vi.resetModules()
    vi.stubEnv('VITE_KAKAO_MAP_KEY', '')
    const { loadKakaoMap } = await import('./useKakaoLoader.js')
    await expect(loadKakaoMap()).rejects.toThrow('VITE_KAKAO_MAP_KEY')
    expect(scripts).toHaveLength(0)
  })
})
