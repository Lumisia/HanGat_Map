/**
 * 주간 날씨 (MAP-05).
 *
 * 백엔드 `GET /main/weather`는 기상청 단기(D+0~3)+중기(D+4~6)를 병합한 7일치를 준다.
 * 화면 슬라이더는 30일이라 <b>8일째부터는 값이 없는 게 정상</b>이고, 날씨 칸은 숨긴다.
 *
 * `wxOf()`가 동기 함수(소비처 4곳)라 API 를 비동기로 흘리지 않고,
 * 지도 진입 때 한 번 받아 캐시에 넣고 wxOf 는 캐시 조회만 한다.
 */
import { apiGet } from '../apiClient'

/** 화면이 쓰는 하루치. 기존 wxOf() 반환 모양과 같다 */
export interface DayWeather {
  /** 아이콘 종류 - 맑음 | 구름 | 비 (wxIcon 이 아는 3종) */
  k: string
  /** 비·눈 예보 여부. 코스 생성이 실내 가중치에 쓴다 */
  rain: number
  /** 최고기온 */
  t: number
  /** 최저기온 */
  tmin: number
  /** 강수확률(%). 백엔드가 안 주면 null - 카드가 30% 이상일 때만 표시한다 */
  rp: number | null
}

/** 백엔드 DailyWeather (domain/weather/model/DailyWeather.java 와 동일 모양) */
interface BackendDaily {
  date: string
  minTemp: number | null
  maxTemp: number | null
  sky: string | null
  rainProb: number | null
}

/** 날짜(YYYY-MM-DD) → 하루치. 캐시가 비면 wxOf 는 전부 null 을 돌려준다 */
let cache: Record<string, DayWeather> = {}

/**
 * 기상청 하늘상태 → 아이콘 종류. 아이콘이 3종뿐이라 흐림은 구름으로 합친다.
 * "흐리고 비" 같은 조합형이 있어 포함 검사로 판정한다 - 비·눈이 먼저다.
 */
export function skyToKind (sky: string | null): string | null {
  if (!sky) return null
  if (sky.includes('비') || sky.includes('눈') || sky.includes('소나기')) return '비'
  if (sky.includes('구름') || sky.includes('흐림')) return '구름'
  if (sky.includes('맑음')) return '맑음'
  return '구름'
}

export const WeatherService = {
  /** 지도 진입 때 한 번. 실패해도 던지지 않는다 - 날씨 칸만 안 보인다 */
  async load (): Promise<boolean> {
    try {
      const rows = await apiGet<BackendDaily[]>('/main/weather')
      const next: Record<string, DayWeather> = {}
      for (const r of rows) {
        const k = skyToKind(r.sky)
        // 기온이나 하늘이 빠진 날은 버린다 - 반쪽 정보로 그리면 NaN°가 뜬다
        if (k == null || r.maxTemp == null || r.minTemp == null) continue
        next[r.date] = { k, rain: k === '비' ? 1 : 0, t: r.maxTemp, tmin: r.minTemp, rp: r.rainProb ?? null }
      }
      cache = next
      return Object.keys(next).length > 0
    } catch {
      return false
    }
  },

  /** 해당 날짜의 날씨. 예보 범위 밖·로드 전·실패면 null */
  byDate (isoDate: string): DayWeather | null {
    return cache[isoDate] ?? null
  },

  /** 테스트 전용 - 캐시를 비운다 */
  reset (): void {
    cache = {}
  }
}

export default WeatherService
