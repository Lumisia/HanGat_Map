<script setup>
/* MAP-09 후기 — 실 API. 열람은 누구나, 작성·삭제는 회원만 (JWT) */
import { ref, computed, watch, nextTick, onBeforeUnmount } from 'vue'
import StarIcon from './StarIcon.vue'
import ProfileAvatar from '../common/ProfileAvatar.vue'
import ReviewEditDialog from '../review/ReviewEditDialog.vue'
import { useReviewEditWindow } from '@/composables/useReviewEditWindow.js'
import { toast } from '@/stores/mapStore'
import { useAuthStore } from '@/stores/auth.js'
import ReviewApiService, { absUrl, failText } from '@/services/map/ReviewApiService'

const props = defineProps({
  place: { type: Object, required: true },
  /** 부모(상세 API)가 주는 별점 평균 - 목록 첫 페이지만으로는 못 구한다 */
  ratingAvg: { type: Number, default: null },
  /** 부모(PlaceDetail)가 미리보기용으로 이미 받은 후기 첫 페이지. 여기서 또 받지 않는다 -
      이 탭은 v-show 라 상세를 열 때 같이 마운트되므로 onMounted 로 받으면 같은 요청이 2번 나갔다(2026-09-12 실측).
      null 이면 부모가 못 받은 것 - 목록만 비우고 작성 폼은 살려 둔다 */
  firstPage: { type: Object, default: null },
  /** 부모의 후기 목록 요청이 실패했다 - "아직 후기가 없어요" 대신 다시 시도를 보여준다(최종점검 #23) */
  firstPageFailed: { type: Boolean, default: false }
})
const emit = defineEmits(['open-photo', 'changed'])

const auth = useAuthStore()


const MAX_PHOTOS = 5
/* 서버(ImageValidator)가 받는 기준과 같다 - 5MB 이하, JPG·PNG·WEBP. 고르는 순간 걸러야
   등록 버튼을 누른 뒤에야 "입력값을 확인해주세요"(서버 문구)를 보는 일이 없다 */
const MAX_PHOTO_BYTES = 5 * 1024 * 1024
const PHOTO_TYPES = ['image/jpeg', 'image/png', 'image/webp']
/* 브라우저가 type 을 못 채우는 경우(일부 윈도우의 webp)는 확장자로 본다 */
const isPhotoType = f => PHOTO_TYPES.includes(f.type) || (!f.type && /\.(jpe?g|png|webp)$/i.test(f.name))

/* ── 목록 ── */
const items = ref([])
const pageNo = ref(0)
const totalPages = ref(0)
const totalElements = ref(0)
const loading = ref(false)

/** 첫 페이지는 부모가 준다 - 처음 열 때, 장소가 바뀔 때, 작성·삭제 뒤(changed → 부모가 다시 읽음) 모두 이 경로로 갱신된다 */
function applyPage (page) {
  items.value = page?.content ?? []
  pageNo.value = page?.number ?? 0
  totalPages.value = page?.totalPages ?? 0
  totalElements.value = page?.totalElements ?? 0
}
watch(() => props.firstPage, applyPage, { immediate: true })
watch(() => props.place.id, resetForm)

/** '더보기' - 다음 페이지를 이어 붙인다 */
async function loadMore () {
  const id = props.place.id
  if (id == null) return    // 목업 장소는 후기 미지원
  loading.value = true
  try {
    const page = await ReviewApiService.getReviews(id, pageNo.value + 1)
    // 응답을 기다리는 사이 다른 장소로 바뀌었으면 버린다 - 안 그러면 이전 장소의 2페이지가 새 장소 목록 뒤에 붙었다(최종점검 #24)
    if (props.place.id !== id) return
    items.value = [...items.value, ...page.content]
    pageNo.value = page.number
    totalPages.value = page.totalPages
    totalElements.value = page.totalElements
  } catch {
    toast('후기를 더 불러오지 못했어요')   // 목록만 실패 - 작성 폼은 살려 둔다
  } finally {
    loading.value = false
  }
}

/* 사진 후기 띠 - 지금까지 읽은 후기(첫 페이지 + 더보기)에 달린 사진 전부. 한산·보통·혼잡 제보 막대가 있던 자리다.
   폭을 넘치면 양쪽 ‹ › 로 넘긴다(장소 소개 페이지의 띠와 같은 동작) */
