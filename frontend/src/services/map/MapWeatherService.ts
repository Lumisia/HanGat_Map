/**
 * 주간 날씨 (MAP-05).
 *
 * 백엔드 `GET /main/weather?region=`는 그 권역 격자의 기상청 단기(D+0~3)+중기(D+4~6)를 병합한 7일치를 준다.
 * 화면 슬라이더는 30일이라 <b>8일째부터는 값이 없는 게 정상</b>이고, 날씨 칸은 숨긴다.
 *
 * 권역 4개를 진입 때 한 번에 받아 캐시한다 - 제주는 한라산 남북으로 날씨가 갈려 남부 장소에 제주시 값을 보여주면
 * 틀린 정보가 된다(MAP_006). `wxOf()`가 동기 함수(소비처 4곳)라 API 를 비동기로 흘리지 않고 캐시 조회만 한다.
 */
import { apiGet } from '../apiClient'

/** 화면이 쓰는 하루치. 기존 wxOf() 반환 모양과 같다 */
export interface DayWeather {
  /** 아이콘 종류 - 맑음 | 구름 | 비 | 눈 (wxIcon 이 아는 4종. 눈은 2026-09-14 추가, 최종점검 #41) */
  k: string
  /** 표시용 날씨 말 - 맑음 | 구름 | 흐림 | 비 | 눈 | 비/눈 | 소나기. 아이콘보다 잘게 - 소나기·흐림도 글자로는 살린다(최종점검 #41) */
  label: string
  /** 비·눈 예보 여부. 코스 생성이 실내 가중치에 쓴다 */
  rain: number
  /** 최고기온 */
  t: number
  /** 최저기온 */
  tmin: number
  /** 강수확률(%). 백엔드가 안 주면 null - 카드가 30% 이상일 때만 표시한다 */
  rp: number | null
  /** 이 날 예보의 기상청 발표 시각(KST ISO). 단기(D+0~3)와 중기(D+4~6)는 발표 시각이 다르다. 적재분이 아니면 null */
  issued: string | null
}

/** 백엔드 DailyWeather (domain/weather/model/DailyWeather.java 와 동일 모양) */
interface BackendDaily {
  date: string
  minTemp: number | null
  maxTemp: number | null
  sky: string | null
  rainProb: number | null
  /** 기상청 발표 시각(KST ISO). 적재분이 아니면 없음 */
  issuedAt?: string | null
}

/**
 * 오늘로부터 며칠째부터 중기예보인가. 백엔드가 단기(D+0~3, 권역 격자별)와 중기(D+4~, 제주 한 지점 11G00201 을 네 권역에 복사)를
 * 이어 붙이므로, 4일째부터는 "○부 기준"이 아니라 "제주 기준"이다(최종점검 #42). 권역별 중기(서귀포 지점)는 배치 쪽 후속
 */
export const MID_TERM_FROM = 4
/** 날씨 카드·코스 일차 줄에 적는 기준 지역 - 중기 구간이면 권역 대신 '제주' */
export const weatherBasis = (k: number, region: string | null | undefined): string | null =>
  k >= MID_TERM_FROM ? '제주' : (region ?? null)

/** 권역 표시명(장소의 r) → 백엔드 코드. 북부가 기존 /main/weather 기본값이라 권역을 모르면 북부를 쓴다 */
export const REGION_CODE: Record<string, string> = { 북부: 'NORTH', 동부: 'EAST', 남부: 'SOUTH', 서부: 'WEST' }
const DEFAULT_REGION = 'NORTH'
const codeOf = (region?: string | null) => REGION_CODE[region ?? ''] ?? DEFAULT_REGION

/** 권역 코드 → (날짜(YYYY-MM-DD) → 하루치). 비면 wxOf 는 전부 null 을 돌려준다 */
let cache: Record<string, Record<string, DayWeather>> = {}
/** 권역 코드 → 그 주에 쓰인 가장 최근 발표 시각(KST ISO). 라벨용, 적재분이 아니면 null */
let issued: Record<string, string | null> = {}

