<script setup>
/** 내 프로필 사진 선택·미리보기·저장. 변경은 본인만 가능하고 등록한 사진은 리뷰에도 공개된다. */
import { computed, nextTick, onBeforeUnmount, ref, watch } from 'vue'
import { useAuthStore } from '../../stores/auth.js'
import { currentProfileImagePath, publicProfileImageUrl } from '../../utils/profileImage.js'
import { useApiError } from '../../composables/useApiError.js'
import BaseModal from '../common/BaseModal.vue'
import ProfilePhotoCropper from './ProfilePhotoCropper.vue'

const auth = useAuthStore()
const toMessage = useApiError()
const input = ref(null)
const changeButton = ref(null)
const currentUrl = computed(() => publicProfileImageUrl(currentProfileImagePath(auth.user)))
const imageFailed = ref(false)
const imageAttempt = ref(0)
const previewUrl = ref('')
const selected = ref(null)
const cropper = ref(null)
const cropReady = ref(false)
const saving = ref(false)
const loading = ref(false)
const error = ref('')
const notice = ref('')
let disposed = false

// Blob URL은 교체·닫기·화면 이탈 시 해제해 원문 이미지가 메모리에 누적되지 않게 한다.
function revoke (url) { if (url) URL.revokeObjectURL(url) }
function clearSelection () {
  revoke(previewUrl.value)
  previewUrl.value = ''
  selected.value = null
  cropReady.value = false
}
function close () {
  if (saving.value) return
  clearSelection()
  error.value = ''
  changeButton.value?.focus()
}

// 이 대화상자 안에서 Tab이 순환하도록 한다. 저장 중에는 바깥 버튼으로 포커스가 빠지지 않는다.
function trapFocus (event) {
  if (event.key !== 'Tab') return
  const buttons = [...event.currentTarget.querySelectorAll('button:not(:disabled), input:not(:disabled), [tabindex="0"]')]
  if (!buttons.length) return
  const index = buttons.indexOf(document.activeElement)
  event.preventDefault()
  const next = index < 0 ? (event.shiftKey ? buttons.length - 1 : 0)
    : (index + (event.shiftKey ? -1 : 1) + buttons.length) % buttons.length
  buttons[next]?.focus()
}

function loadPhoto () {
  imageFailed.value = false
  imageAttempt.value += 1
  loading.value = !!currentUrl.value
}

function photoFailed () {
  imageFailed.value = true
  loading.value = false
  error.value = auth.user?.demoAccount ? '사진을 불러오지 못했어요. 다시 시도해 주세요.' : '사진을 불러오지 못했어요. 다시 시도하거나 새 사진을 등록해 주세요.'
}

watch(() => [auth.user?.userId, auth.user?.profileImageUrl], () => {
  // 계정 변경이나 사진 교체 때 이전 계정의 이미지가 잠깐이라도 남지 않게 비운다.
  clearSelection()
  error.value = ''
  loadPhoto()
}, { immediate: true })

function choose (event) {
  const file = event.target.files?.[0]
  event.target.value = '' // 같은 파일을 다시 선택해도 change가 발생한다.
  if (!file || saving.value || auth.user?.demoAccount) return
  error.value = ''
  notice.value = ''
  if (!['image/jpeg', 'image/png', 'image/webp'].includes(file.type) || !file.size || file.size > 5 * 1024 * 1024) {
    error.value = '5MB 이하의 JPG, PNG, WebP 사진을 선택해 주세요.'
    return
  }
  clearSelection()
  selected.value = file
  previewUrl.value = URL.createObjectURL(file)
}

async function save () {
  if (!selected.value || saving.value || !cropReady.value || auth.user?.demoAccount) return
  const original = selected.value
  const userId = auth.user?.userId
  saving.value = true
  error.value = ''
  try {
    const cropped = await cropper.value.exportFile()
    // 비동기 이미지 변환 중 로그아웃·사진 교체가 일어나면 다른 계정으로 보내지 않는다.
    if (disposed || original !== selected.value || userId !== auth.user?.userId) return
    await auth.updateProfileImage(cropped)
    if (disposed) return
    clearSelection()
    notice.value = '프로필 사진을 변경했어요.'
  } catch (e) {
    if (!disposed) error.value = e?.code === 'SESSION_CHANGED' ? e.message : (toMessage(e) || '')
  } finally {
    saving.value = false
    await nextTick()
    if (!disposed && !selected.value) changeButton.value?.focus()
  }
}

