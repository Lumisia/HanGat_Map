import { beforeEach, describe, expect, it, vi } from 'vitest'
import { apiRequest, getBackendUserId } from '../../api/backendClient.js'
import { ApiError } from '../../api/errors.js'
import { RESTORE_KEY, readRestore, rememberResult, rememberEditing, clearRestore, fetchRestoredCourse, resultFromDetail, useResultRestore, validProof, singleFlight, useClaimRenewal, clearCourseProof } from './resultRestore'
import { courseMockService } from '../courseMockService'
import { syncConfirmedCourseCondition } from './accommodationSelection'
import type { CourseDetail, RestoreState } from './resultRestore'
import type { CourseCondition, CourseResult } from '../../assets/types/course'
vi.mock('../../api/backendClient.js', () => ({ apiRequest: vi.fn(), getBackendUserId: vi.fn(() => null) }))
vi.mock('../../api/notifications.js', () => ({ ASYNC_COURSES_ENABLED: true }))
const condition: CourseCondition = { start_date: '2026-09-07', end_date: '2026-09-09', people: 2,
  transport: 'RENTAL_CAR', course_regions: [], course_styles: [], course_place_preferences: [] }
const detail: CourseDetail = { id: 29, course_type: 'USER', status: 'READY', ...condition, swappable: true, manageable: false,
  accommodation: { source_code: 'KAKAO_LOCAL', source_place_id: 'actual-hotel', place_name: '저장된 숙소', latitude: 33.4, longitude: 126.5 },
  days: [7, 8, 9].map((n, i) => ({ day_no: i + 1, visit_date: `2026-09-0${n}`, items: [{ id: 202 + i, place_id: 30 + i,
    place_name: i === 1 ? '서버에서 교체한 장소' : '기존 장소', category_name: '관광', image_url: '/images/real.jpg', day_no: i + 1,
    position: 1, visit_date: `2026-09-0${n}`, start_time: '10:00:00', end_time: '11:00:00',
    item_source: i === 1 ? 'REPLACEMENT' : 'AI_RECOMMENDED', recommendation_reason: '서버에 저장된 추천 이유' }] })) }
