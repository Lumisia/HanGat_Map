<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, ref } from 'vue'
import CourseShareService, { type ShareStatus } from '../../services/CourseShareService'
import { preloadKakao, shareToKakao } from '../../composables/useKakaoShare.js'
import AppIcon from '../common/AppIcon.vue'
import QRCode from 'qrcode'

const props = defineProps<{ courseId: string; title: string }>()
const emit = defineEmits<{ close: [] }>()
const dialog = ref<HTMLDialogElement>()
const state = ref<ShareStatus | null>(null)
const busy = ref(false)
const error = ref('')
const notice = ref('')
const sdkReady = ref(false)
const confirming = ref(false)
const qrImage = ref('')
const qrBusy = ref(false)
let alive = true
const previousFocus = document.activeElement as HTMLElement | null
const url = computed(() => state.value?.active && state.value.token ? `${window.location.origin}/share/${encodeURIComponent(state.value.token)}` : '')
async function run(action: 'status' | 'create' | 'revoke') {
  if (busy.value) return
  busy.value = true; error.value = ''; notice.value = ''
  qrImage.value = ''
  try {
    const result = await CourseShareService[action](props.courseId)
    if (alive) { state.value = result; confirming.value = false }
  } catch {
    // A lost mutation response may already have changed server state. Recheck before offering another mutation.
    if (alive) { state.value = null; error.value = '공유 상태를 확인하지 못했어요. 다시 확인해 주세요.' }
  } finally { if (alive) busy.value = false }
}
async function copy() {
  try { await navigator.clipboard.writeText(url.value); if (alive) notice.value = '링크를 복사했어요.' }
  catch { if (alive) notice.value = '복사하지 못했어요. 아래 링크를 직접 선택해 복사해 주세요.' }
}
async function prepare() { const ready = await preloadKakao(); if (alive) sdkReady.value = ready }
async function toggleQr() {
  if (qrImage.value) { qrImage.value = ''; return }
  if (qrBusy.value || !url.value) return
  const target = url.value
  qrBusy.value = true
  try {
    const image = await QRCode.toDataURL(target, { width: 256, margin: 4, errorCorrectionLevel: 'M' })
    if (alive && url.value === target && !busy.value) qrImage.value = image
  } catch { if (alive) notice.value = 'QR 코드를 만들지 못했어요. 링크 복사를 이용해 주세요.' }
  finally { if (alive) qrBusy.value = false }
}
function kakao() {
  try {
    if (!shareToKakao({ title: props.title, description: '공유된 제주 여행 코스를 확인해 보세요.', url: url.value, buttonTitle: '코스 보기' })) notice.value = '카카오톡 공유 준비를 다시 시도해 주세요.'
  } catch { notice.value = '카카오톡 공유를 열지 못했어요. 링크 복사를 이용해 주세요.' }
}
onMounted(() => { dialog.value?.showModal(); void run('status'); void prepare() })
onBeforeUnmount(() => { alive = false; dialog.value?.close(); previousFocus?.focus() })
</script>
<template>
  <Teleport to="body">
    <dialog ref="dialog" class="course-share-dialog" aria-labelledby="course-share-title" aria-describedby="course-share-policy" @cancel.prevent="emit('close')">
      <header><span class="share-badge"><AppIcon name="share" :size="13" /> 여행 코스 공유</span><button class="close-share" type="button" aria-label="공유 창 닫기" @click="emit('close')">×</button></header>
      <h2 id="course-share-title">{{ title }}</h2>
      <p class="share-intro">로그인 없이도 링크로 일정, 날씨, 혼잡 예보를 확인할 수 있어요.</p>
      <div id="course-share-policy" class="privacy-box">
        <p><AppIcon name="check" :size="16" /><span><strong>공개되는 정보:</strong> 일정 순서, 장소별 혼잡 예보, 날씨</span></p>
        <p><AppIcon name="lock" :size="16" /><span><strong>비공개 정보 보호:</strong> 인원·숙소·개인 메모는 공유되지 않아요.</span></p>
      </div>
      <p v-if="busy" role="status">공유 상태를 확인하는 중이에요…</p>
      <div v-else-if="error" role="alert">{{ error }} <button type="button" @click="run('status')">다시 확인</button></div>
      <template v-else-if="state?.active">
        <label for="course-share-url">코스 전용 공유 링크</label>
        <div class="share-link-row"><span aria-hidden="true">↗</span><input id="course-share-url" aria-label="공유 링크" :value="url" readonly @focus="($event.target as HTMLInputElement).select()"><button class="copy-link" type="button" @click="copy"><svg viewBox="0 0 24 24" aria-hidden="true"><path d="M8 4h12v15H8zM4 8v14h12" /></svg>링크 복사</button></div>
        <div class="share-actions"><button v-if="sdkReady" class="kakao-share" type="button" @click="kakao"><svg viewBox="0 0 24 24" aria-hidden="true"><path d="M12 3C6.5 3 2 6.4 2 10.5c0 2.7 1.9 5 4.7 6.3L5.5 21l5-3c.5 0 1 .1 1.5.1 5.5 0 10-3.4 10-7.6S17.5 3 12 3" /></svg>카카오톡 공유</button><button v-else type="button" @click="prepare">카카오톡 연결 다시 시도</button><button type="button" :disabled="qrBusy" :aria-expanded="!!qrImage" aria-controls="share-qr" @click="toggleQr"><svg viewBox="0 0 24 24" aria-hidden="true"><path d="M3 3h6v6H3zM15 3h6v6h-6zM3 15h6v6H3zM15 15h3v3h3v3h-6zM12 3v9H3M12 15v6M18 12h3" /></svg>QR 코드</button><button v-if="!confirming" type="button" class="stop-sharing" @click="confirming = true">공유 중지</button></div>
        <div v-if="qrImage" id="share-qr" class="share-qr"><img :src="qrImage" alt="코스 공유 링크 QR 코드" width="256" height="256"><p>카메라로 스캔하면 공유 코스가 열려요.</p></div>
        <template v-if="confirming"><p>공유를 중지하면 기존 링크로 볼 수 없어요. 다시 공유하면 새 링크가 만들어져요.</p><div class="share-actions"><button type="button" @click="run('revoke')">공유 중지 확인</button><button type="button" @click="confirming = false">취소</button></div></template>
      </template>
      <button v-else-if="state" type="button" class="create-share" @click="run('create')">공유 링크 만들기</button>
      <small class="share-footnote">원본 수정도 반영돼요. 제목에 개인 정보가 없는지 확인해 주세요.</small>
      <p v-if="notice" role="status">{{ notice }}</p>
    </dialog>
  </Teleport>