onBeforeUnmount(() => {
  disposed = true
  clearSelection()
})
</script>

<template>
  <div class="profile-photo" :aria-busy="saving || loading">
    <span class="photo-ring">
      <img v-if="currentUrl && !imageFailed" :key="`${currentUrl}:${imageAttempt}`" class="avatar" :src="currentUrl" alt="내 프로필 사진"
        referrerpolicy="no-referrer" @load="loading = false" @error="photoFailed" />
      <span v-else class="avatar initial" aria-label="기본 프로필">{{ auth.initial }}</span>
    </span>
    <p v-if="auth.user?.demoAccount" class="photo-note">데모 계정의 프로필 사진은 변경할 수 없어요.</p>
    <template v-else>
      <button ref="changeButton" class="photo-button" type="button" :disabled="saving" @click="input?.click()">사진 변경</button>
      <input ref="input" hidden type="file" accept="image/jpeg,image/png,image/webp" aria-label="프로필 사진 선택" @change="choose" />
    </template>
    <p v-if="loading" class="photo-note" role="status">사진을 불러오는 중이에요.</p>
    <p v-if="notice" class="photo-note" role="status">{{ notice }}</p>
    <div v-if="error && !selected" class="photo-error" role="alert">
      {{ error }}
      <button v-if="auth.user?.profileImageUrl" type="button" class="photo-button" @click="error = ''; loadPhoto()">다시 불러오기</button>
    </div>
    <Teleport to="body">
      <div v-if="selected" @keydown="trapFocus">
        <BaseModal title="프로필 사진 변경" labelled-by="profile-photo-title" @close="close">
          <div class="photo-preview">
            <ProfilePhotoCropper :key="previewUrl" ref="cropper" :src="previewUrl" :disabled="saving"
              @ready="cropReady = $event" @error="error = $event" />
            <p class="photo-note">원 안에 보이는 영역을 저장해요. JPG, PNG, WebP · 최대 5MB</p>
            <p class="photo-note">등록한 프로필 사진은 리뷰에서 다른 사람에게도 보여요.</p>
            <p v-if="error" class="photo-error" role="alert">{{ error }}</p>
            <div class="photo-actions">
              <button type="button" class="btn2" :disabled="saving" @click="close">취소</button>
              <button type="button" class="btn2 primary" :disabled="saving || !cropReady" @click="save">{{ saving ? '저장 중…' : '사진 저장' }}</button>
            </div>
          </div>
        </BaseModal>
      </div>
    </Teleport>
  </div>
</template>

<style scoped>
.profile-photo { display: flex; flex-direction: column; align-items: center; gap: 10px; width: 160px; flex-shrink: 0; }
.photo-ring { display: block; width: 128px; height: 128px; padding: 4px; border-radius: 50%; background: linear-gradient(to top right, var(--ac), var(--ac-soft)); }
.avatar { display: block; width: 100%; height: 100%; border-radius: 50%; object-fit: cover; background: var(--surf); }
.initial { display: grid; place-items: center; border: 4px solid var(--surf); background: var(--ac-bg); color: var(--ac-dk); font-family: var(--font-head); font-size: 40px; font-weight: 900; }
.photo-button { padding: 7px 14px; border: 1px solid var(--line); border-radius: 999px; background: var(--surf); color: var(--ac); font: inherit; font-size: 13px; font-weight: 700; cursor: pointer; }
.photo-button:focus-visible { outline: 2px solid var(--ac); outline-offset: 3px; }
.photo-button:disabled { opacity: .6; cursor: wait; }
.photo-note { margin: 0; color: var(--tx2); font-size: 12px; line-height: 1.6; text-align: center; }
.photo-error { color: var(--danger, #b42318); font-size: 13px; line-height: 1.6; text-align: center; }
.photo-preview { display: flex; flex-direction: column; align-items: center; gap: 20px; padding-top: 20px; }
.photo-actions { display: flex; justify-content: flex-end; gap: 12px; width: 100%; }
@media (max-width: 900px) {
  .profile-photo { width: 140px; }
  .photo-ring { width: 96px; height: 96px; }
  .initial { font-size: 30px; }
}
</style>
