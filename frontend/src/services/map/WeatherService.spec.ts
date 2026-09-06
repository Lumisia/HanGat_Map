import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest'
import WeatherService, { skyToKind } from './WeatherService'

/** 값은 2026-08-30 실호출 응답에서 그대로 가져왔다. */
const REAL = [
  { date: '2026-08-30', minTemp: 26, maxTemp: 31, sky: '흐림', rainProb: 30 },
  { date: '2026-09-01', minTemp: 25, maxTemp: 30, sky: '맑음', rainProb: 30 },
  { date: '2026-09-02', minTemp: 25, maxTemp: 31, sky: '구름많음', rainProb: 60 },
  { date: '2026-09-03', minTemp: 25, maxTemp: 31, sky: '흐리고 비', rainProb: 60 }
]

function mockFetch (body: unknown) {
  vi.stubGlobal('fetch', vi.fn().mockResolvedValue({
    ok: true,
    json: () => Promise.resolve({ success: true, code: 2000, message: '', result: body })
  }))
}

beforeEach(() => WeatherService.reset())
afterEach(() => vi.unstubAllGlobals())

describe('하늘상태 → 아이콘 종류', () => {
  it('아이콘 3종으로 접는다 - 흐림은 구름이다', () => {
    expect(skyToKind('맑음')).toBe('맑음')
    expect(skyToKind('구름많음')).toBe('구름')
    expect(skyToKind('흐림')).toBe('구름')
  })

  it('조합형은 비가 이긴다', () => {
    // ★ 실측: 중기예보는 "흐리고 비"처럼 준다 - 포함 검사 순서가 바뀌면 구름이 된다
    expect(skyToKind('흐리고 비')).toBe('비')
    expect(skyToKind('구름많고 소나기')).toBe('비')
    expect(skyToKind('흐리고 눈')).toBe('비')
  })

  it('모르는 값은 구름으로 뭉갠다 - 맑다고 단정하지 않는다', () => {
    expect(skyToKind('황사')).toBe('구름')
    expect(skyToKind(null)).toBeNull()
  })
})

describe('로드와 조회', () => {
  it('실응답 7일치가 날짜로 조회된다', async () => {
    mockFetch(REAL)
    expect(await WeatherService.load()).toBe(true)

    expect(WeatherService.byDate('2026-08-30')).toEqual({ k: '구름', rain: 0, t: 31, tmin: 26, rp: 30 })
    expect(WeatherService.byDate('2026-09-03')).toEqual({ k: '비', rain: 1, t: 31, tmin: 25, rp: 60 })
  })

  it('예보 범위 밖은 null - 8일째부터 날씨 칸이 숨는 근거다', async () => {
    mockFetch(REAL)
    await WeatherService.load()

    expect(WeatherService.byDate('2026-09-20')).toBeNull()
  })

  it('로드 전·실패면 전부 null - 가짜 폴백을 만들지 않는다', async () => {
    expect(WeatherService.byDate('2026-08-30')).toBeNull()

    vi.stubGlobal('fetch', vi.fn().mockRejectedValue(new Error('down')))
    expect(await WeatherService.load()).toBe(false)
    expect(WeatherService.byDate('2026-08-30')).toBeNull()
  })

  it('기온 빠진 날은 버린다 - NaN°를 그리지 않는다', async () => {
    mockFetch([{ date: '2026-08-30', minTemp: null, maxTemp: 31, sky: '맑음', rainProb: 0 }])
    expect(await WeatherService.load()).toBe(false)
    expect(WeatherService.byDate('2026-08-30')).toBeNull()
  })
})
