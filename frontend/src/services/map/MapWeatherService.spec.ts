import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest'
import WeatherService, { skyToKind, skyLabel, weatherBasis, MID_TERM_FROM } from './MapWeatherService'

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

  it('조합형은 강수가 이긴다 - 눈은 눈 아이콘(#41)', () => {
    // ★ 실측: 중기예보는 "흐리고 비"처럼 준다 - 포함 검사 순서가 바뀌면 구름이 된다
    expect(skyToKind('흐리고 비')).toBe('비')
    expect(skyToKind('구름많고 소나기')).toBe('비')
    expect(skyToKind('흐리고 눈')).toBe('눈')
    expect(skyToKind('비/눈')).toBe('눈')
  })

  it('모르는 값은 구름으로 뭉갠다 - 맑다고 단정하지 않는다', () => {
    expect(skyToKind('황사')).toBe('구름')
    expect(skyToKind(null)).toBeNull()
  })
})

describe('기준 지역 라벨 (최종점검 #42: 4일째부터는 제주 한 지점 중기예보라 권역 기준이 아니다)', () => {
  it('3일째까지 권역, 4일째부터 제주', () => {
    expect(MID_TERM_FROM).toBe(4)
    expect(weatherBasis(0, '남부')).toBe('남부')
    expect(weatherBasis(3, '남부')).toBe('남부')
    expect(weatherBasis(4, '남부')).toBe('제주')
    expect(weatherBasis(6, '북부')).toBe('제주')
  })
  it('권역을 모르면 단기 구간은 null(라벨 생략), 중기 구간은 제주', () => {
    expect(weatherBasis(1, null)).toBeNull()
    expect(weatherBasis(5, null)).toBe('제주')
  })
})

describe('하늘상태 → 표시 글자 (최종점검 #41: 눈·소나기를 비라고 적지 않는다)', () => {
  it('강수는 종류를 살린다 - 조합형은 강수 쪽', () => {
    expect(skyLabel('눈')).toBe('눈')
    expect(skyLabel('흐리고 눈')).toBe('눈')
    expect(skyLabel('구름많고 소나기')).toBe('소나기')
    expect(skyLabel('흐리고 비/눈')).toBe('비/눈')
    expect(skyLabel('흐리고 비')).toBe('비')
  })

  it('흐림은 구름과 구분해 적고, 모르는 값은 원문 그대로', () => {
    expect(skyLabel('흐림')).toBe('흐림')
    expect(skyLabel('구름많음')).toBe('구름')
    expect(skyLabel('맑음')).toBe('맑음')
    expect(skyLabel('황사')).toBe('황사')
    expect(skyLabel(null)).toBeNull()
  })
})

describe('로드와 조회', () => {
  it('실응답 7일치가 날짜로 조회된다', async () => {
    mockFetch(REAL)
    expect(await WeatherService.load()).toBe(true)

    expect(WeatherService.byDate('2026-08-30')).toEqual({ k: '구름', label: '흐림', rain: 0, t: 31, tmin: 26, rp: 30, issued: null })   // 아이콘은 구름, 글자는 흐림(#41)
    expect(WeatherService.byDate('2026-09-03')).toEqual({ k: '비', label: '비', rain: 1, t: 31, tmin: 25, rp: 60, issued: null })
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

/** URL 의 region 으로 응답을 갈라 주는 목 - 권역별 캐시가 섞이지 않는지 본다 */
function mockFetchByRegion (byRegion: Record<string, unknown>, reject: string[] = []) {
  vi.stubGlobal('fetch', vi.fn((input: unknown) => {
    const url = String(input)
    const code = Object.keys(byRegion).concat(reject).find(c => url.includes(`region=${c}`)) ?? ''
    if (reject.includes(code)) return Promise.reject(new Error('down'))
    return Promise.resolve({
      ok: true,
      json: () => Promise.resolve({ success: true, code: 2000, message: '', result: byRegion[code] ?? [] })
    })
  }))
}

describe('권역별 날씨 (MAP_006)', () => {
  const north = [{ date: '2026-09-11', minTemp: 22, maxTemp: 26, sky: '맑음', rainProb: 0, issuedAt: '2026-09-11T05:00:00' }]
  const south = [
    { date: '2026-09-11', minTemp: 23, maxTemp: 27, sky: '흐리고 비', rainProb: 70, issuedAt: '2026-09-11T02:00:00' },
    { date: '2026-09-12', minTemp: 23, maxTemp: 28, sky: '맑음', rainProb: 10, issuedAt: '2026-09-11T05:00:00' }
  ]

  it('남부 장소는 남부 격자 값을, 권역을 모르면 북부(기존 동작)를 쓴다', async () => {
    mockFetchByRegion({ NORTH: north, SOUTH: south, EAST: [], WEST: [] })
    expect(await WeatherService.load()).toBe(true)

    expect(WeatherService.byDate('2026-09-11', '남부')?.k).toBe('비')
    expect(WeatherService.byDate('2026-09-11', '북부')?.k).toBe('맑음')
    expect(WeatherService.byDate('2026-09-11')?.k).toBe('맑음')
    expect(WeatherService.byDate('2026-09-11', '전체')?.k).toBe('맑음')
  })

  it('한 권역이 실패하면 그 권역만 null - 다른 권역 값을 빌리지 않는다', async () => {
    mockFetchByRegion({ NORTH: north, SOUTH: south }, ['EAST', 'WEST'])
    expect(await WeatherService.load()).toBe(true)

    expect(WeatherService.byDate('2026-09-11', '동부')).toBeNull()
    expect(WeatherService.byDate('2026-09-11', '남부')).not.toBeNull()
  })

  it('발표 시각은 날짜를 주면 그 날 것, 없으면 주간 최근 것, 적재분이 아니면 null', async () => {
    mockFetchByRegion({ NORTH: north, SOUTH: south, EAST: [{ date: '2026-09-11', minTemp: 21, maxTemp: 25, sky: '맑음', rainProb: 0 }], WEST: [] })
    await WeatherService.load()

    expect(WeatherService.issuedAt('남부', '2026-09-11')).toBe('2026-09-11T02:00:00')   // 단기 발표분
    expect(WeatherService.issuedAt('남부', '2026-09-12')).toBe('2026-09-11T05:00:00')
    expect(WeatherService.issuedAt('남부')).toBe('2026-09-11T05:00:00')                 // 주간 최근
    expect(WeatherService.issuedAt('남부', '2026-09-20')).toBe('2026-09-11T05:00:00')   // 범위 밖 날짜 → 주간 최근
    expect(WeatherService.issuedAt('동부')).toBeNull()
    expect(WeatherService.issuedAt('서부')).toBeNull()
  })
})