</template>
<style scoped>
.course-share-dialog { box-sizing:border-box; width:min(492px, calc(100vw - 24px)); max-height:calc(100dvh - 32px); overflow:auto; margin:auto; padding:24px 26px 18px; border:1px solid var(--line); border-radius:22px; background:var(--surf); color:var(--tx); box-shadow:0 20px 80px #0003; }
.course-share-dialog::backdrop { background:#172a3266; }
header,.share-actions { display:flex; align-items:center; gap:8px; }
header { justify-content:space-between; margin-bottom:6px; }
h2 { font-size:23px; line-height:1.4; margin:0; overflow-wrap:anywhere; } p { font-size:12px; line-height:1.7; color:var(--tx2); }
.share-badge { display:inline-flex; align-items:center; gap:6px; border-radius:20px; padding:4px 12px; background:var(--ac-bg); color:var(--primary); font-size:11px; font-weight:800; }
button { display:inline-flex; align-items:center; justify-content:center; gap:6px; border:1px solid var(--line); border-radius:11px; padding:9px 12px; min-height:38px; background:var(--surf); color:var(--tx); cursor:pointer; font-size:12px; font-weight:700; white-space:nowrap; } button:disabled { opacity:.5; cursor:wait; } button:focus-visible,input:focus-visible { outline:3px solid var(--primary); outline-offset:2px; }
.close-share { width:34px; min-height:34px; padding:0; border:0; border-radius:50%; background:var(--surf2); color:var(--tx2); font-size:22px; }
.share-intro { margin:7px 0 22px; }
.privacy-box { background:var(--ac-bg); border:1px solid color-mix(in srgb,var(--primary) 24%,transparent); border-radius:16px; padding:6px 15px; margin-bottom:22px; }
.privacy-box p { display:flex; align-items:flex-start; gap:9px; margin:0; padding:8px 0; font-size:12px; }
.privacy-box p:first-child { color:var(--primary); border-bottom:1px solid color-mix(in srgb,var(--primary) 12%,transparent); }
.privacy-box svg { color:var(--primary); flex-shrink:0; margin-top:2px; }
.privacy-box strong { color:var(--tx); } label { display:block; font-size:12px; margin-bottom:7px; color:var(--tx2); }
.share-link-row { display:flex; align-items:center; gap:9px; padding:5px; border:1px solid var(--line); background:var(--surf2); border-radius:14px; }
.share-link-row>span { color:var(--tx2); margin-left:9px; } input { min-width:0; flex:1; padding:8px 0; border:0; color:var(--tx); background:transparent; font-size:12px; }
.copy-link,.create-share { background:var(--primary); color:var(--on-ac); border-color:transparent; } .create-share { width:100%; }
.share-actions { border-top:1px solid var(--line); padding-top:8px; margin-top:21px; flex-wrap:wrap; }
.share-actions .kakao-share { background:#fee500; color:#191600; border-color:#fee500; }
button svg { width:16px; height:16px; fill:none; stroke:currentColor; stroke-width:1.7; } .kakao-share svg { fill:currentColor; stroke:none; }
.share-actions .stop-sharing { margin-left:auto; color:var(--tx2); background:transparent; border-color:transparent; font-weight:400; padding-right:0; }
.share-qr { text-align:center; margin-top:16px; } .share-qr img { max-width:100%; height:auto; border-radius:12px; } .share-qr p { margin:4px 0; }
.share-footnote { display:block; color:var(--tx2); font-size:10px; line-height:1.6; margin-top:12px; }
@media(max-width:380px) { .course-share-dialog { padding:18px 16px; } .share-actions { gap:6px; } .share-actions button { font-size:11px; padding:8px; } .share-link-row { gap:5px; } .copy-link { padding:8px; } }
</style>
