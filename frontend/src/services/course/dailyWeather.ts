import type { CourseItem } from '../../assets/types/course'
import { addCalendarDays, todayKst } from '../../utils/format.js'
type ItemWeather = Pick<CourseItem, 'visit_date' | 'weather'>
/** Compact presentation of the same API evidence, without inventing missing values. */
export function dayWeatherBadges(items: ItemWeather[]) {
  const groups = new Map<string, { key: string; scope: string; icon: string; state: string; temperature: string | null; rain: string | null; detail: string }>()
  for (const item of items) {
    const fact = daily(item)
    const evidence = fact?.daily_evidence
    const scope = evidence?.spatial_scope === 'JEJU_ISLAND' ? '제주 전역' : regions[evidence?.region_code ?? ''] ?? ''
    const key = `${item.visit_date}:${scope}`
    if (groups.has(key)) continue
    const state = !evidence ? '예보 준비 중' : precipitation[fact?.precipitation_type_code ?? ''] || sky[fact?.sky_condition_code ?? ''] || '상태 정보 없음'
    groups.set(key, { key, scope, state,
      icon: state === '맑음' ? 'sun' : state.includes('눈') ? 'snow' : ['비', '소나기'].includes(state) ? 'rain' : 'cloud',
      temperature: evidence?.temp_min != null && evidence.temp_max != null ? `${evidence.temp_min}~${evidence.temp_max}°C`
        : evidence?.temp_min != null ? `최저 ${evidence.temp_min}°C` : evidence?.temp_max != null ? `최고 ${evidence.temp_max}°C` : null,
      rain: fact?.precipitation_probability != null ? `${fact.precipitation_probability}%` : null,
      detail: dailyWeatherLabel(item),
    })
  }
  return [...groups.values()]
}
const regions: Record<string, string> = { NORTH: '북부', SOUTH: '남부', EAST: '동부', WEST: '서부' }
const precipitation: Record<string, string> = { RAIN: '비', RAIN_SNOW: '비·눈', SNOW: '눈', SHOWER: '소나기' }
const sky: Record<string, string> = { '맑음': '맑음', '구름많음': '구름많음', '구름 많음': '구름많음', '흐림': '흐림' }
function daily(item: ItemWeather) {
  return item.weather?.find(f => f.forecast_date === item.visit_date && f.daily_evidence?.granularity === 'DAILY')
}
function issuedAt(value?: string | null) {
  if (!value) return '발표시각 미확인'
  const instant = new Date(/[zZ]|[+-]\d{2}:?\d{2}$/.test(value) ? value : `${value}Z`)
  if (Number.isNaN(instant.getTime())) return '발표시각 미확인'
  return `발표 ${new Intl.DateTimeFormat('ko-KR', { timeZone: 'Asia/Seoul', month: 'numeric', day: 'numeric', hour: '2-digit', minute: '2-digit', hour12: false }).format(instant)}`
}
export function dailyWeatherLabel(item: ItemWeather, today = todayKst()): string {
  const fact = daily(item)
  const evidence = fact?.daily_evidence
  if (!fact || !evidence) return item.visit_date > addCalendarDays(today, 6)
    ? '기상청 예보 제공 범위 밖 · 날씨 정보 없음'
    : '기상청 예보 데이터 없음'
  const state = precipitation[fact.precipitation_type_code ?? ''] || sky[fact.sky_condition_code ?? ''] || '상태 정보 없음'
  const temperature = evidence.temp_min != null && evidence.temp_max != null
    ? `${evidence.temp_min}~${evidence.temp_max}°C`
    : evidence.temp_min != null ? `최저 ${evidence.temp_min}°C · 최고 정보 없음`
      : evidence.temp_max != null ? `최저 정보 없음 · 최고 ${evidence.temp_max}°C` : '기온 정보 없음'
  const rain = fact.precipitation_probability != null ? `강수확률 ${fact.precipitation_probability}%` : '강수확률 정보 없음'
  return `예보 · 기상청 · ${issuedAt(evidence.issued_at_utc)} · ${state} · ${temperature} · ${rain}`
}
/**
 * 비 예보일 임계값(강수확률 %). 백엔드 RainyDayRule.PROB_FROM과 같은 값이어야 한다 -
 * 배치 코스·AI 생성·폴백이 60에서 실내 위주로 담는데 배지만 다른 값을 쓰면 한 화면에서 말이 어긋난다.
 */
export const RAINY_PROB_FROM = 60
/**
 * DAY 머리 배지 문구. 사실만 말한다 - "비 예보라서 실내로 담았다"는 인과는 생성기(폴백·배치)가 장소별 추천 사유에
 * 적을 때만 성립하고, 이 배지는 예보 값과 실내 비율을 보고 붙이므로 그렇게 주장하지 않는다. 실내 여부는 휴리스틱이라 '위주'까지만.
 */
export const RAINY_INDOOR_BADGE = '비 예보일 · 실내 위주 일정'
type ItemIndoor = ItemWeather & Pick<CourseItem, 'indoor'>
const rainyOn = (item: ItemIndoor) => {
  const probability = daily(item)?.precipitation_probability
  return probability != null && probability >= RAINY_PROB_FROM
}
const majorityIndoor = (list: ItemIndoor[]) => list.filter(item => item.indoor === true).length * 2 > list.length
/**
 * DAY 머리 배지 - 장소마다 제 권역의 일 단위 강수확률로 비 예보 여부를 본다(백엔드 폴백 rainyOn과 같은 기준).
 * 비 예보 권역의 장소들 중 실내가 과반이고, 그날 전체로도 실내가 과반일 때만 문구를 준다. 아니면 null.
 * 실내 여부는 백엔드가 이름 키워드로 판정한 indoor 값이다. 값이 없는 옛 응답은 실외로 세어 배지를 안 띄운다.
 */
export function rainyIndoorNotice(items: ItemIndoor[]): string | null {
  const rainy = items.filter(rainyOn)
  if (!rainy.length) return null
  return majorityIndoor(rainy) && majorityIndoor(items) ? RAINY_INDOOR_BADGE : null
}
/** Once per date/region; island forecasts are never presented as local precision. */
export function dayWeatherLabels(items: ItemWeather[]): string[] {
  const groups = new Map<string, { scope: string; text: string }>()
  for (const item of items) {
    const evidence = daily(item)?.daily_evidence
    const island = evidence?.spatial_scope === 'JEJU_ISLAND'
    const key = `${item.visit_date}:${island ? 'JEJU_ISLAND' : evidence?.region_code ?? 'MISSING'}`
    if (!groups.has(key)) groups.set(key, { scope: island ? '제주 전역 중기예보' : regions[evidence?.region_code ?? ''] ?? '', text: dailyWeatherLabel(item) })
  }
  return [...groups.values()].map(g => g.scope && (groups.size > 1 || g.scope === '제주 전역 중기예보') ? `${g.scope} · ${g.text}` : g.text)
}
