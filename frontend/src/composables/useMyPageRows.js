import { onBeforeUnmount, ref, watch } from 'vue'
import { useAuthStore } from '../stores/auth.js'
import { getBackendSessionVersion } from '../api/backendClient.js'
import { canHandleActivityError } from '../api/myActivity.js'

/** 내 목록 페이지 조회. 계정/정렬 변경 시 이전 응답을 버린다. */
export function useMyPageRows(fetcher, sort, size = 10, toMessage = () => '목록을 불러오지 못했어요.') {
  const auth = useAuthStore()
  const items = ref([])
  const total = ref(0)
  const number = ref(0)
  const totalPages = ref(0)
  const loading = ref(false)
  const error = ref('')
  let requestVersion = 0
  let requestedPage = 0

  async function load(page = number.value) {
    requestedPage = page
    const version = ++requestVersion
    const epoch = getBackendSessionVersion()
    const userId = auth.user?.userId
    const isCurrent = () => version === requestVersion && epoch === getBackendSessionVersion()
    error.value = ''
    if (!auth.user) { loading.value = false; return }
    loading.value = true
    try {
      const result = await fetcher({ page, size, sort: sort.value })
      if (!isCurrent()) return
      // 마지막 페이지의 마지막 리뷰 삭제 또는 다른 기기에서 삭제된 경우.
      if (page > 0 && page >= result.totalPages) return await load(Math.max(0, result.totalPages - 1))
      items.value = result.content
      total.value = result.totalElements
      number.value = result.number
      totalPages.value = result.totalPages
    } catch (e) {
      if (version === requestVersion && canHandleActivityError(e, epoch, userId, auth.user?.userId)) {
        error.value = toMessage(e) || ''
      }
    } finally {
      if (version === requestVersion) loading.value = false
    }
  }

  watch(() => [auth.user?.userId, sort.value], () => {
    items.value = []; total.value = 0; number.value = 0; totalPages.value = 0
    load(0)
  }, { immediate: true, flush: 'sync' })
  onBeforeUnmount(() => { requestVersion += 1 })
  return { items, total, number, totalPages, loading, error, load, retry: () => load(requestedPage) }
}