function memory() { const m = new Map<string, string>(); return { getItem: (k: string) => m.get(k) ?? null, setItem: (k: string, v: string) => { m.set(k, v) }, removeItem: (k: string) => { m.delete(k) } } }
const state: RestoreState = { mode: 'result', courseId: 29, condition }
beforeEach(() => { vi.clearAllMocks(); vi.mocked(getBackendUserId).mockReturnValue(null) })
describe('same-tab result restoration', () => {
  it('does not restore another account or signed-out member state', () => {
    const port = memory()
    vi.mocked(getBackendUserId).mockReturnValue(6)
    rememberResult(resultFromDetail(detail), condition, port)
    vi.mocked(getBackendUserId).mockReturnValue(7)
    expect(readRestore(port)).toBeNull()
    expect(port.getItem(RESTORE_KEY)).toBeNull()
    rememberEditing(condition, port)
    vi.mocked(getBackendUserId).mockReturnValue(null)
    expect(readRestore(port)).toBeNull()
  })
  it('legacy member restoration rechecks only the course ID without old inputs or proof', () => {
    const port = memory()
    port.setItem(RESTORE_KEY, JSON.stringify({ ...state, claim_token: 'test-proof', claim_expires_at: '2099-01-01T00:00:00Z' }))
    vi.mocked(getBackendUserId).mockReturnValue(6)
    const restored = readRestore(port)!
    expect(restored.courseId).toBe(29)
    expect(restored.ownerId).toBe(6)
    expect(restored.claim_token).toBeUndefined()
    expect(restored.condition.course_place_preferences).toEqual([])
    expect(apiRequest).not.toHaveBeenCalled()
  })
  it.each([undefined, 400000])('restores drafts with removed legacy budget %s', budget => {
    const port = memory()
    const inputs = condition
    port.setItem(RESTORE_KEY, JSON.stringify({ mode: 'editing', condition: { ...inputs, budget_total: budget } }))
    expect(readRestore(port)?.condition).toEqual(inputs)
    expect(JSON.parse(port.getItem(RESTORE_KEY)!).condition).toEqual(inputs)
  })
  it('preserves authoritative budget summary after async generation and saved-course reload', () => {
    const summary = { has_cost_data: true, verified_total: 0,
      estimated_min: 16000, estimated_max: 24000, total_expected_min: 16000,
      total_expected_max: 24000, unknown_count: 2 }
    const restored = resultFromDetail({ ...detail, status: 'SAVED', budget_summary: summary })
    expect(restored.budget_summary).toEqual(summary)
  })
  it('reload uses latest server weather without snapshotting or regenerating, and tolerates missing weather', async () => {
    const d = structuredClone(detail)
    d.days[0].items[0].weather = [{ forecast_date: d.start_date, precipitation_probability: 30, daily_evidence: {
      source_code: 'KMA_SHORT', region_code: 'EAST', spatial_scope: 'REGION', granularity: 'DAILY',
      issued_at_utc: '2026-09-07T00:00:00', temp_min: 20, temp_max: 27 } }]
    const port = memory(); rememberResult(resultFromDetail(d), condition, port)
    expect(port.getItem(RESTORE_KEY)).not.toMatch(/weather|daily_evidence/)
    vi.mocked(apiRequest).mockResolvedValue(d)
    const restored = await fetchRestoredCourse(state, false)
    expect(restored.days[0].items[0].weather).toEqual(d.days[0].items[0].weather)
    d.days[0].items[0].weather = []
    vi.mocked(apiRequest).mockResolvedValue(d)
    const missing = await fetchRestoredCourse(state, false)
    expect(missing.days[0].items[0].weather).toEqual([])
    expect(missing.accommodation).toEqual(restored.accommodation)
    expect(missing.days[0].items[0].start_time).toEqual(restored.days[0].items[0].start_time)
    expect(apiRequest).toHaveBeenNthCalledWith(2, '/courses/29', { method: 'GET', auth: false })
  })
  it('remembers only id, inputs and same-tab course proof, never itinerary or member auth', () => {
    const port = memory()
    const course = { ...resultFromDetail(detail), claim_token: 'not-stored', claim_expires_at: '2099-01-01T00:00:00Z' }
    expect(rememberResult(course, condition, port)).toBe(true)
    const expected = { ...state, ownerId: null, claim_token: course.claim_token, claim_expires_at: course.claim_expires_at }
    expect(JSON.parse(port.getItem(RESTORE_KEY)!)).toEqual(expected)
    expect(port.getItem(RESTORE_KEY)).not.toMatch(/access_token|refresh_token|days|car_route|costs/)
    expect(readRestore(port)).toEqual(expected)
  })
  it('reload uses GET once, no POST, and authoritative swapped places and accommodation', async () => {
    const port = memory(); rememberResult(resultFromDetail(detail), condition, port)
    vi.mocked(apiRequest).mockResolvedValue(detail)
    const course = await fetchRestoredCourse(readRestore(port)!, false)
    expect(apiRequest).toHaveBeenCalledTimes(1)
    expect(apiRequest).toHaveBeenCalledWith('/courses/29', { method: 'GET', auth: false })
    expect(course.days[1].items[0].place_name).toBe('서버에서 교체한 장소')
    expect(course.accommodation).toEqual(detail.accommodation)
    expect(course.days.map(x => [x.day_no, x.visit_date, x.items[0].start_time])).toEqual([[1, '2026-09-07', '10:00:00'], [2, '2026-09-08', '10:00:00'], [3, '2026-09-09', '10:00:00']])
    expect(course.days[0].items[0].recommendation_reason).toBe('서버에 저장된 추천 이유')
    expect(course.days[0].items[0].costs).toEqual([])
    expect(course.days[0].items[0]).not.toHaveProperty('candidate_id')
    expect(course.days[0].items[0]).not.toHaveProperty('weather_condition')
    expect(course).not.toHaveProperty('generation_reason')
    expect(course).not.toHaveProperty('claim_token')
  })
  it('preserves null accommodation and missing reason without fabrication', () => {
    const d = structuredClone(detail); d.accommodation = null; delete d.days[0].items[0].recommendation_reason
    const c = resultFromDetail(d)
    expect(c.accommodation).toBeNull(); expect(c.days[0].items[0].recommendation_reason).toBeUndefined()
    const restoredCondition = { ...condition, accommodation: detail.accommodation! }
    syncConfirmedCourseCondition(restoredCondition, c)
    expect(restoredCondition.accommodation).toBeUndefined()
  })
  it('reuses authenticated GET contract, not a new public permission', async () => {
    vi.mocked(apiRequest).mockResolvedValue({ ...detail, status: 'SAVED' })
    await fetchRestoredCourse(state, true)
    expect(apiRequest).toHaveBeenCalledWith('/courses/29', { method: 'GET', auth: true })
  })
  it.each(['null', '{}', '{broken', JSON.stringify({ ...state, courseId: -1 }), JSON.stringify({ ...state, courseId: '29' }), JSON.stringify({ ...state, condition: { ...condition, course_styles: [null] } })])('rejects malformed state %s', raw => {
    const port = memory(); port.setItem(RESTORE_KEY, raw); expect(readRestore(port)).toBeNull(); expect(port.getItem(RESTORE_KEY)).toBeNull()
  })
  it('storage unavailable never breaks generation', () => {
    const port = { getItem: () => { throw Error() }, setItem: () => { throw Error() }, removeItem: () => { throw Error() } }
    expect(rememberResult(resultFromDetail(detail), condition, port)).toBe(false); expect(readRestore(port)).toBeNull()
  })
  it('editing/new course persists inputs but will not reopen old course', () => {
    const port = memory(); rememberResult(resultFromDetail(detail), condition, port)
    rememberEditing({ ...condition, people: 4 }, port)
    expect(readRestore(port)).toEqual({ ownerId: null, mode: 'editing', condition: { ...condition, people: 4 } })
    clearRestore(port); expect(readRestore(port)).toBeNull()
  })
  it('deduplicates concurrent detail requests and displays loading', async () => {
    let resolve!: (v: CourseResult) => void
    const load = vi.fn(() => new Promise<CourseResult>(r => { resolve = r }))
    const flow = useResultRestore(load); const a = flow.restore(state, false); const b = flow.restore(state, false)
    expect(flow.restoring.value).toBe(true); expect(load).toHaveBeenCalledTimes(1)
    resolve(resultFromDetail(detail)); expect(await a).toEqual(await b); expect(flow.restoring.value).toBe(false)
  })
  it('late response cannot overwrite condition edit/new view', async () => {
    let resolve!: (v: CourseResult) => void
    const flow = useResultRestore(() => new Promise(r => { resolve = r }))
    const old = flow.restore(state, false); flow.cancel(); resolve(resultFromDetail(detail))
    expect(await old).toBeUndefined(); expect(flow.restoring.value).toBe(false)
  })
  it.each([401, 403, 404, 410])('terminal HTTP %i gives safe message without automatic regeneration', async status => {
    const load = vi.fn().mockRejectedValue(new ApiError(status, 'error', 'private details'))
    const flow = useResultRestore(load); expect(await flow.restore(state, false)).toBeUndefined()
    expect(flow.terminal.value).toBe(true); expect(flow.restoreError.value).not.toContain('private details'); expect(load).toHaveBeenCalledTimes(1)
  })
  it('transient error offers manual retry; no automatic retry', async () => {
    const load = vi.fn().mockRejectedValueOnce(new ApiError(0, 'NETWORK', 'offline')).mockResolvedValueOnce(resultFromDetail(detail))
    const flow = useResultRestore(load); await flow.restore(state, false)
    expect(flow.terminal.value).toBe(false); expect(load).toHaveBeenCalledTimes(1)
    expect(await flow.restore(state, false)).toHaveProperty('id', 29)
  })
  it.each([3301, 3304, 3307])('honors existing HTTP 400 domain code %i', async code => {
    const flow = useResultRestore(vi.fn().mockRejectedValue(new ApiError(400, code, 'private detail')))
    await flow.restore(state, false)
    expect(flow.terminal.value).toBe(true)
    expect(flow.restoreError.value).toBe(code === 3301 ? '삭제되었거나 찾을 수 없는 코스예요.' : '코스가 만료되었거나 조회 권한이 없어요.')
  })
  it('shows the explicit two-hour expiry response without exposing server detail', async () => {
    const flow = useResultRestore(vi.fn().mockRejectedValue(new ApiError(410, 3309, 'private detail')))
    await flow.restore(state, false)
    expect(flow.terminal.value).toBe(true)
    expect(flow.restoreError.value).toBe('저장하지 않은 코스의 2시간 보관 기간이 만료되었어요.')
  })
  it.each(['EXPIRED', 'DELETED', 'FAILED', 'GENERATING'])('unavailable server status %s is not shown as ready', async status => {
    vi.mocked(apiRequest).mockResolvedValue({ ...detail, status }); await expect(fetchRestoredCourse(state, false)).rejects.toHaveProperty('status', 410)
  })
  it('expired/missing proof is never a modification credential', () => {
    expect(validProof({})).toBe(false)
    expect(validProof({ claim_token: 'memory-only', claim_expires_at: '2020-01-01T00:00:00Z' })).toBe(false)
  })
  it('rejects wrong response identity', () => { expect(() => resultFromDetail(detail, { ...state, courseId: 30 })).toThrow() })
  it('deduplicates same-course follow-up route reads, not changed itineraries', async () => {
    const load = vi.fn().mockResolvedValue({ days: [] }); const route = singleFlight(load)
    const a = route('course-29-v1', 29); const b = route('course-29-v1', 29)
    await Promise.all([a, b]); expect(load).toHaveBeenCalledTimes(1)
    await route('course-29-v2', 29); expect(load).toHaveBeenCalledTimes(2)
  })
  it('route failure leaves restored server itinerary intact and does not cache the error', async () => {
    const flow = useResultRestore(async () => resultFromDetail(detail))
    const restored = await flow.restore(state, false); const before = JSON.stringify(restored)
    const load = vi.fn().mockRejectedValue(new Error('route unavailable')); const route = singleFlight(load)
    await expect(route('29', restored!)).rejects.toThrow()
    expect(JSON.stringify(restored)).toBe(before); expect(flow.restoreError.value).toBe('')
    await expect(route('29', restored!)).rejects.toThrow(); expect(load).toHaveBeenCalledTimes(2)
  })
})

