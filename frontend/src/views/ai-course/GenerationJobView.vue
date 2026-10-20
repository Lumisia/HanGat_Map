<script setup>
/** 작업 조회 실패와 실제 생성 실패를 구분한다. 화면을 닫아도 서버 작업은 취소하지 않는다. */
import { computed, onBeforeUnmount, ref, watch } from 'vue'
import { useRoute } from 'vue-router'
import { getGenerationJob } from '../../api/courseGeneration.js'
import { ASYNC_COURSES_ENABLED } from '../../api/notifications.js'
import { useAuthStore } from '../../stores/auth.js'
import GenerationArtwork from '../../components/course/GenerationArtwork.vue'
const auth = useAuthStore()
const route = useRoute()
const job = ref(null)
const error = ref('')
const loading = ref(false)
let epoch = 0; let timer
const terminal = computed(() => ['SUCCEEDED', 'FAILED'].includes(job.value?.status))
const title = computed(() => ({ QUEUED: '코스 생성 요청이 접수됐어요', RUNNING: '제주 여행을 구성하고 있어요', SUCCEEDED: 'AI 코스가 완성됐어요', FAILED: 'AI 코스 생성에 실패했어요' }[job.value?.status] || '코스 생성 상태 확인'))
async function load () {
  clearTimeout(timer)
  if (!ASYNC_COURSES_ENABLED || !auth.isLoggedIn || document.hidden) return
  const ticket = ++epoch
  loading.value = true
  try {
    const id = String(route.params.jobId)
    if (!/^[a-f\d]{8}(-[a-f\d]{4}){3}-[a-f\d]{12}$/i.test(id)) throw new Error('올바른 작업 주소가 아니에요.')
    const result = await getGenerationJob(id)
    if (ticket !== epoch) return
    job.value = result; error.value = ''
  } catch (failure) {
    if (ticket === epoch) error.value = failure.status === 404 ? '이 작업이 없거나 조회할 권한이 없어요.' : '진행 상태를 확인하지 못했어요. 생성이 실패했다는 뜻은 아니에요.'
  } finally {
    if (ticket === epoch) {
      loading.value = false
      if (!terminal.value && !error.value) timer = setTimeout(load, 5000)
    }
  }
}
function visible () { clearTimeout(timer); if (!document.hidden) void load() }
watch(() => [route.params.jobId, auth.user?.userId], () => { epoch++; clearTimeout(timer); job.value = null; error.value = ''; loading.value = false; void load() }, { immediate: true })
document.addEventListener('visibilitychange', visible)
onBeforeUnmount(() => { epoch++; clearTimeout(timer); document.removeEventListener('visibilitychange', visible) })
</script>
<template>
  <main class="job-page">
    <section>
      <GenerationArtwork v-if="job && !error" :status="job.status" />
      <div aria-live="polite">
      <h1>{{ title }}</h1>
      <p v-if="!ASYNC_COURSES_ENABLED">비동기 코스 생성 서비스 준비 중이에요.</p>
      <p v-else-if="error" role="alert">{{ error }}</p>
      <template v-else-if="job">
        <p v-if="job.status === 'QUEUED'">먼저 접수된 요청을 처리한 뒤 시작해요.</p>
        <p v-else-if="job.status === 'RUNNING'">혼잡도와 이동 동선을 고려하고 있어요. 다른 화면을 둘러봐도 괜찮아요.</p>
        <p v-else-if="job.status === 'FAILED'">요청을 완료하지 못했어요. 조건을 확인한 뒤 새로 요청해 주세요. 실패한 작업은 자동으로 다시 접수되지 않아요.</p>
        <p v-else-if="job.status === 'SUCCEEDED'">결과를 확인하고 마음에 들면 저장해 주세요. 생성만으로 여행이 확정되지는 않아요.</p>
        <p v-if="!terminal">완료 또는 최종 실패 결과는 알림 내역에서도 확인할 수 있어요.</p>
      </template>
      <p v-else-if="loading">진행 상태를 확인하고 있어요.</p>
      </div>
      <div class="actions">
        <RouterLink v-if="job?.status === 'SUCCEEDED'" class="btn primary" :to="{ name: 'ai-course', query: { job: job.jobId } }">생성된 코스 보기</RouterLink>
        <RouterLink v-if="job?.status === 'FAILED'" class="btn primary" to="/ai-course">여행 조건 다시 선택</RouterLink>
        <button v-if="ASYNC_COURSES_ENABLED" type="button" class="btn" :disabled="loading" @click="load">상태 새로고침</button>
        <RouterLink class="btn" to="/mypage/alerts">알림 내역</RouterLink>
      </div>
    </section>
  </main>
</template>
<style scoped>
.job-page { width: min(720px, calc(100% - 40px)); margin: 50px auto; color: var(--tx); }
section { margin-top: 20px; padding: clamp(20px, 5vw, 40px); background: transparent; border: 0; text-align: center; }
h1 { font-size: clamp(22px, 4vw, 30px); line-height: 1.4; }
p { line-height: 1.8; color: var(--tx2); }
.actions { display: flex; justify-content: center; gap: 12px; flex-wrap: wrap; margin-top: 24px; }
</style>
