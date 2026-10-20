import { beforeEach, describe, expect, it } from 'vitest'
import type { CourseCondition, CourseResult } from '../assets/types/course'
import { storePendingCourseClaim, takePendingCourseClaim } from './pendingCourseClaim'

const memory = new Map<string, string>()
Object.defineProperty(globalThis, 'sessionStorage', {
  value: {
    getItem: (key: string) => memory.get(key) ?? null,
    setItem: (key: string, value: string) => memory.set(key, value),
    removeItem: (key: string) => memory.delete(key),
  },
  configurable: true,
})

const condition: CourseCondition = {
  start_date: '2026-09-01', end_date: '2026-09-02', people: 2,
  transport: 'RENTAL_CAR',
  course_regions: [], course_styles: [], course_place_preferences: [],
}

function course(expiresAt = '2099-01-01T00:00:00Z'): CourseResult {
  return {
    id: 11, course_type: 'USER', generation_reason: 'INITIAL', status: 'READY',
    start_date: condition.start_date, end_date: condition.end_date,
    people: 2, transport: 'RENTAL_CAR', days: [],
    claim_token: 'opaque-proof', claim_expires_at: expiresAt,
  }
}

beforeEach(() => memory.clear())

describe('pending course claim', () => {
  it('keeps the proof in session storage and consumes it once without putting it in a URL', () => {
    storePendingCourseClaim(course(), condition, '제주 여행')
    const raw = [...memory.values()][0]

    expect(raw).toContain('opaque-proof')
    expect(raw).not.toContain('http://')
    expect(raw).not.toContain('https://')
    expect(takePendingCourseClaim()).toMatchObject({ title: '제주 여행', condition })
    expect(takePendingCourseClaim()).toBeNull()
  })

  it('drops expired proof instead of retrying it', () => {
    storePendingCourseClaim(course('2020-01-01T00:00:00Z'), condition, '만료 코스')
    expect(takePendingCourseClaim()).toBeNull()
    expect(memory.size).toBe(0)
  })
})
