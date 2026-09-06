<script setup>
/* MAP-09 후기 — 실 API. 열람은 누구나, 작성·삭제는 회원만 (JWT) */
import { ref, computed, watch, onMounted } from 'vue'
import { useRouter } from 'vue-router'
import StarIcon from './StarIcon.vue'
import { toast } from '@/stores/mapStore'
import { useAuthStore } from '@/stores/auth.js'
import ReviewApiService, { LEVEL_TO_KEY, absUrl } from '@/services/map/ReviewApiService'
import { CROWD_KO } from '@/utils/crowd'

const props = defineProps({
  place: { type: Object, required: true },
  /** 부모(상세 API)가 주는 별점 평균 - 목록 첫 페이지만으로는 못 구한다 */
  ratingAvg: { type: Number, default: null }
})
const emit = defineEmits(['open-photo', 'changed'])

const router = useRouter()
const auth = useAuthStore()

/* 화면 키(calm/mid/busy) → 서버 레벨 */
const TO_LEVEL = { calm: 'QUIET', mid: 'NORMAL', busy: 'CROWDED' }
const FROM_LEVEL = LEVEL_TO_KEY

const MAX_PHOTOS = 5

/* ── 목록 ── */
const items = ref([])
const pageNo = ref(0)
const totalPages = ref(0)
const totalElements = ref(0)
const loading = ref(false)

async function load (reset = true) {
  if (props.place.id == null) return    // 목업 장소는 후기 미지원
  loading.value = true
  try {
    const page = await ReviewApiService.getReviews(props.place.id, reset ? 0 : pageNo.value + 1)
    items.value = reset ? page.content : [...items.value, ...page.content]
    pageNo.value = page.number
    totalPages.value = page.totalPages
    totalElements.value = page.totalElements
  } catch {
    // 목록만 실패 - 작성 폼은 살려 둔다
  } finally {
    loading.value = false
  }
}
onMounted(load)
watch(() => props.place.id, () => { resetForm(); load() })

const counts = computed(() => {
  const c = { calm: 0, mid: 0, busy: 0 }
  items.value.forEach(r => { const k = FROM_LEVEL[r.congestionReport]; if (k) c[k]++ })
  return c
})
const total = computed(() => (counts.value.calm + counts.value.mid + counts.value.busy) || 1)
const myId = computed(() => ReviewApiService.myUserId())

const dateOf = iso => { const d = new Date(iso); return `${d.getFullYear()}.${d.getMonth() + 1}.${d.getDate()}` }

/* ── 작성 ── */
const star = ref(0)
const crowdVote = ref('')
const text = ref('')
const photos = ref([])          // { file, preview }
const submitting = ref(false)
const fileInput = ref(null)

const canSubmit = computed(() => (!!star.value || !!crowdVote.value) && !submitting.value)

function resetForm () {
  star.value = 0; crowdVote.value = ''; text.value = ''; photos.value = []
}

function onFiles (e) {
  const remain = MAX_PHOTOS - photos.value.length
  if (remain <= 0) { toast(`사진은 최대 ${MAX_PHOTOS}장까지예요`); e.target.value = ''; return }
  for (const f of [...e.target.files].slice(0, remain)) {
    photos.value.push({ file: f, preview: URL.createObjectURL(f) })
  }
  if (e.target.files.length > remain) toast(`사진은 최대 ${MAX_PHOTOS}장까지예요`)
  e.target.value = ''
}

async function submit () {
  if (!canSubmit.value) return
  if (!auth.isLoggedIn) {
    toast('로그인하면 후기를 남길 수 있어요')
    router.push({ name: 'login', query: { redirect: '/map' } })
    return
  }
  submitting.value = true
  try {
    const imageUrls = photos.value.length
      ? await ReviewApiService.uploadPhotos(photos.value.map(p => p.file))
      : []
    await ReviewApiService.create(props.place.id, {
      rating: star.value || null,
      congestionReport: TO_LEVEL[crowdVote.value] ?? null,
      content: text.value.trim() || null,
      imageUrls
    })
    resetForm()
    await load()
    emit('changed')             // 부모가 상세를 다시 읽어 별점 요약을 갱신한다
    toast('후기가 등록됐어요')
  } catch (err) {
    toast(err?.message ?? '후기 등록에 실패했어요')
  } finally {
    submitting.value = false
  }
}

