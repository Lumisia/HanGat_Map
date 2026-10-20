import { ref } from 'vue'
import { listGenerationJobs } from '../../api/courseGeneration.js'

/** A closed dialog or a different account must never receive an earlier page. */
export function useGenerationHistory(fetchPage = listGenerationJobs) {
  const open = ref(false), items = ref([]), page = ref(0), hasNext = ref(false)
  const busy = ref(false), error = ref('')
  let epoch = 0
  function close() {
    epoch++; open.value = false; items.value = []; page.value = 0
    hasNext.value = false; busy.value = false; error.value = ''
  }
  async function load(target = page.value) {
    if (!open.value || !Number.isInteger(target) || target < 0 || target > 10000) return
    const ticket = ++epoch
    page.value = target; busy.value = true; error.value = ''; items.value = []; hasNext.value = false
    try {
      const response = await fetchPage(target, 5)
      if (ticket !== epoch || !open.value) return
      items.value = response.items; hasNext.value = response.hasNext === true
    } catch {
      if (ticket === epoch) error.value = '최근 생성 요청을 불러오지 못했어요.'
    } finally { if (ticket === epoch) busy.value = false }
  }
  function show() { if (open.value) return; open.value = true; return load(0) }
  return { open, items, page, hasNext, busy, error, close, load, show }
}
