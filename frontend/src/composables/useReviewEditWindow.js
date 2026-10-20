import { ref, onMounted, onBeforeUnmount } from 'vue'

/** 서버가 전달한 최초 작성 기준 기한만 사용한다. 수정 시각으로 기한을 늘리지 않는다. */
export function isReviewEditable(review, now = Date.now()) {
  const deadline = Date.parse(review?.editableUntil)
  return Number.isFinite(deadline) && now < deadline
}

/** 페이지를 계속 열어 두거나 다른 탭에서 돌아와도 만료한 수정 버튼은 사라진다. */
export function useReviewEditWindow() {
  const now = ref(Date.now())
  const refresh = () => { now.value = Date.now() }
  let timer
  onMounted(() => {
    refresh()
    timer = setInterval(refresh, 1000)
    document.addEventListener('visibilitychange', refresh)
  })
  onBeforeUnmount(() => {
    clearInterval(timer)
    document.removeEventListener('visibilitychange', refresh)
  })
  return { canEdit: review => isReviewEditable(review, now.value) }
}
