import { ref } from 'vue'
import type { AccommodationInput, CourseCondition, CourseResult } from '../../assets/types/course'
import { courseMockService } from '../courseMockService'
import { fetchRestoredCourse, validProof } from './resultRestore'

type PatchAccommodation = (course: CourseResult, selected: AccommodationInput) => Promise<AccommodationInput>
type ReadCourse = (state: { mode: 'result'; courseId: number; condition: CourseCondition }, authenticated: boolean) => Promise<CourseResult>

const sameAccommodation = (left: AccommodationInput | null | undefined, right: AccommodationInput) =>
  left?.source_code === right.source_code && left?.source_place_id === right.source_place_id

/** The detail GET, not the selected card or old form state, decides the visible accommodation and itinerary. */
export function useAccommodationSelection(
  patch: PatchAccommodation = courseMockService.updateAccommodation,
  read: ReadCourse = fetchRestoredCourse,
) {
  const saving = ref(false)
  const error = ref('')
  let epoch = 0

  function cancel() { epoch++; saving.value = false; error.value = '' }

  async function save(course: CourseResult, selected: AccommodationInput, condition: CourseCondition, authenticated: boolean) {
    if (saving.value) return undefined
    const ticket = ++epoch
    saving.value = true
    error.value = ''
    let patched = false
    try {
      const response = await patch(course, selected)
      patched = true
      const detail = await read({ mode: 'result', courseId: course.id, condition }, authenticated)
      if (ticket !== epoch) return undefined
      const confirmed: CourseResult = { ...course, ...detail, car_route: undefined }
      if (detail.status === 'READY' && validProof(course)) {
        confirmed.claim_token = course.claim_token
        confirmed.claim_expires_at = course.claim_expires_at
      } else {
        delete confirmed.claim_token
        delete confirmed.claim_expires_at
      }
      const selectedStored = sameAccommodation(response, selected) && sameAccommodation(detail.accommodation, selected)
      if (!selectedStored) error.value = '서버에 저장된 숙소가 선택한 숙소와 달라요. 서버의 최신 결과를 표시합니다.'
      return { course: confirmed, selectedStored }
    } catch {
      if (ticket === epoch) error.value = patched
        ? '숙소 저장 결과를 확인하지 못했어요. 다시 불러와 확인해 주세요.'
        : '숙소를 저장하지 못했어요. 기존 확정 숙소와 일정은 그대로 유지됩니다.'
      return undefined
    } finally {
      if (ticket === epoch) saving.value = false
    }
  }

  return { saving, error, save, cancel }
}

export function syncConfirmedCourseCondition(condition: CourseCondition, course: CourseResult) {
  Object.assign(condition, {
    start_date: course.start_date, end_date: course.end_date, people: course.people,
    transport: course.transport,
    accommodation: course.accommodation ?? undefined,
  })
}
