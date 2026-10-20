<script setup>
import { computed, nextTick, onBeforeUnmount, onMounted, ref } from 'vue'
import { useRouter } from 'vue-router'
import BaseModal from '../common/BaseModal.vue'
import { useAuthStore } from '../../stores/auth.js'
import { ApiError } from '../../api/errors.js'

const emit = defineEmits(['close'])
const auth = useAuthStore()
const router = useRouter()
const email = ref('')
const busy = ref(false)
const error = ref('')
const controls = ref(null)
const cancelButton = ref(null)
const opener = document.activeElement
const matches = computed(() => email.value.trim().toLowerCase() === auth.user?.email?.trim().toLowerCase())

function close () { if (!busy.value) emit('close') }
function trapFocus (event) {
  if (event.key !== 'Tab') return
  const items = [...controls.value.querySelectorAll('input:not(:disabled), button:not(:disabled)')]
  const next = items.indexOf(document.activeElement) + (event.shiftKey ? -1 : 1)
  event.preventDefault()
  if (items.length) items[(next + items.length) % items.length].focus()
}
onMounted(async () => { await nextTick(); cancelButton.value?.focus() })
onBeforeUnmount(() => { if (opener instanceof HTMLElement && opener.isConnected) opener.focus() })

async function submit () {
  if (busy.value || !matches.value || auth.user?.demoAccount) return
  busy.value = true
  error.value = ''
  try {
    await auth.withdraw(email.value.trim())
    emit('close')
    await router.replace({ name: 'login', query: { withdrawn: '1' } })
  } catch (e) {
    error.value = e instanceof ApiError ? e.message : '탈퇴 처리 결과를 확인하지 못했어요. 다시 로그인해 계정 상태를 확인해 주세요.'
  } finally { busy.value = false }
}
</script>

<template>
  <BaseModal title="정말 회원탈퇴 하시겠어요?" labelled-by="withdrawal-title" @close="close">
    <form ref="controls" :aria-busy="busy" @submit.prevent="submit" @keydown="trapFocus">
      <p class="description">탈퇴하면 모든 기기에서 로그아웃되고, 공유 코스와 여행 알림이 중단돼요.</p>
      <div class="retention">
        <strong>탈퇴 시점부터 정확히 30일간 보관해요</strong>
        <p>30일이 지나기 전에 다시 로그인하면 탈퇴를 취소할 수 있어요. 이후에는 계정과 관련 데이터가 영구 삭제되어 복구할 수 없어요.</p>
        <p>탈퇴 처리 안내는 가입한 이메일로 보내드려요.</p>
      </div>
      <label for="withdrawal-email">본인 이메일을 입력해 주세요</label>
      <p class="account">{{ auth.user?.email }}</p>
      <input id="withdrawal-email" v-model="email" type="email" autocomplete="off" autocapitalize="none" spellcheck="false"
        :disabled="busy" required aria-describedby="withdrawal-email-help" placeholder="가입한 이메일" />
      <p id="withdrawal-email-help" class="help">이메일이 일치하면 회원탈퇴 버튼을 누를 수 있어요.</p>
      <p v-if="error" class="error" role="alert">{{ error }}</p>
      <div class="actions">
        <button ref="cancelButton" type="button" class="btn2" :disabled="busy" @click="close">취소</button>
        <button type="submit" class="btn2 danger" :disabled="busy || !matches">{{ busy ? '처리하는 중…' : '회원탈퇴' }}</button>
      </div>
    </form>
  </BaseModal>
</template>

<style scoped>
.description, .retention p { font-size: 13px; line-height: 1.7; color: var(--tx2); }
.retention { margin: 18px 0; padding: 16px; background: var(--surf); border: 1px solid var(--rule); border-radius: 12px; }
.retention strong { font-size: 14px; color: var(--tx); }
.retention p { margin: 8px 0 0; }
label { font-size: 13px; font-weight: 700; }
.account { margin: 7px 0 12px; color: var(--tx2); font-size: 13px; overflow-wrap: anywhere; }
input { width: 100%; box-sizing: border-box; padding: 13px; border: 1px solid var(--rule); border-radius: 10px; background: var(--bg); color: var(--tx); font: inherit; font-size: 16px; }
input:focus-visible { outline: 2px solid var(--ac); outline-offset: 2px; }
.help { margin: 8px 0 16px; font-size: 12px; line-height: 1.5; color: var(--tx3); }
.actions { display: flex; gap: 10px; margin-top: 18px; }
.actions > button { flex: 1; min-height: 44px; }
.error { color: var(--busy); font-size: 13px; line-height: 1.6; }
</style>