const stripPhotos = computed(() => items.value.flatMap(r => (r.imageUrls ?? []).map(u => ({ url: absUrl(u), who: r.nickname ?? `여행자${r.userId}` }))))
const stripEl = ref(null)
const stripCan = ref({ l: false, r: false })
function stripSync () {
  const el = stripEl.value
  stripCan.value = el ? { l: el.scrollLeft > 2, r: el.scrollLeft + el.clientWidth < el.scrollWidth - 2 } : { l: false, r: false }
}
function stripGo (dir) {
  const el = stripEl.value
  if (el) el.scrollBy({ left: dir * el.clientWidth * 0.8, behavior: 'smooth' })
}
watch(stripPhotos, () => nextTick(stripSync), { flush: 'post' })
/* 이 화면은 v-show 라 숨어 있는 동안 폭이 0 이다 - 보이게 되는 순간(크기 변화)에도 다시 잰다 */
let stripRo = null
watch(stripEl, el => {
  stripRo?.disconnect(); stripRo = null
  if (el && 'ResizeObserver' in window) { stripRo = new ResizeObserver(stripSync); stripRo.observe(el) }
  stripSync()
})
onBeforeUnmount(() => stripRo?.disconnect())
function openStrip (i) { emit('open-photo', { photos: stripPhotos.value.map(p => p.url), index: i }) }
const myId = computed(() => auth.user?.userId ?? null)
const editing = ref(null)
const { canEdit } = useReviewEditWindow()
watch([() => props.place.id, () => auth.user?.userId], () => { editing.value = null }, { flush: 'sync' })
function onEdited() {
  editing.value = null
  emit('changed')
  toast('후기를 저장했어요')
}

const dateOf = iso => { const d = new Date(iso); return `${d.getFullYear()}.${d.getMonth() + 1}.${d.getDate()}` }

/* ── 작성 ── */
const star = ref(0)
const text = ref('')
const photos = ref([])          // { file, preview }
const submitting = ref(false)
const fileInput = ref(null)

/* 백엔드(ReviewService)는 별점·혼잡 제보 중 하나를 요구한다. 제보 입력을 뺐으니 별점이 있어야 등록 버튼이 켜진다 */
const canSubmit = computed(() => !!star.value && !submitting.value)

function resetForm () {
  star.value = 0; text.value = ''; photos.value = []
}

function onFiles (e) {
  const remain = MAX_PHOTOS - photos.value.length
  if (remain <= 0) { toast(`사진은 최대 ${MAX_PHOTOS}장까지예요`); e.target.value = ''; return }
  const picked = [...e.target.files]
  const ok = picked.filter(f => f.size <= MAX_PHOTO_BYTES && isPhotoType(f))
  for (const f of ok.slice(0, remain)) {
    photos.value.push({ file: f, preview: URL.createObjectURL(f) })
  }
  if (ok.length > remain) toast(`사진은 최대 ${MAX_PHOTOS}장까지예요`)
  // 서버가 거절할 파일은 빼고 나머지만 담았다고 알린다. 토스트는 한 번에 하나라 첫 파일 이름과 나머지 장수만 적는다
  const bad = picked.filter(f => !ok.includes(f))
  if (bad.length) {
    const why = bad[0].size > MAX_PHOTO_BYTES ? '5MB가 넘는 사진은 뺐어요' : 'JPG·PNG·WEBP 사진만 올릴 수 있어요'
    toast(`${why} · ${bad[0].name}${bad.length > 1 ? ` 외 ${bad.length - 1}장` : ''}`)
  }
  e.target.value = ''
}

/* 비로그인으로 등록을 누르면 화면 가운데 안내만 띄운다 - 로그인 화면으로 보내지 않는다(2026-09-13 결정).
   전엔 토스트를 띄우자마자 /login 으로 보내 토스트는 안 보이고, 로그인 뒤엔 홈으로 떨어져 보던 장소가 사라졌다(최종점검 #22) */
const loginAsk = ref(false)
const askOk = ref(null)
async function askLogin () {
  loginAsk.value = true
  await nextTick()
  askOk.value?.focus()          // 확인 버튼에 포커스 - Enter·Esc 로 바로 닫힌다
}

