import { beforeEach, describe, expect, it } from 'vitest'
import type { CourseCondition, CourseItem } from '../assets/types/course'
import { calculateCourseCostSummary, generateMockCourseForTest } from './courseMockService'
import { savedCourseMockService } from './savedCourseMockService'
import { getMockWeather, weatherRecommendationAdjustment } from './weatherMockService'

const condition = (fixed = false): CourseCondition => ({
  start_date: '2026-08-13', end_date: '2026-08-15', people: 2, transport: 'RENTAL_CAR',
  course_regions: [{ region_id: 1, code: 'EAST', name: '동부' }],
  course_styles: [{ tag_id: 1, code: 'NATURE', name: '자연', weight: 1 }],
  course_place_preferences: fixed ? [{ place_id: 103, place_name: '성산일출봉', preference_type: 'WANT', fixed_date: '2026-08-15', fixed_time: '15:00' }] : [],
})

describe('COURSE_004 weather', () => {
  it('penalizes rainy outdoor places and promotes rainy indoor places', () => {
    const rain = getMockWeather('2026-08-15', '12:00')
    expect(rain.weather_condition).toBe('RAIN')
    expect(weatherRecommendationAdjustment(rain, 'OUTDOOR', 'BEACH')).toBeLessThan(0)
    expect(weatherRecommendationAdjustment(rain, 'INDOOR', 'CAFE')).toBeGreaterThan(0)
  })

  it('keeps a rainy USER_FIXED schedule and adds a warning', async () => {
    const course = await generateMockCourseForTest(condition(true), 'INITIAL')
    const fixed = course.days.flatMap(day => day.items).find(item => item.place_name === '성산일출봉')!
    expect(fixed).toMatchObject({ visit_date: '2026-08-15', start_time: '15:00', item_source: 'USER_FIXED', weather_condition: 'RAIN' })
    expect(fixed.weather_warning).toContain('사용자 지정대로 유지')
  })
})

describe('COURSE_006 cost accuracy', () => {
  it('separates verified, estimated, and unknown costs without treating unknown as free', () => {
    const item = { costs: [
      { id: 1, course_id: 1, category: 'FOOD', accuracy_type: 'VERIFIED', amount_min: 16000, amount_max: 16000, currency: 'KRW' },
      { id: 2, course_id: 1, category: 'LODGING', accuracy_type: 'ESTIMATED', amount_min: 80000, amount_max: 120000, currency: 'KRW' },
      { id: 3, course_id: 1, category: 'OTHER', accuracy_type: 'UNKNOWN', currency: 'KRW' },
    ] } as CourseItem
    const summary = calculateCourseCostSummary([item])
    expect(summary.verified_amount).toBe(16000)
    expect(summary.estimated_min).toBe(140000)
    expect(summary.estimated_max).toBe(230000)
    expect(summary.unknown_count).toBe(1)
  })
})

describe('COURSE_008 mock saving', () => {
  beforeEach(() => savedCourseMockService.clear())

  it('saves once, returns a summary, preserves coordinates, and blocks duplicate course IDs', async () => {
    const generated = await generateMockCourseForTest(condition(), 'INITIAL')
    const record = await savedCourseMockService.save(generated, '동부 자연 여행')
    expect(record.course.status).toBe('SAVED')
    expect(record.summary).toMatchObject({ course_id: generated.id, title: '동부 자연 여행', start_date: generated.start_date, end_date: generated.end_date })
    expect(record.summary.representative_places.length).toBeGreaterThan(0)
    expect(record.course.days.flatMap(day => day.items).every(item => item.latitude != null && item.longitude != null)).toBe(true)
    await expect(savedCourseMockService.save(generated, '중복 저장')).rejects.toThrow('이미 저장된 코스')
    expect(savedCourseMockService.list()).toHaveLength(1)
  })
})
