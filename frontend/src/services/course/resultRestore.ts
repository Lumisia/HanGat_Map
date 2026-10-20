import { ref } from 'vue'
import { apiRequest, getBackendUserId } from '../../api/backendClient.js'
import { ASYNC_COURSES_ENABLED } from '../../api/notifications.js'
import { ApiError } from '../../api/errors.js'
import { todayKst } from '../../utils/format.js'
import type { CourseCondition, CourseResult, CourseItem } from '../../assets/types/course'

export const RESTORE_KEY = 'hangat.ai-course.restore.v1'
export interface RestoreState {
  ownerId?: number | null
  mode: 'result' | 'editing'
  courseId?: number
  condition: CourseCondition
  claim_token?: string
  claim_expires_at?: string
}
type StoragePort = Pick<Storage, 'getItem' | 'setItem' | 'removeItem'>
const storage = (): StoragePort | undefined => {
  try { return typeof sessionStorage === 'undefined' ? undefined : sessionStorage } catch { return undefined }
}
const validId = (id: unknown): id is number => Number.isSafeInteger(id) && Number(id) > 0
function inputOnly(c: CourseCondition): CourseCondition {
  // Explicit input allowlist: claim proof is handled separately, never from arbitrary input fields.
  return {
    start_date: c.start_date, end_date: c.end_date, people: c.people, transport: c.transport,
    course_regions: c.course_regions.map(x => ({ region_id: x.region_id, code: x.code, name: x.name })),
    course_styles: c.course_styles.map(x => ({ tag_id: x.tag_id, code: x.code, name: x.name, weight: x.weight })),
    course_place_preferences: c.course_place_preferences.map(x => ({ place_id: x.place_id, source_code: x.source_code,
      source_place_id: x.source_place_id, place_name: x.place_name, address: x.address, road_address: x.road_address,
      latitude: x.latitude, longitude: x.longitude, category_name: x.category_name,
      preference_type: x.preference_type, fixed_date: x.fixed_date, fixed_time: x.fixed_time })),
    ...(c.accommodation ? { accommodation: { source_code: c.accommodation.source_code, source_place_id: c.accommodation.source_place_id,
      place_name: c.accommodation.place_name, address: c.accommodation.address, road_address: c.accommodation.road_address,
      latitude: c.accommodation.latitude, longitude: c.accommodation.longitude, region: c.accommodation.region } } : {}),
  }
}
export function validProof(value: Pick<CourseResult, 'claim_token' | 'claim_expires_at'>, now = Date.now()) {
  return typeof value.claim_token === 'string' && value.claim_token.length > 0 && value.claim_token.length <= 4096
    && typeof value.claim_expires_at === 'string' && Date.parse(value.claim_expires_at) > now
}
export function readRestore(port = storage()): RestoreState | null {
  try {
    const raw = port?.getItem(RESTORE_KEY)
    if (!raw) return null
    if (raw.length > 50000) throw new Error('Invalid restore state')
    const value = JSON.parse(raw) as RestoreState
    const ownerId = getBackendUserId()
    // Legacy member records have no owner marker: recheck only their ID with
    // the server, without showing potentially another member's inputs/proof.
    const legacyMember = value && !Object.prototype.hasOwnProperty.call(value, 'ownerId') && ownerId != null
    if ((!legacyMember && (value.ownerId ?? null) !== (ownerId ?? null))
      || (legacyMember && value.mode !== 'result')) { clearRestore(port); return null }
    const c = value?.condition
    if (!['result', 'editing'].includes(value?.mode) || (value.mode === 'result' && !validId(value.courseId))
      || !c || !/^\d{4}-\d{2}-\d{2}$/.test(c.start_date) || !/^\d{4}-\d{2}-\d{2}$/.test(c.end_date)
      || !Number.isInteger(c.people) || c.people < 1
      || !['RENTAL_CAR', 'PUBLIC_TRANSIT', 'TAXI', 'WALK_BIKE'].includes(c.transport)
      || !Array.isArray(c.course_regions) || !Array.isArray(c.course_styles) || !Array.isArray(c.course_place_preferences)
      || [...c.course_regions, ...c.course_styles].some(x => !x || typeof x.code !== 'string')
      || c.course_place_preferences.some(x => !x || typeof x.place_name !== 'string')) throw new Error('Invalid restore state')
    const safe: RestoreState = { ownerId: ownerId ?? null, mode: value.mode, courseId: value.mode === 'result' ? value.courseId : undefined,
      condition: legacyMember ? { start_date: todayKst(), end_date: todayKst(), people: 2, transport: 'RENTAL_CAR', course_regions: [], course_styles: [], course_place_preferences: [] } : inputOnly(c),
      ...(!legacyMember && value.mode === 'result' && validProof(value) ? { claim_token: value.claim_token, claim_expires_at: value.claim_expires_at } : {}) }
    port?.setItem(RESTORE_KEY, JSON.stringify(safe)) // same-tab course proof only; no user access/refresh credential
    return safe
  } catch { clearRestore(port); return null }
}
export function clearRestore(port = storage()) { try { port?.removeItem(RESTORE_KEY) } catch { /* storage can be disabled */ } }
export function rememberResult(course: CourseResult, condition: CourseCondition, port = storage()): boolean {
  if (!validId(course.id)) return false
  try {
    const value: RestoreState = { ownerId: getBackendUserId() ?? null, mode: 'result', courseId: course.id, condition: inputOnly(condition),
      ...(course.status === 'READY' && validProof(course) ? { claim_token: course.claim_token, claim_expires_at: course.claim_expires_at } : {}) }
    port?.setItem(RESTORE_KEY, JSON.stringify(value)); return !!port
  } catch { return false }
}