async function submit () {
  if (!canSubmit.value) return
  if (!auth.isLoggedIn) { askLogin(); return }
  submitting.value = true
  try {
    const imageUrls = photos.value.length
      ? await ReviewApiService.uploadPhotos(photos.value.map(p => p.file))
      : []
    await ReviewApiService.create(props.place.id, {
      rating: star.value || null,
      congestionReport: null,
      content: text.value.trim() || null,
      imageUrls
    })
    resetForm()
    emit('changed')             // 부모가 상세와 후기 첫 페이지를 다시 읽는다 - 별점 요약과 이 목록(firstPage)이 함께 갱신된다
    toast('후기가 등록됐어요')
  } catch (err) {
    toast(failText(err, '후기를 남기지 못했어요'))   // 서버 원문("JWT 토큰 유효하지 않음" 등)을 그대로 띄우지 않는다
  } finally {
    submitting.value = false
  }
}

async function removeReview (r) {
  try {
    await ReviewApiService.remove(r.id)
    emit('changed')
    toast('후기를 삭제했어요')
  } catch (err) {
    toast(failText(err, '후기를 삭제하지 못했어요'))
  }
}
</script>

<template>
  <div>
    <div class="rv-t">방문 후기를 남겨주세요</div>
    <div class="rv-star">
      <button v-for="n in 5" :key="n" :aria-label="`${n}점`" @click="star = n">
        <StarIcon :filled="n <= star" :size="26" />
      </button>
    </div>

    <div class="rv-phrow">
      <span v-for="(p, i) in photos" :key="i" class="rv-pht">
        <img :src="p.preview" alt="첨부한 사진">
        <button class="del" aria-label="사진 삭제" @click="photos.splice(i, 1)">×</button>
      </span>
      <button v-if="photos.length < MAX_PHOTOS" class="rv-phadd" @click="fileInput.click()">
        사진<br>{{ photos.length }}/{{ MAX_PHOTOS }}
      </button>
      <input ref="fileInput" type="file" accept="image/jpeg,image/png,image/webp" multiple style="display:none" @change="onFiles">
    </div>

    <div class="rv-in">
      <input v-model="text" placeholder="한 줄 남기기 (선택)" maxlength="60" @keydown.enter="submit">
      <button :disabled="!canSubmit" @click="submit">{{ submitting ? '등록 중…' : '등록' }}</button>
    </div>

    <!-- 비로그인 등록 시 가운데 안내. body 로 옮겨 그리는 이유는 달력(DatePicker)과 같다 - 모바일 바텀시트 안에서 fixed 가 시트 기준이 되지 않게 -->
    <Teleport to="body">
      <div v-if="loginAsk" class="rv-ask-bd" @click.self="loginAsk = false" @keydown.esc="loginAsk = false">
        <div class="rv-ask" role="alertdialog" aria-modal="true" aria-labelledby="rv-ask-title">
          <b id="rv-ask-title">로그인이 필요해요</b>
          <p>후기는 로그인을 하셔야 남길 수 있어요</p>
          <button ref="askOk" type="button" @click="loginAsk = false">확인</button>
        </div>
      </div>
    </Teleport>

    <template v-if="items.length">
      <div class="rv-sum">
        <StarIcon filled :size="15" />
        <!-- 별점 후기가 없으면 평균을 만들어내지 않는다 -->
        <span class="avg">{{ ratingAvg != null ? ratingAvg.toFixed(1) : '-' }}</span>
        <span class="cnt">후기 {{ totalElements }}</span>
      </div>
      <!-- 사진 후기 띠 - 사진 달린 후기가 있을 때만 -->
      <div v-if="stripPhotos.length" class="rv-photos">
        <div class="rv-photos-h">사진 후기 <b>{{ stripPhotos.length }}</b></div>
        <div class="rv-strip">
          <button v-if="stripCan.l" class="rv-pnav prev" aria-label="이전 사진들" @click="stripGo(-1)">‹</button>
          <ul ref="stripEl" aria-label="후기 사진" @scroll.passive="stripSync">
            <li v-for="(p, i) in stripPhotos" :key="p.url + i">
              <button :aria-label="`${p.who}님의 후기 사진 크게 보기`" @click="openStrip(i)"><img :src="p.url" :alt="`${p.who}님의 후기 사진`" loading="lazy" decoding="async"></button>
            </li>
          </ul>
          <button v-if="stripCan.r" class="rv-pnav next" aria-label="다음 사진들" @click="stripGo(1)">›</button>
        </div>
      </div>

      <div v-for="r in items" :key="r.id" class="rv-i">
        <div class="rv-h">
          <ProfileAvatar :src="r.profileImageUrl" :nickname="r.nickname" />
          <!-- 탈퇴 등으로 닉네임이 없으면(null) 익명 표기로 대체한다 -->
          <span class="rv-nm">{{ r.nickname ?? `여행자${r.userId}` }}</span>
          <span class="rv-dt">{{ dateOf(r.createdAt) }} 작성 <span v-if="r.editedAt">(수정)</span></span>
          <span v-if="myId === r.userId" class="rv-actions">
            <button v-if="canEdit(r)" class="rv-edit" aria-label="내 후기 수정" @click="editing = r">수정</button>
            <button class="rv-del" aria-label="내 후기 삭제" @click="removeReview(r)">삭제</button>
          </span>
        </div>
        <div class="rv-mt">
          <template v-if="r.rating"><StarIcon v-for="n in 5" :key="n" :filled="n <= r.rating" :size="12" /></template>
          <!-- 예전 후기에 저장된 혼잡 제보(한산·보통·혼잡)는 더 이상 보여주지 않는다(2026-09-18 사용자 결정). 값은 DB 에 남아 있을 뿐 -->
        </div>
        <div v-if="r.content" class="rv-tx">{{ r.content }}</div>
        <div v-if="r.imageUrls && r.imageUrls.length" class="rv-imgs">
          <img v-for="(p, pi) in r.imageUrls" :key="pi" :src="absUrl(p)" :alt="`후기 사진 ${pi + 1}`"
            title="클릭하면 크게 보기" @click="emit('open-photo', { photos: r.imageUrls.map(absUrl), index: pi })">
        </div>
      </div>

      <button v-if="pageNo + 1 < totalPages" class="rvchip"
        style="justify-content:center;margin-top:8px" :disabled="loading" @click="loadMore">
        <span class="ct">후기 {{ totalElements - items.length }}개 더보기</span>
      </button>
    </template>

    <template v-else-if="firstPageFailed">
      <div class="rv-none">후기를 불러오지 못했어요.</div>
      <!-- changed 는 부모가 상세·후기 첫 페이지를 다시 받는 신호 - 등록·삭제 뒤와 같은 경로 -->
      <button class="rvchip" style="justify-content:center" @click="emit('changed')"><span class="ct">다시 시도</span></button>
    </template>
    <div v-else class="rv-none">아직 후기가 없어요.<br>첫 방문 후기를 남겨보세요.</div>
    <ReviewEditDialog v-if="editing" :key="editing.id" :review="editing" @close="editing = null" @saved="onEdited" />
  </div>
