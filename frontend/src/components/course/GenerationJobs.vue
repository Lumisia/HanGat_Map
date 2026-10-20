<script setup>
import { onBeforeUnmount, onMounted, watch } from 'vue'
import { useAuthStore } from '../../stores/auth.js'
import { ASYNC_COURSES_ENABLED } from '../../api/notifications.js'
import { useGenerationHistory } from '../../services/course/generationHistory.js'
const emit = defineEmits(['selected'])
const auth = useAuthStore()
const history = useGenerationHistory()
const { items, page, hasNext, busy, error } = history
const labels = { QUEUED: '접수됨', RUNNING: '생성 중', SUCCEEDED: '생성 완료', FAILED: '생성 실패' }
const requestedAt = value => {
  const date = new Date(value)
  return value && Number.isFinite(date.getTime())
    ? new Intl.DateTimeFormat('ko-KR', { timeZone: 'Asia/Seoul', dateStyle: 'short', timeStyle: 'short' }).format(date)
    : '요청 시각 정보 없음'
}
// The existing parent dialog mounts this list only while it is open.
onMounted(() => { if (ASYNC_COURSES_ENABLED && auth.isLoggedIn) void history.show() })
watch(() => [auth.isLoggedIn, auth.user?.userId], () => history.close())
onBeforeUnmount(() => history.close())
</script>
<template>
  <section v-if="ASYNC_COURSES_ENABLED && auth.isLoggedIn" class="recent-jobs" aria-label="최근 AI 코스 생성 요청">
    <div class="jobs-heading"><h3>최근 코스 생성 요청</h3><button type="button" :disabled="busy" @click="history.load()">새로고침</button></div>
    <p v-if="error" role="alert">{{ error }} <button type="button" @click="history.load()">다시 시도</button></p>
    <p v-else-if="busy" role="status">요청을 확인하고 있어요.</p>
    <p v-else-if="!items.length">접수된 요청이 없어요.</p>
    <ul><li v-for="job in items" :key="job.jobId">
      <RouterLink :to="{ name: 'course-generation-job', params: { jobId: job.jobId } }" @click="emit('selected')">
        <span>{{ job.startDate }} ~ {{ job.endDate }}<small>요청 {{ requestedAt(job.createdAt) }}</small></span><strong>{{ labels[job.status] || '상태 확인' }}</strong>
      </RouterLink>
    </li></ul>
    <nav class="pages" aria-label="최근 요청 페이지">
      <button type="button" :disabled="busy || page === 0" @click="history.load(page - 1)">이전</button>
      <span aria-live="polite">{{ page + 1 }}페이지</span>
      <button type="button" :disabled="busy || !hasNext || page >= 10000" @click="history.load(page + 1)">다음</button>
    </nav>
  </section>
</template>
<style scoped>
.recent-jobs { margin: 20px 0; padding: 18px 0; border-block: 1px solid var(--line); }
.jobs-heading, a { display: flex; gap: 12px; align-items: center; justify-content: space-between; }
h3 { margin: 0; font-size: 15px; }
button, strong { color: var(--ac); font-size: 13px; }
ul { list-style: none; padding: 0; margin-bottom: 0; }
a { padding: 10px 0; font-size: 14px; flex-wrap: wrap; overflow-wrap: anywhere; }
small { display: block; margin-top: 4px; color: var(--tx2); }
.pages { display: flex; justify-content: space-between; align-items: center; gap: 12px; margin-top: 16px; font-size: 14px; }
.pages button { min-height: 44px; padding: 8px; }
button:disabled { opacity: .5; }
p { color: var(--tx3); font-size: 13px; }
</style>
