<script setup>
import { onMounted, ref } from 'vue'
import { useRouter } from 'vue-router'
import AuthLayout from '../../components/auth/AuthLayout.vue'
import { getWithdrawalContext, cancelWithdrawal, declineWithdrawalRecovery } from '../../api/userAuth.js'
import { useAuthStore } from '../../stores/auth.js'
import { ApiError } from '../../api/errors.js'

const auth = useAuthStore()
const router = useRouter()
const context = ref(null)
const loading = ref(true)
const busy = ref(false)
const error = ref('')

function deletionDate (value) {
  // 서버의 UTC 시각을 KST로 표시한다. offset 없는 응답도 UTC로 해석한다.
  const utc = /(?:Z|[+-]\d{2}:\d{2})$/.test(value) ? value : `${value}Z`
  return new Intl.DateTimeFormat('ko-KR', { timeZone: 'Asia/Seoul', dateStyle: 'long', timeStyle: 'short' }).format(new Date(utc))
}

onMounted(async () => {
  auth.handleUnauthorized(null, null)
  auth.returnTo = null
  try { context.value = await getWithdrawalContext() }
  catch { error.value = '인증 확인이 만료되었거나 취소할 수 없는 계정이에요. 다시 로그인해 확인해 주세요.' }
  finally { loading.value = false }
})

async function decide (restore) {
  if (busy.value || !context.value) return
  busy.value = true
  error.value = ''
  try {
    if (restore) await cancelWithdrawal()
    else await declineWithdrawalRecovery()
    context.value = null
    await router.replace({ name: 'login', query: { withdrawal: restore ? 'cancelled' : 'kept' } })
  } catch (e) {
    if ([401, 403, 410].includes(e?.status)) context.value = null
    error.value = e instanceof ApiError ? e.message : '처리 결과를 확인하지 못했어요. 다시 로그인해 확인해 주세요.'
  } finally { busy.value = false }
}
</script>

<template>
  <AuthLayout title="회원탈퇴 취소 확인" back-to="/login" back-label="로그인 화면으로">
    <p v-if="loading" role="status">계정 상태를 확인하고 있어요.</p>
    <template v-else-if="context">
      <p class="email">{{ context.email }}</p>
      <h2 class="question">회원탈퇴를 시도한 계정입니다.<br>회원탈퇴를 취소하시겠습니까?</h2>
      <div class="retention">
        <p>삭제 예정: <strong>{{ deletionDate(context.deleteAt) }} (한국 시간)</strong></p>
        <p>위 시각부터는 탈퇴를 취소할 수 없으며 이후 자동 정리 작업에서 영구 삭제돼요.</p>
      </div>
      <p class="hint">취소하더라도 기존 공유 링크와 여행 알림은 자동으로 다시 켜지지 않아요.</p>
      <div class="actions" :aria-busy="busy">
        <button class="btn2" :disabled="busy" @click="decide(false)">아니오</button>
        <button class="btn2 primary" :disabled="busy" @click="decide(true)">예</button>
      </div>
      <p v-if="busy" role="status" class="hint">처리하고 있어요…</p>
    </template>
    <p v-if="error" class="error" role="alert">{{ error }}</p>
    <RouterLink v-if="!loading && !context" class="btn2 primary" to="/login">로그인 화면으로</RouterLink>
  </AuthLayout>
</template>

<style scoped>
.email { margin-bottom: 14px; color: var(--ac); font-weight: 700; overflow-wrap: anywhere; }
.question { font-size: clamp(19px, 3vw, 23px); font-weight: 700; line-height: 1.6; letter-spacing: -.04em; }
.retention { margin: 22px 0 14px; padding: 16px; border: 1px solid var(--rule); background: var(--surf); border-radius: 12px; }
.retention p { margin: 0; font-size: 13px; line-height: 1.7; color: var(--tx2); }
.retention p + p { margin-top: 10px; }
.hint { font-size: 12px; line-height: 1.7; color: var(--tx3); }
.actions { display: flex; gap: 12px; margin: 24px 0 12px; }
.actions button { flex: 1; min-height: 48px; }
.error { margin: 18px 0; color: var(--busy); line-height: 1.7; }
</style>
