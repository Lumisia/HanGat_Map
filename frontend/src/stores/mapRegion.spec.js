import { afterEach, describe, expect, it, vi } from 'vitest'
import { nextTick } from 'vue'

/* 백엔드 찜 API 는 여기서 안 쓴다 - import 만 막는다 */
const favApi = { ids: vi.fn(), add: vi.fn(), remove: vi.fn() }
vi.mock('../services/map/FavoriteApiService', () => ({ default: favApi, FavoriteApiService: favApi }))

/** Storage 흉내 - 탭 하나의 sessionStorage */
function storage (initial = {}) {
  const m = new Map(Object.entries(initial))
  return {
    getItem: k => (m.has(k) ? m.get(k) : null),
    setItem: (k, v) => m.set(k, String(v)),
    removeItem: k => m.delete(k)
  }
}

/* mapStore 는 import 시점에 기본 권역을 정하므로 케이스마다 모듈을 새로 읽는다 */
async function loadStore (session) {
  vi.resetModules()
  vi.stubGlobal('sessionStorage', session)
  vi.stubGlobal('localStorage', storage())
  return import('./mapStore.js')
}

afterEach(() => vi.unstubAllGlobals())

describe('map region default (MAP_001)', () => {
  it('starts on 전체 when nothing is remembered', async () => {
    const s = await loadStore(storage())
    expect(s.state.F.reg).toBe('전체')
  })

  it('restores the region picked earlier in this tab', async () => {
    const s = await loadStore(storage({ hangat_map_region: '남부' }))
    expect(s.state.F.reg).toBe('남부')
  })

  it('ignores a remembered value that is not one of our regions', async () => {
    const s = await loadStore(storage({ hangat_map_region: '부산' }))
    expect(s.state.F.reg).toBe('전체')
  })

  it('remembers a new pick for the rest of the tab', async () => {
    const session = storage()
    const s = await loadStore(session)
    s.state.F.reg = '동부'
    await nextTick()
    expect(session.getItem('hangat_map_region')).toBe('동부')
  })

  it('falls back to 전체 when storage throws (private mode)', async () => {
    const broken = { getItem: () => { throw new Error('blocked') }, setItem: () => { throw new Error('blocked') }, removeItem: () => {} }
    const s = await loadStore(broken)
    expect(s.state.F.reg).toBe('전체')
    s.state.F.reg = '북부'   // 저장이 막혀도 화면은 바뀌어야 한다
    await nextTick()
    expect(s.state.F.reg).toBe('북부')
  })
})
