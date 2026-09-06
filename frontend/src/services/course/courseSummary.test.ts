import { describe, expect, it } from 'vitest'
import { readFileSync } from 'node:fs'
import { accessNotices, routeSummary } from './courseSummary'

describe('AI course measured route summary', () => {
  it('formats measured totals without changing facts', () => {
    const days = [{ total_duration_seconds: 3751, total_distance_meters: 42930 }]
    const before = structuredClone(days)
    expect(routeSummary(days)).toBe('63분 · 42.9km')
    expect(days).toEqual(before)
  })
  it('distinguishes loading and missing facts from zero', () => {
    expect(routeSummary(undefined, true)).toBe('계산 중')
    expect(routeSummary(undefined)).toBe('정보 없음')
    expect(routeSummary([])).toBe('정보 없음')
    expect(routeSummary([{ total_distance_meters: null, total_duration_seconds: null }])).toBe('정보 없음')
    expect(routeSummary([{ total_distance_meters: 0, total_duration_seconds: 0 }])).toBe('0분 · 0.0km')
  })
  it('does not present partial subtotals as complete', () => {
    expect(routeSummary([{
      total_distance_meters: 1000, total_duration_seconds: 100,
      legs: [{ distance_meters: 1000, duration_seconds: 100 }, { distance_meters: null, duration_seconds: null }],
    }])).toBe('일부 구간 정보 없음')
  })
  it('rejects invalid totals and deduplicates access notices', () => {
    expect(routeSummary([{ total_distance_meters: NaN, total_duration_seconds: 1 }])).toBe('정보 없음')
    const endpoint = { access_point: { notice: '어승생악 인근 어리목주차장까지 차량 경로' } }
    expect(accessNotices([{ legs: [{ from: endpoint, to: endpoint }] }])).toEqual([endpoint.access_point.notice])
    expect(accessNotices()).toEqual([])
  })
  it('keeps result independent of map and guards missing costs', () => {
    const view = readFileSync(new URL('../../views/ai-course/AiCourseView.vue', import.meta.url), 'utf8')
    expect(view).not.toMatch(/CourseBridge|MapCanvas|MapView|CoursePanel|services\/map/)
    expect(view).toContain("if (!summary?.has_cost_data) return '정보 없음'")
    expect(view).toContain("result.value.status !== 'SAVED'")
    expect(view).toContain('course: String(result.value.id)')
    expect(view).not.toContain('stashAiCourse')
  })
})
