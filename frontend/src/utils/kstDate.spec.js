import { describe, expect, it, vi } from 'vitest'
import { todayKst, addCalendarDays, calendarDayOffset, formatCalendarDate, tripPhase } from './format.js'
import { isAlertLive } from '../api/mypage.js'
import { apiRequest } from '../api/backendClient.js'
import { courseMockService } from '../services/courseMockService'

vi.mock('../api/backendClient.js', () => ({ apiRequest: vi.fn() }))

describe('Korean service calendar', () => {
  it('switches at KST midnight, not UTC midnight', () => {
    expect(todayKst(new Date('2026-09-06T14:59:59Z'))).toBe('2026-09-06')
    expect(todayKst(new Date('2026-09-06T15:00:00Z'))).toBe('2026-09-07')
  })
  it.each([
    ['2026-09-05T14:59:59Z', 'UPCOMING'],
    ['2026-09-05T15:00:00Z', 'ONGOING'],
    ['2026-09-08T14:59:59Z', 'ONGOING'],
    ['2026-09-08T15:00:00Z', 'PAST'],
  ])('compares trip dates at %s', (instant, phase) => {
    expect(tripPhase('2026-09-06', '2026-09-08', new Date(instant))).toBe(phase)
  })
  it('retains an old alert through the final KST day only', () => {
    const alert = { createdAt: '2026-08-01T00:00:00Z', affectedDate: '2026-09-06' }
    expect(isAlertLive({ courses: [] }, alert, new Date('2026-09-06T14:59:59Z'))).toBe(true)
    expect(isAlertLive({ courses: [] }, alert, new Date('2026-09-06T15:00:00Z'))).toBe(false)
  })
  it('roundtrips date strings and crosses month/leap-year boundaries', () => {
    expect(addCalendarDays('2026-09-06', 0)).toBe('2026-09-06')
    expect(addCalendarDays('2026-12-31', 1)).toBe('2027-01-01')
    expect(addCalendarDays('2028-02-28', 1)).toBe('2028-02-29')
    expect(calendarDayOffset('2026-09-06', '2026-09-08')).toBe(2)
    expect(formatCalendarDate('2026-09-06')).toContain('6일')
    expect(formatCalendarDate('2026-09-06')).toContain('일')
  })
  it('preserves 2-night/3-day dates across the actual Course API boundary', async () => {
    const start = todayKst(new Date('2026-09-05T15:00:00Z'))
    const dates = [0, 1, 2].map(day => addCalendarDays(start, day))
    const request = { start_date: start, end_date: dates[2], people: 2, transport: 'RENTAL_CAR',
      course_regions: [], course_styles: [], course_place_preferences: [] }
    const result = { id: 1, start_date: start, end_date: dates[2],
      days: dates.map((date, i) => ({ day_no: i + 1, visit_date: date, items: [] })) }
    vi.mocked(apiRequest).mockResolvedValueOnce(result)
    expect(await courseMockService.generateCourse(request)).toEqual(result)
    expect(vi.mocked(apiRequest).mock.calls.at(-1)[1].body).toMatchObject({
      start_date: '2026-09-06', end_date: '2026-09-08',
    })
    expect(result.days.map(day => day.visit_date)).toEqual(['2026-09-06', '2026-09-07', '2026-09-08'])
  })
})
