import { describe, expect, it, vi } from 'vitest'
import { isNaviMobile, startNavi } from './kakaoNavi'

describe('모바일 카카오내비', () => {
  it('화면 너비가 아닌 기기로 구분하며 iPad 데스크톱 UA도 지원한다', () => {
    expect(isNaviMobile('Mozilla Android', 1)).toBe(true)
    expect(isNaviMobile('iPhone', 5)).toBe(true)
    expect(isNaviMobile('Macintosh', 5)).toBe(true)
    expect(isNaviMobile('Windows NT', 10)).toBe(false)
    expect(isNaviMobile('Macintosh', 0)).toBe(false)
  })
  it('선택 장소의 경도·위도를 wgs84 순서로 전달한다', () => {
    const start = vi.fn()
    expect(startNavi({ placeName: '금오름', latitude: 33.35, longitude: 126.30 }, true,
      { isInitialized: () => true, Navi: { start } })).toBe(true)
    expect(start).toHaveBeenCalledWith({ name: '금오름', x: 126.30, y: 33.35, coordType: 'wgs84' })
  })
  it('PC·미초기화·좌표 누락일 때 앱 실행을 막는다', () => {
    const start = vi.fn(); const sdk = { isInitialized: () => true, Navi: { start } }
    const place = { placeName: '금오름', latitude: 33.35, longitude: 126.3 }
    expect(startNavi(place, false, sdk)).toBe(false)
    expect(startNavi({ ...place, latitude: null }, true, sdk)).toBe(false)
    expect(startNavi({ ...place, longitude: NaN }, true, sdk)).toBe(false)
    expect(startNavi(place, true, undefined)).toBe(false)
    expect(startNavi(place, true, { ...sdk, isInitialized: () => false })).toBe(false)
    expect(start).not.toHaveBeenCalled()
  })
})
