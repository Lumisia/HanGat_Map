import { onScopeDispose, ref } from 'vue'
import { getBackendSessionVersion } from '../api/backendClient.js'
import { NOTIFICATIONS_ENABLED, listNotificationPage } from '../api/notifications.js'

/** 헤더 목록과 독립적인 7개 페이지. 계정·필터 변경 전 응답은 반영하지 않는다. */
export function useNotificationInbox () {
  const items = ref([])
  const number = ref(0)
  const totalPages = ref(0)
  const totalElements = ref(0)
  const category = ref('ALL')
  const loading = ref(false)
  const error = ref('')
  let request = 0
  let requestedPage = 0
  let alive = true
  onScopeDispose(() => { alive = false; request++ })

  async function load (target = requestedPage) {
    if (!NOTIFICATIONS_ENABLED || !alive) return
    // 자동 갱신이 겹쳐도 응답 대기 중인 사용자의 페이지 선택을 유지한다.
    requestedPage = target
    const ticket = ++request
    const epoch = getBackendSessionVersion()
    loading.value = true
    error.value = ''
    try {
      const result = await listNotificationPage(target, category.value)
      if (!alive || ticket !== request || epoch !== getBackendSessionVersion()) return
      if (!Array.isArray(result?.items) || !Number.isInteger(result.totalPages)) throw new Error('알림 응답 형식을 확인해 주세요.')
      // 삭제 또는 다른 탭의 변경으로 현재 페이지가 없어졌으면 마지막 유효 페이지를 조회한다.
      if (target > 0 && target >= result.totalPages) return await load(Math.max(0, result.totalPages - 1))
      items.value = result.items
      number.value = result.number
      totalPages.value = result.totalPages
      totalElements.value = result.totalElements
    } catch (failure) {
      if (alive && ticket === request && epoch === getBackendSessionVersion()) error.value = failure.message || '알림을 불러오지 못했어요.'
    } finally {
      if (alive && ticket === request) loading.value = false
    }
  }
  function filter (value) {
    category.value = value
    items.value = []
    number.value = 0
    return load(0)
  }
  return { items, number, totalPages, totalElements, category, loading, error, load, filter }
}