</template>

<style scoped>
.rv-actions{margin-left:auto;display:flex;gap:8px;flex-shrink:0}
.rv-del,.rv-edit{background:none;border:0;color:var(--tx3);font-size:11px;padding:6px 0}
.rv-edit:hover{color:var(--ac-dk)}
.rv-del:hover{color:#b02c2c}
/* 비로그인 안내 - Teleport 로 body 에 그리므로 hangat.css(@scope .map-shell) 밖. 달력 백드롭과 같은 z·톤 */
.rv-ask-bd{position:fixed;inset:0;z-index:2500;background:rgba(15,25,35,.45);display:flex;align-items:center;justify-content:center;padding:20px}
.rv-ask{background:var(--surf);color:var(--tx);border-radius:18px;box-shadow:var(--sh2);width:300px;max-width:100%;
  padding:22px 20px 18px;text-align:center;display:flex;flex-direction:column;align-items:center;gap:8px;word-break:keep-all}
.rv-ask b{font-size:15.5px;font-weight:800;letter-spacing:-.02em}
.rv-ask p{margin:0;font-size:13px;line-height:1.55;color:var(--tx2)}
.rv-ask button{margin-top:8px;min-width:120px;padding:10px 18px;border-radius:11px;background:var(--ac);color:var(--on-ac);font-size:13px;font-weight:800}
.rv-ask button:hover{filter:brightness(.95)}
</style>
