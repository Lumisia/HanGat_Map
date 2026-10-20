<script setup>
/** 마이페이지·지도 공통 후기 편집. 기존 사진은 URL을 유지하고 새 사진만 업로드한다. */
import { computed, ref, onBeforeUnmount } from 'vue'
import BaseModal from '../common/BaseModal.vue'
import StarIcon from '../map/StarIcon.vue'
import ReviewApiService, { absUrl } from '@/services/map/ReviewApiService'
import { useReviewEditWindow, isReviewEditable } from '@/composables/useReviewEditWindow.js'
import { getBackendSessionVersion } from '@/api/backendClient.js'

const props = defineProps({ review: { type: Object, required: true } })
const emit = defineEmits(['close', 'saved'])
const rating = ref(props.review.rating ?? '')
const content = ref(props.review.content ?? '')
const photos = ref((props.review.imageUrls ?? []).map(url => ({ url, preview: absUrl(url) })))
const fileInput = ref(null)
const busy = ref(false)
const sessionExpired = ref(false)
const error = ref('')
const { canEdit } = useReviewEditWindow()
const editable = computed(() => canEdit(props.review))
/* 혼잡 제보 입력은 뺐다(2026-09-18) - 서버가 별점·제보 중 하나를 요구하므로 별점이 있어야 저장할 수 있다 */
const canSave = computed(() => editable.value && !busy.value && !sessionExpired.value && !!rating.value)
const epoch = getBackendSessionVersion()
const previousFocus = typeof document === 'undefined' ? null : document.activeElement
let alive = true
const current = () => alive && epoch === getBackendSessionVersion()

function discard(photo) {
  if (photo.file) URL.revokeObjectURL(photo.preview)
}
onBeforeUnmount(() => {
  alive = false
  photos.value.forEach(discard)
  if (previousFocus?.isConnected) previousFocus.focus()
})
function removePhoto(index) {
  discard(photos.value[index])
  photos.value.splice(index, 1)
}
function choosePhotos(event) {
  error.value = ''
  const files = [...event.target.files]
  for (const file of files) {
    if (photos.value.length >= 5) { error.value = '사진은 최대 5장까지 첨부할 수 있어요.'; break }
    const validType = ['image/jpeg', 'image/png', 'image/webp'].includes(file.type)
      || (!file.type && /\.(jpe?g|png|webp)$/i.test(file.name))
    if (!validType || file.size > 5 * 1024 * 1024) {
      error.value = '사진은 JPG·PNG·WebP, 한 장당 5MB 이하로 선택해 주세요.'
      continue
    }
    photos.value.push({ file, url: null, preview: URL.createObjectURL(file) })
  }
  event.target.value = ''
}
function trapFocus(event) {
  if (event.key !== 'Tab') return
  const nodes = [...event.currentTarget.querySelectorAll('button:not(:disabled), input:not(:disabled):not([hidden]), textarea:not(:disabled)')]
  const first = nodes[0], last = nodes.at(-1)
  if (event.shiftKey && (document.activeElement === first || !nodes.includes(document.activeElement))) { event.preventDefault(); last?.focus() }
  else if (!event.shiftKey && (document.activeElement === last || !nodes.includes(document.activeElement))) { event.preventDefault(); first?.focus() }
}
async function save() {
  if (!canSave.value || !isReviewEditable(props.review)) return
  if (!current()) { sessionExpired.value = true; error.value = '로그인 상태가 변경됐어요. 창을 닫고 다시 로그인해 주세요.'; return }
  busy.value = true
  error.value = ''
  try {
    const added = photos.value.filter(photo => !photo.url)
    if (added.length) {
      const urls = await ReviewApiService.uploadPhotos(added.map(photo => photo.file), { sessionBound: true })
      if (!current()) { if (alive) emit('close'); return }
      // 저장 요청만 실패하면 다음 시도에서 이미 업로드한 사진을 재사용한다.
      added.forEach((photo, index) => { photo.url = urls[index] })
    }
    if (!isReviewEditable(props.review)) {
      error.value = '사진 전송 중 수정 기한이 지났어요. 더 이상 저장할 수 없어요.'
      return
    }
    const result = await ReviewApiService.update(props.review.id ?? props.review.reviewId, {
      rating: rating.value === '' ? null : Number(rating.value),
      congestionReport: null,   // 예전에 저장된 제보도 수정 시 비운다 - 서비스 어디에도 보여주지 않는 값
      content: content.value || null,
      imageUrls: photos.value.map(photo => photo.url)
    })
    if (current()) emit('saved', result)
  } catch (e) {
    if (alive) {
      sessionExpired.value = !current() || e?.code === 'SESSION_CHANGED'
      error.value = sessionExpired.value ? '로그인 상태가 변경됐어요. 창을 닫고 다시 로그인해 주세요.'
        : (e?.message || '리뷰를 수정하지 못했어요. 다시 시도해 주세요.')
    }
  } finally {
    if (alive) busy.value = false
  }
}
</script>