async function removeReview (r) {
  try {
    await ReviewApiService.remove(r.id)
    await load()
    emit('changed')
    toast('후기를 삭제했어요')
  } catch (err) {
    toast(err?.message ?? '삭제에 실패했어요')
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

    <div class="rv-c">
      <button v-for="c in ['calm', 'mid', 'busy']" :key="c" :class="[c, { on: crowdVote === c }]"
        @click="crowdVote = crowdVote === c ? '' : c">{{ CROWD_KO[c] }}</button>
    </div>

    <div class="rv-phrow">
      <span v-for="(p, i) in photos" :key="i" class="rv-pht">
        <img :src="p.preview" alt="첨부한 사진">
        <button class="del" aria-label="사진 삭제" @click="photos.splice(i, 1)">×</button>
      </span>
      <button v-if="photos.length < MAX_PHOTOS" class="rv-phadd" @click="fileInput.click()">
        사진<br>{{ photos.length }}/{{ MAX_PHOTOS }}
      </button>
      <input ref="fileInput" type="file" accept="image/*" multiple style="display:none" @change="onFiles">
    </div>

    <div class="rv-in">
      <input v-model="text" placeholder="한 줄 남기기 (선택)" maxlength="60" @keydown.enter="submit">
      <button :disabled="!canSubmit" @click="submit">{{ submitting ? '등록 중…' : '등록' }}</button>
    </div>

    <template v-if="items.length">
      <div class="rv-sum">
        <StarIcon filled :size="15" />
        <!-- 별점 후기가 없으면 평균을 만들어내지 않는다 -->
        <span class="avg">{{ ratingAvg != null ? ratingAvg.toFixed(1) : '-' }}</span>
        <span class="cnt">후기 {{ totalElements }}</span>
      </div>
      <div class="rv-bar">
        <div v-for="c in ['calm', 'mid', 'busy']" :key="c" class="rv-b">
          <span :style="{ color: `var(--${c})` }">{{ CROWD_KO[c] }}</span>
          <span class="bg">
            <i :style="{ width: Math.round(counts[c] / total * 100) + '%', background: `var(--${c})` }"></i>
          </span>
          <b>{{ counts[c] }}</b>
        </div>
      </div>

      <div v-for="r in items" :key="r.id" class="rv-i">
        <div class="rv-h">
          <span class="rv-av">{{ (r.nickname ?? '여')[0] }}</span>
          <!-- 탈퇴 등으로 닉네임이 없으면(null) 익명 표기로 대체한다 -->
          <span class="rv-nm">{{ r.nickname ?? `여행자${r.userId}` }}</span>
          <span class="rv-dt">{{ dateOf(r.createdAt) }} 작성</span>
          <button v-if="myId === r.userId" class="rv-del" aria-label="내 후기 삭제"
            @click="removeReview(r)">삭제</button>
        </div>
        <div class="rv-mt">
          <template v-if="r.rating"><StarIcon v-for="n in 5" :key="n" :filled="n <= r.rating" :size="12" /></template>
          <span v-if="FROM_LEVEL[r.congestionReport]" class="bdg"
            :style="{ background: `var(--${FROM_LEVEL[r.congestionReport]}-bg)`, color: `var(--${FROM_LEVEL[r.congestionReport]})`, fontSize: '10px', padding: '2px 8px' }">
            {{ CROWD_KO[FROM_LEVEL[r.congestionReport]] }}
          </span>
        </div>
        <div v-if="r.content" class="rv-tx">{{ r.content }}</div>
        <div v-if="r.imageUrls && r.imageUrls.length" class="rv-imgs">
          <img v-for="(p, pi) in r.imageUrls" :key="pi" :src="absUrl(p)" :alt="`후기 사진 ${pi + 1}`"
            title="클릭하면 크게 보기" @click="emit('open-photo', { photos: r.imageUrls.map(absUrl), index: pi })">
        </div>
      </div>

      <button v-if="pageNo + 1 < totalPages" class="rvchip"
        style="justify-content:center;margin-top:8px" :disabled="loading" @click="load(false)">
        <span class="ct">후기 {{ totalElements - items.length }}개 더보기</span>
      </button>
    </template>

    <div v-else class="rv-none">아직 후기가 없어요.<br>첫 방문 후기를 남겨보세요.</div>
  </div>
</template>

<style scoped>
.rv-del{margin-left:auto;background:none;border:0;color:var(--tx3);font-size:11px}
.rv-del:hover{color:#b02c2c}
</style>