type Proof = Pick<CourseResult, 'claim_token' | 'claim_expires_at'>
export function clearCourseProof(course: Proof) { delete course.claim_token; delete course.claim_expires_at }

/** One renewal per restored course per view, never a timer or member-auth refresh. */
export function useClaimRenewal(send = (id: number, token: string) => apiRequest(`/courses/${id}/claim/renew`, {
  method: 'POST', auth: false, ...(ASYNC_COURSES_ENABLED ? { optionalAuth: true } : {}), body: { claim_token: token },
}) as Promise<Proof>) {
  const notice = ref('')
  const renewing = ref(false)
  let epoch = 0
  const attempts = new Map<number, Promise<Proof | undefined>>()
  function cancel() { epoch++; attempts.clear(); renewing.value = false; notice.value = '' }
  function renew(courseId: number, proof: Proof): Promise<Proof | undefined> {
    if (attempts.has(courseId)) return attempts.get(courseId)!
    if (!validId(courseId) || !validProof(proof)) return Promise.resolve({})
    const ticket = epoch
    const original = { claim_token: proof.claim_token, claim_expires_at: proof.claim_expires_at }
    renewing.value = true
    const task = Promise.resolve().then(() => send(courseId, original.claim_token!)).then(next => {
      if (ticket !== epoch) return undefined
      if (!validProof(next)) throw new Error('Invalid renewal response')
      notice.value = ''
      return { claim_token: next.claim_token, claim_expires_at: next.claim_expires_at }
    }).catch(failure => {
      if (ticket !== epoch) return undefined
      const invalid = failure instanceof ApiError && ([401, 403, 404, 410].includes(failure.status)
        || [3301, 3303, 3304, 3305, 3307].includes(Number(failure.code)))
      if (invalid || !validProof(original)) {
        notice.value = '코스 저장 증명이 만료되었거나 유효하지 않아 숙소 변경을 사용할 수 없어요. 일정 조회는 유지됩니다.'
        return {}
      }
      notice.value = '코스 저장 증명을 갱신하지 못했어요. 기존 만료 시각까지 숙소 변경을 사용할 수 있어요.'
      return original // never extend on network/protocol failure
    }).finally(() => { if (ticket === epoch) renewing.value = false })
    attempts.set(courseId, task)
    return task
  }
  return { renew, cancel, notice, renewing }
}
export function rememberEditing(condition: CourseCondition, port = storage()) {
  try { port?.setItem(RESTORE_KEY, JSON.stringify({ ownerId: getBackendUserId() ?? null, mode: 'editing', condition: inputOnly(condition) })) } catch { clearRestore(port) }
}