/**
 * 기상청 하늘상태 → 아이콘 종류. 흐림은 구름으로, 소나기는 비로 합친다.
 * "흐리고 비" 같은 조합형이 있어 포함 검사로 판정한다 - 눈 > 비 > 구름 순("비/눈"은 눈 아이콘).
 */
export function skyToKind (sky: string | null): string | null {
  if (!sky) return null
  if (sky.includes('눈')) return '눈'
  if (sky.includes('비') || sky.includes('소나기')) return '비'
  if (sky.includes('구름') || sky.includes('흐림')) return '구름'
  if (sky.includes('맑음')) return '맑음'
  return '구름'
}

/**
 * 기상청 하늘상태 → 화면에 적는 짧은 말. "흐리고 눈" 처럼 조합형이면 강수 쪽이 이긴다(눈 > 소나기 > 비).
 * 단기예보 배치는 이미 눈/비 한 글자로 저장하고, 중기예보는 기상청 문장 그대로 온다
 */
export function skyLabel (sky: string | null): string | null {
  if (!sky) return null
  if (sky.includes('소나기')) return '소나기'
  if (sky.includes('비') && sky.includes('눈')) return '비/눈'
  if (sky.includes('눈')) return '눈'
  if (sky.includes('비')) return '비'
  if (sky.includes('흐림')) return '흐림'
  if (sky.includes('구름')) return '구름'
  if (sky.includes('맑음')) return '맑음'
  return sky
}

export const WeatherService = {
  /** 지도 진입 때 한 번, 권역 4개를 나란히. 실패해도 던지지 않는다 - 그 권역 날씨 칸만 안 보인다 */
  async load (): Promise<boolean> {
    const codes = Object.values(REGION_CODE)
    const results = await Promise.allSettled(codes.map(c => apiGet<BackendDaily[]>(`/main/weather?region=${c}`)))
    const nextCache: Record<string, Record<string, DayWeather>> = {}
    const nextIssued: Record<string, string | null> = {}
    results.forEach((res, i) => {
      // 한 권역이 실패하면 그 권역만 '예보 전' - 다른 권역 값을 빌려 채우지 않는다
      if (res.status !== 'fulfilled') return
      const days: Record<string, DayWeather> = {}
      let latest: string | null = null
      for (const r of res.value) {
        const k = skyToKind(r.sky)
        // 기온이나 하늘이 빠진 날은 버린다 - 반쪽 정보로 그리면 NaN°가 뜬다
        if (k == null || r.maxTemp == null || r.minTemp == null) continue
        days[r.date] = { k, label: skyLabel(r.sky) ?? k, rain: k === '비' || k === '눈' ? 1 : 0, t: r.maxTemp, tmin: r.minTemp, rp: r.rainProb ?? null, issued: r.issuedAt ?? null }
        if (r.issuedAt && (!latest || r.issuedAt > latest)) latest = r.issuedAt
      }
      nextCache[codes[i]] = days
      nextIssued[codes[i]] = latest
    })
    cache = nextCache
    issued = nextIssued
    return Object.values(nextCache).some(days => Object.keys(days).length > 0)
  },

  /** 해당 날짜·권역의 날씨. 권역을 안 주거나 모르면 북부(기존 동작). 예보 범위 밖·로드 전·실패면 null */
  byDate (isoDate: string, region?: string | null): DayWeather | null {
    return cache[codeOf(region)]?.[isoDate] ?? null
  },

  /**
   * 기상청 발표 시각(KST ISO). 날짜를 주면 그 날 예보의 것(단기 05시·중기 18시가 섞이므로 라벨은 선택한 날 기준),
   * 없거나 그 날 값이 없으면 그 권역 주간 중 가장 최근 것. 적재분이 아니거나 로드 전이면 null
   */
  issuedAt (region?: string | null, isoDate?: string): string | null {
    const code = codeOf(region)
    return (isoDate ? cache[code]?.[isoDate]?.issued : null) ?? issued[code] ?? null
  },

  /** 테스트 전용 - 캐시를 비운다 */
  reset (): void {
    cache = {}
    issued = {}
  }
}

export default WeatherService