describe('course proof renewal', () => {
  const proof = { claim_token: 'course-proof-only', claim_expires_at: '2099-01-01T00:00:00Z' }
  it('calls dedicated renewal once and uses new proof for accommodation, never generation/auth', async () => {
    const next = { ...proof, claim_token: 'renewed-course-proof' }
    vi.mocked(apiRequest).mockResolvedValueOnce(next).mockResolvedValueOnce(detail.accommodation)
    const flow = useClaimRenewal()
    const [a, b] = await Promise.all([flow.renew(29, proof), flow.renew(29, proof)])
    expect(a).toEqual(b)
    expect(apiRequest).toHaveBeenCalledTimes(1)
    expect(apiRequest).toHaveBeenCalledWith('/courses/29/claim/renew', { method: 'POST', auth: false, optionalAuth: true, body: { claim_token: proof.claim_token } })
    await courseMockService.updateAccommodation({ ...resultFromDetail(detail), ...a }, detail.accommodation!)
    expect(vi.mocked(apiRequest).mock.calls[1][0]).toBe('/courses/29/accommodation')
    expect(vi.mocked(apiRequest).mock.calls[1][1]?.body).toMatchObject({ claim_token: next.claim_token })
    expect(vi.mocked(apiRequest).mock.calls.some(([path]) => path === '/courses' || path === '/auth/reissue')).toBe(false)
  })
  it('does not grant a proof to old records or expired records', async () => {
    const send = vi.fn(); const flow = useClaimRenewal(send)
    expect(await flow.renew(29, {})).toEqual({})
    expect(await flow.renew(29, { ...proof, claim_expires_at: '2000-01-01T00:00:00Z' })).toEqual({})
    expect(send).not.toHaveBeenCalled()
  })
  it('invalid proof is removed while course remains available', async () => {
    const flow = useClaimRenewal(vi.fn().mockRejectedValue(new ApiError(400, 3304, 'private detail')))
    expect(await flow.renew(29, proof)).toEqual({})
    expect(flow.notice.value).not.toContain('private detail')
  })
  it('network failure retains the exact previous expiry and does not retry', async () => {
    const send = vi.fn().mockRejectedValue(new ApiError(0, 'NETWORK', 'offline'))
    const flow = useClaimRenewal(send)
    expect(await flow.renew(29, proof)).toEqual(proof)
    await flow.renew(29, proof); expect(send).toHaveBeenCalledTimes(1)
  })
  it('late renewal is discarded after changing course/view', async () => {
    let resolve!: (value: typeof proof) => void
    const flow = useClaimRenewal(() => new Promise(r => { resolve = r }))
    const request = flow.renew(29, proof); await Promise.resolve()
    flow.cancel(); resolve(proof)
    expect(await request).toBeUndefined(); expect(flow.renewing.value).toBe(false)
  })
  it('saved/new-course state removes the course proof from storage', () => {
    const port = memory(); const course = { ...resultFromDetail(detail), ...proof }
    rememberResult(course, condition, port); expect(readRestore(port)?.claim_token).toBe(proof.claim_token)
    rememberResult({ ...course, status: 'SAVED' }, condition, port); expect(readRestore(port)?.claim_token).toBeUndefined()
    rememberResult(course, condition, port); rememberEditing(condition, port)
    expect(readRestore(port)?.claim_token).toBeUndefined()
    clearCourseProof(course); expect(course.claim_token).toBeUndefined()
  })
  it('malformed or expired stored proof is stripped without discarding valid course id', () => {
    const port = memory(); port.setItem(RESTORE_KEY, JSON.stringify({ ...state, ...proof, claim_expires_at: 'bad' }))
    expect(readRestore(port)).toEqual({ ...state, ownerId: null })
  })
})
