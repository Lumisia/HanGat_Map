import { computed, ref, type Ref } from 'vue'
import CourseService, { type CourseDetail, type CourseDetailItem } from '../services/CourseService'
import type { AlternativePlace, CourseItem } from '../assets/types/course'
import { ApiError } from '../api/errors.js'
import { todayKst } from '../utils/format.js'

/** One complete straight-distance-ranked snapshot per modal; scrolling only reveals its next three items. */
export function useSavedCourseActions(course: Ref<CourseDetail | null>, api: Pick<typeof CourseService, 'getStraightAlternatives' | 'swapItem' | 'getCourseDetail'> = CourseService) {
  const target = ref<{ item: CourseDetailItem; dayNo: number; visitDate: string } | null>(null)
  const alternatives = ref<AlternativePlace[]>([])
  const loading = ref(false), busy = ref(false), notice = ref(''), message = ref('')
  const hasMore = ref(false), loadFailed = ref(false), forecastDate = ref('')
  let epoch = 0, remaining: AlternativePlace[] = []
  function reset() {
    epoch++; target.value = null; alternatives.value = []; remaining = []; loading.value = false
    busy.value = false; notice.value = ''; message.value = ''; hasMore.value = false
    loadFailed.value = false; forecastDate.value = ''
  }
  const modalItem = computed<CourseItem | null>(() => {
    if (!target.value || !course.value) return null
    const { item, dayNo, visitDate } = target.value
    return { id: item.id, course_id: Number(course.value.id), place_id: item.placeId, place_name: item.placeName,
      category_name: item.categoryName, image_url: item.imageUrl ?? undefined, day_no: dayNo, position: item.position,
      visit_date: visitDate, item_source: 'AI_RECOMMENDED', congestion_level: item.congestionLevel ?? undefined,
      congestion_rate: item.congestionRate ?? undefined, costs: [] }
  })
  function revealMore() {
    alternatives.value.push(...remaining.splice(0, 3))
    hasMore.value = remaining.length > 0
  }
  async function fetchCandidates() {
    if (!target.value || !course.value || busy.value || loading.value) return
    const id = course.value.id, request = epoch, item = target.value.item
    const exclude = [...new Set(course.value.days.flatMap(d => d.items).filter(i => i.id !== item.id).map(i => i.placeId))]
    loading.value = true; loadFailed.value = false; notice.value = ''
    try {
      const result = await api.getStraightAlternatives(item.placeId, exclude)
      if (request !== epoch || course.value?.id !== id) return
      if (result.distance_basis !== 'STRAIGHT_LINE' || !Array.isArray(result.places)) throw new Error('Invalid straight-distance response')
      forecastDate.value = result.forecast_date
      const seen = new Set([...exclude, item.placeId])
      remaining = result.places.filter(p => {
        if (seen.has(p.place_id)) return false
        seen.add(p.place_id); return true
      })
      alternatives.value = []; revealMore()
    } catch (error) {
      if (request === epoch && course.value?.id === id) {
        loadFailed.value = true; hasMore.value = false
        notice.value = error instanceof ApiError && Number(error.code) === 3401
          ? '오늘의 혼잡 예보가 없어 대안을 추천할 수 없어요.'
          : '직선거리 대안을 확인하지 못했어요. 잠시 후 다시 시도해 주세요.'
      }
    } finally { if (request === epoch) loading.value = false }
  }
  async function openSwap(day: CourseDetail['days'][number], item: CourseDetailItem) {
    if (!course.value?.swappable || busy.value) return
    reset(); target.value = { item, dayNo: day.dayNo, visitDate: day.visitDate }
    forecastDate.value = todayKst()
    await fetchCandidates()
  }
  async function loadMore(retry = false) {
    if (!target.value || loading.value || busy.value) return
    if (retry && loadFailed.value) { await fetchCandidates(); return }
    if (loadFailed.value || !hasMore.value) return
    revealMore()
  }
  async function applySwap(alternative: AlternativePlace) {
    if (!target.value || !course.value?.swappable || busy.value || loading.value) return
    const id = course.value.id, request = epoch, itemId = target.value.item.id, owned = course.value.manageable
    busy.value = true; notice.value = ''
    try {
      await api.swapItem(id, itemId, alternative.place_id, owned)
      const refreshed = await api.getCourseDetail(id)
      if (request !== epoch || course.value?.id !== id) return
      target.value = null
      if (refreshed) { course.value = refreshed; message.value = `${alternative.place_name}(으)로 바꿨어요.` }
      else message.value = '장소는 교체됐지만 최신 일정을 불러오지 못했어요. 새로고침해 주세요.'
    } catch (error) {
      if (request === epoch && course.value?.id === id) notice.value = error instanceof ApiError
        ? `바꾸지 못했어요. ${error.message}` : '장소를 바꾸지 못했어요. 다시 시도해 주세요.'
    } finally { if (request === epoch) busy.value = false }
  }
  return { target, modalItem, alternatives, loading, busy, notice, message, hasMore, loadFailed, forecastDate, reset, openSwap, loadMore, applySwap }
}
