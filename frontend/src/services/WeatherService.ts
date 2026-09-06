/**
 * 메인 화면의 "제주 일주일 날씨".
 *
 * 1순위: 백엔드 GET /main/weather (DB에 적재된 기상청 발표분 - 적재 전이면 백엔드가 단기 D+0~3 + 중기 D+4~6을 직접 병합)
 * 폴백: 백엔드가 죽어 있으면 기존 결정적 샘플(utils/crowd.js weatherOn)로 화면을 유지한다.
 *       live 플래그로 어느 쪽인지 알려주므로 화면 라벨이 정직하게 바뀐다 (시연용 vs 기상청).
 */
import { apiGet } from './apiClient'
import { weatherOn } from '../utils/crowd.js'
import { at, fmt } from '../utils/date.js'

export interface DailyWeather {
  /** 8/17 (월) */
  day: string
  /** 화면에는 aria-hidden 으로 들어간다 - 뜻은 description 이 전달한다 */
  icon: string
  /** 최고기온. 예보가 없는 날은 null - 0°로 지어내지 않는다 */
  temperature: number | null
  description: string
}

export interface WeeklyForecast {
  /** true = 기상청 실데이터, false = 시연용 샘플 폴백 */
  live: boolean
  days: DailyWeather[]
}

/** 백엔드 DailyWeather (backend/domain/weather/model/DailyWeather.java 와 동일 모양) */
interface BackendDailyWeather {
  date: string
  minTemp: number | null
  maxTemp: number | null
  sky: string | null
  rainProb: number | null
}

const ICON: Record<string, string> = {
  맑음: '☀️',
  구름: '☁️',
  비: '🌧️'
}

const FORECAST_DAYS = 7

/** 중기예보는 "흐리고 비" 같은 자유 텍스트라 포함 여부로 매핑한다 */
function iconOf (sky: string | null, rainProb: number | null): string {
  if (sky?.includes('눈')) return '🌨️'
  if (sky?.includes('비') || sky?.includes('소나기') || (rainProb ?? 0) >= 60) return '🌧️'
  if (sky?.includes('맑음')) return '☀️'
  return '☁️'
}

export const WeatherService = {
  async getWeeklyForecast (): Promise<WeeklyForecast> {
    try {
      const days = await apiGet<BackendDailyWeather[]>('/main/weather')
      return {
        live: true,
        days: days.map(item => ({
          day: fmt(new Date(item.date)),
          icon: iconOf(item.sky, item.rainProb),
          temperature: item.maxTemp,
          description: [
            item.sky ?? '예보 준비 중',
            item.minTemp !== null ? `최저 ${item.minTemp}°` : null,
            item.rainProb ? `강수 ${item.rainProb}%` : null
          ].filter(Boolean).join(' · ')
        }))
      }
    } catch {
      return { live: false, days: sampleForecast() }
    }
  }
}

/** 백엔드 폴백 - 기존 결정적 샘플. 지도·마이페이지 배지와 같은 값(weatherOn)을 본다 */
function sampleForecast (): DailyWeather[] {
  return Array.from({ length: FORECAST_DAYS }, (_, i) => {
    const date = at(i)
    const w = weatherOn(date)
    return {
      day: fmt(date),
      icon: ICON[w.k] ?? '☁️',
      temperature: w.t,
      description: `${w.k} · 최저 ${w.tmin}°`
    }
  })
}

export default WeatherService
