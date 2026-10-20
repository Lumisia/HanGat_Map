import { describe, expect, it, vi } from 'vitest'
import { syncConfirmedCourseCondition, useAccommodationSelection } from './accommodationSelection'
import type { AccommodationInput, CourseCondition, CourseResult } from '../../assets/types/course'

const oldHotel: AccommodationInput = { source_code: 'KAKAO_LOCAL', source_place_id: 'old', place_name: '기존 숙소', latitude: 33.4, longitude: 126.5 }
const selected: AccommodationInput = { source_code: 'KAKAO_LOCAL', source_place_id: 'new', place_name: '선택 숙소', latitude: 33.5, longitude: 126.6 }
const condition: CourseCondition = { start_date: '2026-09-11', end_date: '2026-09-11', people: 2,
  transport: 'PUBLIC_TRANSIT', course_regions: [], course_styles: [], course_place_preferences: [], accommodation: oldHotel }
const course: CourseResult = { id: 49, course_type: 'USER', status: 'READY', ...condition, accommodation: oldHotel, days: [],
  claim_token: 'course-proof', claim_expires_at: '2099-01-01T00:00:00Z' }

describe('authoritative accommodation selection', () => {
  it('shows the selected hotel only after PATCH and detail confirmation', async () => {
    let releasePatch!: (value: AccommodationInput) => void
    const patch = vi.fn(() => new Promise<AccommodationInput>(resolve => { releasePatch = resolve }))
    const read = vi.fn().mockResolvedValue({ ...course, accommodation: selected })
    const flow = useAccommodationSelection(patch, read)
    const request = flow.save(course, selected, condition, false)
    expect(flow.saving.value).toBe(true)
    expect(course.accommodation).toEqual(oldHotel)
    releasePatch(selected)
    const outcome = await request
    expect(outcome?.course.accommodation).toEqual(selected)
    expect(outcome?.selectedStored).toBe(true)
    expect(read.mock.invocationCallOrder[0]).toBeGreaterThan(patch.mock.invocationCallOrder[0]!)
    expect(flow.saving.value).toBe(false)
  })

  it('keeps the existing confirmed hotel and reports PATCH failure', async () => {
    const flow = useAccommodationSelection(vi.fn().mockRejectedValue(new Error('PATCH failed')), vi.fn())
    expect(await flow.save(course, selected, condition, false)).toBeUndefined()
    expect(course.accommodation).toEqual(oldHotel)
    expect(flow.error.value).toContain('기존 확정 숙소')
  })

  it('accepts detail null as authoritative and clears the old condition hotel', async () => {
    const flow = useAccommodationSelection(vi.fn().mockResolvedValue(selected), vi.fn().mockResolvedValue({ ...course, accommodation: null }))
    const outcome = await flow.save(course, selected, condition, false)
    expect(outcome?.course.accommodation).toBeNull()
    expect(outcome?.selectedStored).toBe(false)
    const restoredCondition = structuredClone(condition)
    syncConfirmedCourseCondition(restoredCondition, outcome!.course)
    expect(restoredCondition.accommodation).toBeUndefined()
    expect(flow.error.value).toContain('서버의 최신 결과')
  })

  it('discards a late PATCH/read completion after the view changes', async () => {
    let releaseRead!: (value: CourseResult) => void
    const read = vi.fn(() => new Promise<CourseResult>(resolve => { releaseRead = resolve }))
    const flow = useAccommodationSelection(vi.fn().mockResolvedValue(selected), read)
    const request = flow.save(course, selected, condition, true)
    await Promise.resolve()
    flow.cancel()
    releaseRead({ ...course, accommodation: selected })
    expect(await request).toBeUndefined()
    expect(flow.saving.value).toBe(false)
  })
})