/** Restore only authoritative detail data; do not fabricate generation facts. */
export interface CourseDetail extends Omit<CourseResult, 'generation_reason' | 'days'> {
  swappable: boolean
  manageable: boolean
  days: Array<{ day_no: number; visit_date: string; items: Array<Omit<CourseItem, 'course_id' | 'costs'> & { costs?: CourseItem['costs'] }> }>
}
export function resultFromDetail(detail: CourseDetail, proof?: RestoreState): CourseResult {
  if (!validId(detail.id) || !Array.isArray(detail.days) || (proof?.courseId != null && proof.courseId !== detail.id)) throw new Error('Invalid course response')
  return {
    id: detail.id, course_type: detail.course_type, status: detail.status, title: detail.title,
    start_date: detail.start_date, end_date: detail.end_date, people: detail.people,
    transport: detail.transport, estimated_cost_min: detail.estimated_cost_min, estimated_cost_max: detail.estimated_cost_max,
    budget_summary: detail.budget_summary,
    average_congestion_rate: detail.average_congestion_rate, accommodation: detail.accommodation,
    swappable: detail.swappable, manageable: detail.manageable,
    days: detail.days.map(day => ({ day_no: day.day_no, visit_date: day.visit_date, items: day.items.map(item => ({
      id: item.id, course_id: detail.id, place_id: item.place_id, place_name: item.place_name,
      category_name: item.category_name, image_url: item.image_url, latitude: item.latitude, longitude: item.longitude,
      day_no: item.day_no, position: item.position, visit_date: item.visit_date, start_time: item.start_time, end_time: item.end_time,
      item_source: item.item_source, inbound_distance_m: item.inbound_distance_m, inbound_travel_minutes: item.inbound_travel_minutes,
      congestion_rate: item.congestion_rate, congestion_level: item.congestion_level,
      recommendation_reason: item.recommendation_reason, recommendation_reason_code: item.recommendation_reason_code,
      replaced_from_place_id: item.replaced_from_place_id, weather: item.weather, costs: item.costs ?? [],
      indoor: item.indoor,
    })) })),
  }
}
export async function fetchRestoredCourse(state: RestoreState, authenticated: boolean) {
  if (!validId(state.courseId)) throw new Error('Invalid course id')
  const detail = await apiRequest(`/courses/${state.courseId}`, { method: 'GET', auth: authenticated }) as CourseDetail
  if (['DELETED', 'EXPIRED', 'FAILED', 'GENERATING'].includes(detail.status)) throw new ApiError(410, 'COURSE_UNAVAILABLE', '이 코스는 더 이상 표시할 수 없어요.')
  return resultFromDetail(detail, state)
}

/** One view instance owns its request; navigation invalidates every outstanding completion. */
export function useResultRestore(load = fetchRestoredCourse) {
  const restoring = ref(false)
  const restoreError = ref('')
  const terminal = ref(false)
  let epoch = 0
  let pending: Promise<CourseResult | undefined> | undefined
  function cancel() { epoch++; pending = undefined; restoring.value = false; restoreError.value = ''; terminal.value = false }
  function restore(state: RestoreState, authenticated: boolean) {
    if (pending) return pending
    const ticket = ++epoch
    restoring.value = true; restoreError.value = ''; terminal.value = false
    pending = load(state, authenticated).then(value => ticket === epoch ? value : undefined).catch(failure => {
      if (ticket !== epoch) return undefined
      // BaseException may use HTTP 400 with the existing domain response code.
      const missing = failure instanceof ApiError && (failure.status === 404 || Number(failure.code) === 3301)
      const expired = failure instanceof ApiError && (failure.status === 410 || Number(failure.code) === 3309)
      terminal.value = failure instanceof ApiError && ([401, 403, 404, 410].includes(failure.status)
        || [3001, 3002, 3301, 3303, 3304, 3305, 3307, 3309].includes(Number(failure.code)))
      restoreError.value = terminal.value
        ? (missing ? '삭제되었거나 찾을 수 없는 코스예요.' : expired
          ? '저장하지 않은 코스의 2시간 보관 기간이 만료되었어요.'
          : '코스가 만료되었거나 조회 권한이 없어요.')
        : '코스를 불러오지 못했어요. 잠시 후 다시 불러와 주세요.'
      return undefined
    }).finally(() => { if (ticket === epoch) { restoring.value = false; pending = undefined } })
    return pending
  }
  return { restoring, restoreError, terminal, restore, cancel }
}

/** Dedupe identical follow-up reads only while pending; failures never become cached success. */
export function singleFlight<A, T>(load: (arg: A) => Promise<T>) {
  const pending = new Map<string, Promise<T>>()
  return (key: string, arg: A): Promise<T> => {
    const existing = pending.get(key)
    if (existing) return existing
    const request = Promise.resolve().then(() => load(arg)).finally(() => pending.delete(key))
    pending.set(key, request)
    return request
  }
}
