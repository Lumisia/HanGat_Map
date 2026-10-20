import { describe, it, expect } from 'vitest'
import { dailyWeatherLabel, dayWeatherLabels, dayWeatherBadges, rainyIndoorNotice, RAINY_INDOOR_BADGE, RAINY_PROB_FROM } from './dailyWeather'

describe('rainyIndoorNotice - 비 예보일 실내 위주 배지', () => {
  const evidence = { source_code: 'KMA_SHORT', region_code: 'EAST', spatial_scope: 'REGION', granularity: 'DAILY', issued_at_utc: '2026-09-19T20:00:00Z', temp_min: 20, temp_max: 25 }
  const item = (indoor: boolean | undefined, probability: number | null, region = 'EAST') => ({
    visit_date: '2026-09-20', indoor,
    weather: [{ forecast_date: '2026-09-20', precipitation_probability: probability, daily_evidence: { ...evidence, region_code: region } }],
  })

  it('강수확률이 임계값 이상이고 실내가 과반이면 사실 문장을 준다 - 인과("비라서 담았다")는 주장하지 않는다', () => {
    expect(rainyIndoorNotice([item(true, 80), item(true, 80), item(false, 80)])).toBe(RAINY_INDOOR_BADGE)
    expect(RAINY_INDOOR_BADGE).toBe('비 예보일 · 실내 위주 일정')
    expect(RAINY_PROB_FROM).toBe(60)   // 백엔드 RainyDayRule.PROB_FROM과 같아야 한다
  })

  it('비 예보라도 실내가 과반이 아니면 띄우지 않는다 - 실내 위주라고 말할 수 없다', () => {
    expect(rainyIndoorNotice([item(true, 80), item(false, 80), item(false, 80)])).toBeNull()
    expect(rainyIndoorNotice([item(true, 80), item(false, 80)])).toBeNull()   // 반반은 과반이 아니다
  })

  it('임계값 바로 아래거나 강수확률을 모르면 띄우지 않는다', () => {
    expect(rainyIndoorNotice([item(true, RAINY_PROB_FROM - 1), item(true, RAINY_PROB_FROM - 1)])).toBeNull()
    expect(rainyIndoorNotice([item(true, RAINY_PROB_FROM), item(true, RAINY_PROB_FROM)])).toBe(RAINY_INDOOR_BADGE)
    expect(rainyIndoorNotice([item(true, null), item(true, null)])).toBeNull()
    expect(rainyIndoorNotice([])).toBeNull()
  })

  it('실내 여부가 없는 옛 응답은 실외로 세어 배지를 띄우지 않는다', () => {
    expect(rainyIndoorNotice([item(undefined, 90), item(undefined, 90)])).toBeNull()
  })

  it('권역이 섞인 날은 장소마다 제 권역 예보로 본다 - 비 권역 장소가 실외뿐이면 다른 권역이 실내여도 띄우지 않는다', () => {
    // 동부만 비(80%), 서부는 맑음(10%). 비 권역의 장소가 실외 하나뿐이면 "실내 위주"는 사실이 아니다
    expect(rainyIndoorNotice([item(false, 80, 'EAST'), item(true, 10, 'WEST'), item(true, 10, 'WEST')])).toBeNull()
    // 비 권역 장소 둘이 실내이고 그날 전체도 실내 과반이면 띄운다
    expect(rainyIndoorNotice([item(true, 80, 'EAST'), item(true, 80, 'EAST'), item(false, 10, 'WEST')])).toBe(RAINY_INDOOR_BADGE)
    // 비 권역 장소는 실내지만 그날 전체로는 실외가 과반이면 띄우지 않는다
    expect(rainyIndoorNotice([item(true, 80, 'EAST'), item(false, 10, 'WEST'), item(false, 10, 'WEST')])).toBeNull()
  })
})
describe('stored daily weather', () => {
  it('builds compact badges from actual daily values, preserving zero and missing data', () => {
    const item = { visit_date: '2026-09-20', weather: [{ forecast_date: '2026-09-20', sky_condition_code: '맑음', precipitation_probability: 0,
      daily_evidence: { source_code: 'KMA_SHORT', spatial_scope: 'REGION', issued_at_utc: '2026-09-19T20:00:00Z', granularity: 'DAILY', region_code: 'EAST', temp_min: 20, temp_max: 26 } }] }
    expect(dayWeatherBadges([item, item])).toMatchObject([{ icon: 'sun', state: '맑음', temperature: '20~26°C', rain: '0%', scope: '동부' }])
    expect(dayWeatherBadges([{ ...item, visit_date: '2026-09-21' }])).toMatchObject([{ icon: 'cloud', state: '예보 준비 중', temperature: null, rain: null }])
  })
  const evidence = { source_code: 'KMA_MID', region_code: 'EAST', spatial_scope: 'JEJU_ISLAND', granularity: 'DAILY', issued_at_utc: '2026-09-07T09:00:00', temp_min: 23, temp_max: 29 }
  it('labels island-wide daily evidence, not an hourly temperature', () => {
    const text = dailyWeatherLabel({ visit_date: '2026-09-11', weather: [{ forecast_date: '2026-09-11', sky_condition_code: '흐림', daily_evidence: evidence }] })
    expect(text).toBe('예보 · 기상청 · 발표 9. 7. 18:00 · 흐림 · 23~29°C · 강수확률 정보 없음')
    expect(text).not.toMatch(/UTC|EAST|시부터/)
  })
  it('deduplicates daily evidence and labels island scope', () => {
    const item = { visit_date: '2026-09-11', weather: [{ forecast_date: '2026-09-11', sky_condition_code: '맑음', precipitation_probability: 10, daily_evidence: evidence }] }
    expect(dayWeatherLabels([item, item, item])).toEqual(['제주 전역 중기예보 · 예보 · 기상청 · 발표 9. 7. 18:00 · 맑음 · 23~29°C · 강수확률 10%'])
    const local = (region: string) => ({ ...item, weather: [{ ...item.weather[0], daily_evidence: { ...evidence, spatial_scope: 'REGION', region_code: region } }] })
    expect(dayWeatherLabels([local('EAST'), local('EAST'), local('WEST')])).toEqual(['동부 · 예보 · 기상청 · 발표 9. 7. 18:00 · 맑음 · 23~29°C · 강수확률 10%', '서부 · 예보 · 기상청 · 발표 9. 7. 18:00 · 맑음 · 23~29°C · 강수확률 10%'])
  })
  it('preserves component gaps, explicit zero probability and observed precipitation type', () => {
    const item = { visit_date: '2026-09-11', weather: [{ forecast_date: '2026-09-11', precipitation_probability: 0, daily_evidence: { ...evidence, temp_min: null, temp_max: null } }] }
    expect(dailyWeatherLabel(item)).toContain('상태 정보 없음 · 기온 정보 없음 · 강수확률 0%')
    expect(dailyWeatherLabel({ ...item, weather: [{ ...item.weather[0], sky_condition_code: 'UNKNOWN' }] })).toContain('상태 정보 없음 · 기온 정보 없음 · 강수확률 0%')
    expect(dailyWeatherLabel({ ...item, weather: [{ ...item.weather[0], precipitation_type_code: 'RAIN' }] })).toContain('비 · 기온 정보 없음 · 강수확률 0%')
  })
  it('missing/different-day evidence is unavailable, never sunny or zero', () => {
    expect(dailyWeatherLabel({ visit_date: '2026-09-11' }, '2026-09-11')).toBe('기상청 예보 데이터 없음')
    expect(dailyWeatherLabel({ visit_date: '2026-09-12', weather: [{ forecast_date: '2026-09-11', daily_evidence: evidence }] }, '2026-09-11')).toBe('기상청 예보 데이터 없음')
    expect(dailyWeatherLabel({ visit_date: '2026-09-18', weather: [] }, '2026-09-11'))
      .toBe('기상청 예보 제공 범위 밖 · 날씨 정보 없음')
  })
  it('uses the actual UTC issue instant whether the API includes a Z suffix or not', () => {
    const item = (issued: string) => ({ visit_date: '2026-09-11', weather: [{ forecast_date: '2026-09-11',
      sky_condition_code: '맑음', daily_evidence: { ...evidence, issued_at_utc: issued } }] })
    expect(dailyWeatherLabel(item('2026-09-07T09:00:00'))).toContain('발표 9. 7. 18:00')
    expect(dailyWeatherLabel(item('2026-09-07T09:00:00Z'))).toContain('발표 9. 7. 18:00')
  })
})