<template>
  <Teleport to="body">
    <BaseModal title="리뷰 수정" labelled-by="review-edit-title" @close="!busy && emit('close')" @keydown="trapFocus">
      <form class="review-edit" @submit.prevent="save">
        <p class="hint">작성 후 7일 이내에 수정할 수 있어요. 수정해도 기한은 늘어나지 않아요.</p>
        <p v-if="!editable" class="error" role="status">수정 기한이 지났어요. 리뷰는 삭제만 할 수 있어요.</p>
        <fieldset :disabled="busy || !editable">
          <div class="rating-field">
            <span id="review-rating-label" class="field-label">별점</span>
            <div class="stars" role="group" aria-labelledby="review-rating-label">
              <button v-for="n in 5" :key="n" type="button" :aria-label="`${n}점`"
                :aria-pressed="rating === n" @click="rating = n">
                <StarIcon :filled="n <= rating" :size="26" />
              </button>
            </div>
            <button v-if="rating" type="button" class="clear-rating" @click="rating = ''">별점 선택 해제</button>
          </div>
          <div class="photo-label"><span class="field-label">사진 <span class="hint">(선택)</span></span><span class="hint">{{ photos.length }}/5 · 한 장당 최대 5MB</span></div>
          <div class="photos">
            <div v-for="(photo, index) in photos" :key="photo.preview" class="photo">
              <img :src="photo.preview" :alt="`첨부 사진 ${index + 1}`">
              <button type="button" :aria-label="`사진 ${index + 1} 제거`" @click="removePhoto(index)">×</button>
            </div>
            <button v-if="photos.length < 5" type="button" class="photo-add" aria-label="사진 추가" @click="fileInput.click()">
              <span>사진</span><span>{{ photos.length }}/5</span>
            </button>
            <input ref="fileInput" type="file" accept="image/jpeg,image/png,image/webp" multiple hidden @change="choosePhotos">
          </div>
          <label class="content-label">한 줄 후기 <span class="hint">{{ content.length }}/60</span>
            <input v-model="content" type="text" maxlength="60" placeholder="한 줄 남기기 (선택)">
          </label>
        </fieldset>
        <p v-if="error" class="error" role="alert">{{ error }}</p>
        <div class="actions">
          <button type="button" class="cancel" :disabled="busy" @click="emit('close')">취소</button>
          <button type="submit" class="save" :disabled="!canSave">{{ busy ? '저장 중…' : '수정 저장' }}</button>
        </div>
      </form>
    </BaseModal>
  </Teleport>
</template>

<style scoped>
.review-edit { color: var(--tx); max-height: 70dvh; overflow-y: auto; }
.hint { color: var(--tx3); font-size: 12px; line-height: 1.65; font-weight: 400; }
fieldset { border: 0; padding: 0; margin: 18px 0; min-width: 0; }
label, .field-label { display: block; font-size: 13px; font-weight: 700; }
.rating-field { position: relative; text-align: center; margin-bottom: 18px; }
.stars { display: flex; justify-content: center; gap: 3px; margin-top: 7px; }
.stars button { width: 36px; height: 36px; display: grid; place-items: center; padding: 0; background: transparent; border: 0; border-radius: 8px; }
.clear-rating { padding: 4px 8px; margin-top: 2px; font-size: 11px; color: var(--tx3); background: transparent; border: 0; border-radius: 6px; }
.photo-label { display: flex; flex-wrap: wrap; align-items: baseline; justify-content: space-between; gap: 4px 10px; margin-bottom: 10px; }
.content-label input { display: block; width: 100%; box-sizing: border-box; margin-top: 7px; padding: 12px; border-radius: 10px; border: 1px solid var(--line); background: var(--surf2); color: var(--tx); font: inherit; font-size: 12.5px; font-weight: 400; }
.content-label input::placeholder { color: var(--tx3); }
.photos { display: flex; flex-wrap: wrap; gap: 10px; margin-bottom: 16px; }
.photo { position: relative; }
.photo img { display: block; width: 64px; height: 64px; object-fit: cover; border-radius: 10px; }
.photo-add { width: 64px; height: 64px; display: flex; flex-direction: column; align-items: center; justify-content: center; gap: 3px; border: 1.5px dashed var(--line2); border-radius: 10px; background: transparent; color: var(--tx3); font-size: 11px; font-weight: 700; }
.photo-add:hover { border-color: var(--ac); color: var(--ac); }
.photo button { position: absolute; right: -5px; top: -5px; width: 28px; height: 28px; border-radius: 50%; background: var(--surf); color: var(--tx); border: 1px solid var(--line); }
.error { color: var(--busy); font-size: 13px; line-height: 1.6; }
.actions { display: flex; justify-content: flex-end; gap: 10px; padding-top: 8px; }
.actions button { padding: 12px 20px; border-radius: var(--rp); font-size: 13px; font-weight: 700; }
.cancel { background: var(--surf2); color: var(--tx2); }
.save { background: var(--ac); color: var(--on-ac); }
button:disabled { opacity: .5; cursor: not-allowed; }
button:focus-visible, input:focus-visible { outline: 2px solid var(--ac); outline-offset: 2px; }
</style>
