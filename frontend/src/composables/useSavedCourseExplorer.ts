import { ref, shallowRef } from 'vue'
import type { CourseDetail } from '../services/CourseService'

/** 선택 요청의 순서를 보존한다. 이전 코스 응답은 현재 화면을 덮어쓸 수 없다. */
export function useSavedCourseExplorer(fetchDetail: (id: string) => Promise<CourseDetail | null>) {
  const selectedId = ref('')
  const course = shallowRef<CourseDetail | null>(null)
  const expanded = ref(true)
  const loading = ref(false)
  const failed = ref(false)
  let sequence = 0

  function reset() {
    sequence++
    selectedId.value = ''
    course.value = null
    loading.value = false
    failed.value = false
  }

  async function select(id: string) {
    if (selectedId.value === id && (course.value || loading.value)) {
      expanded.value = !expanded.value
      return
    }
    const request = ++sequence
    selectedId.value = id
    course.value = null
    expanded.value = true
    loading.value = true
    failed.value = false
    try {
      const result = await fetchDetail(id)
      if (request !== sequence) return
      course.value = result
      failed.value = !result
    } catch {
      if (request === sequence) failed.value = true
    } finally {
      if (request === sequence) loading.value = false
    }
  }
  return { selectedId, course, expanded, loading, failed, select, reset }
}

export function sheetHeight(start: number, deltaY: number, containerHeight: number) {
  if (containerHeight <= 0) return start
  return Math.min(90, Math.max(18, start - deltaY / containerHeight * 100))
}
