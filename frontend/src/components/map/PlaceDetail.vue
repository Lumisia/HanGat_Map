<script setup>
/* MAP_007 장소 상세 — 상세 화면과 후기 화면을 한 패널 안에서 전환한다 */
import { ref, computed, watch } from 'vue'
import StarIcon from './StarIcon.vue'
import ReviewSection from './ReviewSection.vue'
import { state, toggleFav, isFav, toast } from '@/stores/mapStore'

import { crowd, tier, tierKo, rank30, bestDay, CROWD_KO } from '@/utils/crowd'
import { at, fmtK } from '@/utils/date'
import { wxOf, wxIcon } from '@/utils/weather'
import { dist, won } from '@/utils/geo'
import { copyText } from '@/utils/clipboard'
import { shareToKakao, preloadKakao } from '@/composables/useKakaoShare'
import MapPlaceService from '@/services/map/MapPlaceService'
import ReviewApiService, { LEVEL_TO_KEY, absUrl } from '@/services/map/ReviewApiService'

const props = defineProps({ place: { type: Object, required: true } })
const emit = defineEmits(['close', 'open-place', 'open-photo'])

const view = ref('info')
const hint = ref('')
/* 공유 시트 열림 - loadDetail(즉시 watch)이 닫으므로 선언이 이 위에 있어야 한다 */
const shareOpen = ref(false)
/** 휴무일·입장료는 상세 API에만 있다. 못 받아오면 null - 해당 줄만 안 보인다 */
const detail = ref(null)

const s = computed(() => props.place)
const c = computed(() => crowd(s.value, state.di))
const t = computed(() => tier(c.value))

/* 리드 문장은 그 장소의 30일 예보 안에서의 순위만 말한다 — 다른 장소와 비교하지 않는다 */
const rankText = computed(() => {
  const r = rank30(s.value, state.di)
  return r <= 8 ? '한산한 편' : r >= 23 ? '혼잡한 편' : null
})
const best = computed(() => bestDay(s.value, 0, 30))
const tipText = computed(() => {
  if (c.value == null) return ''
  if (best.value.k === state.di) return null
  const g = c.value - best.value.c
  return g >= 25 ? '훨씬 한산한' : g >= 12 ? '꽤 한산한' : '조금 더 한산한'
})

/* 선택일을 가운데 두고 7일 — 범위를 넘지 않게 시작점을 당긴다.
   날씨 없는 날도 카드는 유지한다(혼잡은 30일 커버) - 그 날은 기온 대신 '예보 전'으로 표시 (2026-09-02 C안) */
const week = computed(() => {
  const st = Math.max(0, Math.min(state.di - 1, 23))
  return Array.from({ length: 7 }, (_, j) => {
    const k = st + j, d = at(k), w = wxOf(k), cc = crowd(s.value, k)
    return { k, d, w, cc, t: tier(cc), ko: tierKo(cc), label: `${d.getMonth() + 1}/${d.getDate()} ${'일월화수목금토'[d.getDay()]}` }
  })
})

/** 날씨가 제공되는 마지막 날짜 라벨. 창에 날씨 없는 카드가 있을 때 캡션으로 안내한다 */
const wxUntil = computed(() => {
  let last = -1
  for (let i = 0; i < 30; i++) if (wxOf(i)) last = i
  if (last < 0) return null
  const d = at(last)
  return `${d.getMonth() + 1}/${d.getDate()}`
})
const weatherGap = computed(() => week.value.some(w => !w.w))

/**
 * 입장료 배지. 모르면 배지를 안 그린다 -
 * 전에는 값이 없을 때 '무료'로 떨어져 2,138곳 전부가 무료로 보였다(실제 무료는 11곳).
 */
const feeBadge = computed(() => {
  if (s.value.fee != null) return `입장 ${won(s.value.fee)}원`   // 목업
  if (detail.value?.free) return '입장 무료'
  return null
})
/** 배지가 하나도 없으면 줄을 통째로 없앤다 - 빈 div 가 여백으로 남는다 */
const hasAmen = computed(() =>
  feeBadge.value || s.value.park != null || s.value.wc != null || s.value.in)

/** 착한가격 대표 메뉴 - 상세 overview가 "대표메뉴: ..." 형태일 때만 (관광지 소개문과 구분) */
const menuRows = computed(() => {
  const o = detail.value?.overview
  if (!o || !o.startsWith('대표메뉴:')) return []
  return o.replace(/^대표메뉴:\s*/, '').split(' · ').map(item => {
    const m = item.match(/^(.*?)\s*([\d,]+원)$/)
    return m ? { n: m[1], p: m[2] } : { n: item, p: '' }
  })
})

/** 관광공사(KTO) 공식 사진 - 상세에 싣는 사진은 이것뿐이다 */
const ktoImages = computed(() => detail.value?.images ?? [])
/* 후기 요약은 상세 API(places.rating_avg 비정규화)가 준다 - localStorage 데모 아님 */
const reviewCount = computed(() => detail.value?.reviewCount ?? 0)
const ratingAvg = computed(() => detail.value?.ratingAvg ?? null)
/** 하단 미리보기용 최근 3건 */
const previewReviews = ref([])
const rvDate = iso => { const d = new Date(iso); return `${d.getFullYear()}.${d.getMonth() + 1}.${d.getDate()}` }

watch(() => props.place.n, loadDetail, { immediate: true })

async function loadDetail() {
  view.value = 'info'
  hint.value = ''
  shareOpen.value = false
  detail.value = null
  // 목업 모드는 id 가 없다
  if (s.value.id == null) return
  const id = s.value.id
  const [d, rv] = await Promise.all([
    MapPlaceService.getDetail(id),
    ReviewApiService.getReviews(id, 0).catch(() => null)
  ])
  // 응답이 늦게 와도 그새 다른 장소를 열었으면 버린다
  if (s.value.id === id) {
    detail.value = d
    previewReviews.value = rv?.content.slice(0, 3) ?? []
  }
}

function jumpToBest() {
  if (best.value.k !== state.di) state.di = best.value.k
}

/** 한산한 날 찾기 — 앞으로 2주 안에서 */
function findCalmDay() {
  if (c.value == null) {
    hint.value = '<span style="color:var(--tx3)">혼잡 예보가 제공되지 않는 장소예요.</span>'
    return
  }
  const b = bestDay(s.value, state.di, 14)
  const g = c.value - b.c, word = g >= 25 ? '훨씬' : g >= 12 ? '꽤' : '조금'
  hint.value = b.k === state.di
    ? '<span style="color:var(--calm)">앞으로 2주 중엔 오늘이 가장 한산해요.</span>'
    : `<span style="color:var(--calm)"><b>${fmtK(at(b.k))}</b>로 옮기면 ${word} 한산해져요.</span>`
  if (b.k !== state.di) setTimeout(() => { state.di = b.k }, 1000)
}

const nearby = ref([])
function findNearby() {
  nearby.value = state.layers.spot.filter(x => x.n !== s.value.n)
    .map(x => ({ s: x, c: crowd(x, state.di), d: dist(s.value, x) }))
    .filter(o => o.d < 12 && o.c != null && o.c < 40)
    .sort((a, b) => a.c - b.c).slice(0, 3)
  hint.value = nearby.value.length ? '' : '<span style="color:var(--tx3)">반경 12km 안에는 한산한 대안이 없어요.</span>'
}

async function copyAddr() {
  toast(await copyText(s.value.addr)
    ? '주소를 복사했어요'
    : '복사가 막혀 있어요 — 주소를 드래그해 직접 복사해 주세요')
}

/* ── 친구에게 공유 (MAP_007) ── */
/** 시트를 열 때 카카오 SDK를 미리 받아둔다 - 클릭 시 팝업이 활성화 안에서 열리게 */
function toggleShare() {
  shareOpen.value = !shareOpen.value
  if (shareOpen.value) preloadKakao()
}

/** 공유용 딥링크(?place=id) - 목업 장소는 id가 없어 현재 화면 주소로 대신한다 */
const shareUrl = computed(() =>
  s.value.id != null ? `${location.origin}/map?place=${s.value.id}` : location.href)

async function copyLink() {
  shareOpen.value = false
  toast(await copyText(shareUrl.value)
    ? '링크를 복사했어요 — 붙여넣으면 이 장소가 바로 열려요'
    : '복사가 막혀 있어요 — 주소창의 주소를 직접 복사해 주세요')
}

/* 팝업은 클릭 활성화 안에서 열려야 한다 - await 하면 빈 창이 뜬다.
   그래서 시트를 열 때 preloadKakao()로 미리 받아두고 여기선 동기로 연다.
   아직 로드 전이면(느린 회선) 열지 않고 링크 복사로 안내한다 */
function shareKakao() {
  shareOpen.value = false
  const opened = shareToKakao({
    title: s.value.n,
    description: s.value.addr || [s.value.c, s.value.r].filter(Boolean).join(' · '),
    imageUrl: ktoImages.value[0]?.url ?? null,
    url: shareUrl.value,
  })
  if (!opened) toast('카카오톡 준비 중이에요 — 잠시 후 다시 누르거나 링크 복사를 이용해 주세요')
}

/** OS 공유 시트(모바일 브라우저 대부분, 데스크톱은 일부만 지원) */
const canNative = typeof navigator.share === 'function'
async function shareNative() {
  shareOpen.value = false
  try {
    await navigator.share({ title: s.value.n, text: `${s.value.n} — 한갓지도`, url: shareUrl.value })
  } catch { /* 사용자가 시트를 닫은 것 - 정상 */ }
}

</script>

<template>
  <div class="fl pop on">
    <div v-show="view === 'info'">
      <div class="poh">
        <div style="flex:1">
          <h4>{{ s.n }}</h4>
          <div class="sub">{{ s.c }} · {{ s.r }}</div>
        </div>
        <!-- MAP_009 찜 -->
        <button class="fav" :class="{ on: isFav(s.n) }" aria-label="찜하기" @click="toggleFav(s.n)">♥</button>
        <div class="share-wrap">
          <button class="share" :aria-expanded="shareOpen" @click="toggleShare">공유하기</button>
          <div v-if="shareOpen" class="share-sheet">
            <button @click="shareKakao"><i class="si ka"></i>카카오톡</button>
            <button @click="copyLink"><i class="si cp"></i>링크 복사</button>
            <button v-if="canNative" @click="shareNative"><i class="si nt"></i>더보기</button>
          </div>
        </div>
        <button class="pox" @click="emit('close')">×</button>
      </div>

      <button class="rvchip" @click="view = 'rv'">
        <template v-if="reviewCount">
          <template v-if="ratingAvg != null">
            <StarIcon filled :size="15" /><span class="sc">{{ ratingAvg.toFixed(1) }}</span>
          </template>
          <span class="ct">후기 {{ reviewCount }}</span>
        </template>
        <template v-else>
          <StarIcon :size="15" />
          <span class="ct" style="font-weight:700;color:var(--tx2)">첫 후기를 남겨보세요</span>
        </template>
        <span class="ar">›</span>
      </button>

      <!-- 장소 사진(MAP-08): 관광공사(KTO) 공식 사진만 싣는다.
           사용자 사진은 방문 후기(MAP_008)에서만 올린다 - 상세 직접 업로드(데모)는 2026-09-03 제거 -->
      <div v-if="ktoImages.length" class="pimg">
        <img v-for="(p, i) in ktoImages" :key="p.url" :src="p.thumb" :alt="p.caption || `${s.n} 사진`"
          title="클릭하면 크게 보기" style="cursor:zoom-in"
          @click="emit('open-photo', { photos: ktoImages.map(x => x.url), index: i })">
      </div>
      <div v-if="ktoImages.length && detail?.imageAttribution" class="pimg-src">
        {{ detail.imageAttribution }}
      </div>

      <div class="lead">
        <!-- MAP_004 예외: 예보 미제공 — 없는 데이터는 추측하지 않는다 -->
        <template v-if="c == null">
          <span class="bdg" style="background:var(--none);color:#fff">정보 없음</span>
          &nbsp;이 장소는 혼잡 예보가 제공되지 않아요.
        </template>
        <template v-else>
          <span class="bdg" :style="{ background: `var(--${t})`, color: '#fff' }">{{ tierKo(c) }}</span>
          &nbsp;{{ fmtK(at(state.di)) }} · 이곳의 30일 예보 중에선
          <template v-if="rankText"><b>{{ rankText }}</b>이에요.</template>
          <template v-else>중간쯤이에요.</template>
        </template>
      </div>

      <div v-if="c != null" class="tipbox" @click="jumpToBest">
        <template v-if="!tipText">✓ 30일 중 <b>오늘이 가장 한산</b>해요.</template>
        <template v-else>🕐 <b>{{ fmtK(at(best.k)) }}</b>로 가면 <b>{{ tipText }}</b> 날이에요. 눌러서 옮겨보세요.</template>
      </div>

      <div class="spark" style="margin-bottom:6px"><div class="st"><i></i>날짜별 날씨와 혼잡</div></div>
      <div class="wxrow">
        <div v-for="w in week" :key="w.k" class="wxc" :class="{ on: w.k === state.di }"
          @click="state.di = w.k">
          <div class="wd">{{ w.label }}</div>
          <div class="wi" v-html="w.w ? wxIcon(w.w.k, 27) : ''"></div>
          <!-- 날씨 없는 날은 고장이 아니라 원래 없는 것 - '-' 대신 명시적으로 말한다 -->
          <div v-if="w.w" class="wt">{{ w.w.t }}°</div>
          <div v-else class="wt pre">예보 전</div>
          <!-- 강수확률 30% 이상만 표시. 빈 칸도 같은 높이로 두어 카드 줄이 맞는다 -->
          <div class="wp">
            <template v-if="w.w && w.w.rp >= 30">
              <svg width="7" height="9" viewBox="0 0 8 10" aria-hidden="true"><path d="M4 .4C5.3 2.3 6.8 4 6.8 5.9a2.8 2.8 0 1 1-5.6 0C1.2 4 2.7 2.3 4 .4Z" fill="#2F93E0"/></svg>{{ w.w.rp }}%
            </template>
          </div>
          <!-- 혼잡 바는 핀과 같은 면색(-st) - 글자용 진한 톤을 면에 쓰면 핀과 색이 어긋난다 -->
          <div class="wc" :style="{ background: `var(--${w.t}-st, var(--${w.t}))` }" :title="w.ko"></div>
        </div>
      </div>
      <div v-if="weatherGap && wxUntil" class="wx-note">날씨는 {{ wxUntil }}까지 제공돼요 · 혼잡은 30일 표시</div>

      <!-- 없는 정보(null)는 배지를 그리지 않는다 - '주차 없음'과 '주차 정보 없음'은 다르다 -->
      <div v-if="hasAmen" class="amen">
        <span v-if="feeBadge" class="am">{{ feeBadge }}</span>
        <span v-if="s.park != null" class="am" :class="{ no: !s.park }">주차</span>
        <span v-if="s.wc != null" class="am" :class="{ no: !s.wc }">화장실</span>
        <span v-if="s.in" class="am">실내</span>
      </div>

      <!-- 착한가격 대표 메뉴 (행안부 실데이터) - 메뉴 없는 업소는 섹션 자체를 숨긴다 -->
      <div v-if="menuRows.length" class="menu">
        <div class="mn-h"><i></i>대표 메뉴<span v-if="s.good" class="mn-b">착한가격</span></div>
        <div v-for="m in menuRows" :key="m.n" class="mn-r">
          <span class="mn-n">{{ m.n }}</span><span class="mn-p">{{ m.p }}</span>
        </div>
      </div>

      <!-- 주소(복사) · 운영시간(있을 때만 — 상시 개방은 줄 자체를 표시하지 않음) · 전화 -->
      <div class="pinfo">
        <div class="pi">
          <svg width="13" height="13" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"
            stroke-linecap="round" stroke-linejoin="round"><path d="M12 21s-7-6.1-7-11a7 7 0 0 1 14 0c0 4.9-7 11-7 11Z"/>
            <circle cx="12" cy="10" r="2.6"/></svg>
          <span class="tx">{{ s.addr }}</span>
          <button class="cp" @click="copyAddr">복사</button>
        </div>
        <div v-if="s.hours" class="pi">
          <svg width="13" height="13" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"
            stroke-linecap="round"><circle cx="12" cy="12" r="9"/><path d="M12 7v5l3.2 1.9"/></svg>
          <span class="tx multi">{{ s.hours }}<span class="sub2">운영시간</span></span>
        </div>
        <div v-if="detail?.rest" class="pi">
          <svg width="13" height="13" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"
            stroke-linecap="round"><rect x="3.5" y="5" width="17" height="15.5" rx="2.5"/><path d="M3.5 9.8h17M8 3v3.6M16 3v3.6"/></svg>
          <span class="tx multi">{{ detail.rest }}<span class="sub2">휴무일</span></span>
        </div>
        <!-- 무료는 위 배지가 이미 말한다 - 여기는 요금표가 있을 때만 -->
        <div v-if="detail?.feeText && !detail.free" class="pi">
          <svg width="13" height="13" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"
            stroke-linejoin="round"><path d="M3 9.2V6.5A1.5 1.5 0 0 1 4.5 5h15A1.5 1.5 0 0 1 21 6.5v2.7a2.8 2.8 0 0 0 0 5.6v2.7a1.5 1.5 0 0 1-1.5 1.5h-15A1.5 1.5 0 0 1 3 17.5v-2.7a2.8 2.8 0 0 0 0-5.6Z"/><path d="M14 9.5v5" stroke-dasharray="1.6 2.2"/></svg>
          <span class="tx multi">{{ detail.feeText }}<span class="sub2">입장료</span></span>
        </div>
        <div v-if="s.tel" class="pi">
          <svg width="13" height="13" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"
            stroke-linejoin="round"><path d="M5 4h4l1.6 4-2.2 1.6a13 13 0 0 0 6 6L16 13.4l4 1.6v4a2 2 0 0 1-2.2 2A17 17 0 0 1 3 6.2 2 2 0 0 1 5 4Z"/></svg>
          <span class="tx"><a :href="`tel:${s.tel}`">{{ s.tel }}</a></span>
        </div>
      </div>

      <div class="acts">
        <button class="p1" @click="findCalmDay">한산한 날 찾기</button>
        <button @click="findNearby">근처 대안 보기</button>
      </div>
      <div v-if="hint || nearby.length" class="hint" style="display:block">
        <span v-if="hint" v-html="hint"></span>
        <template v-if="nearby.length">
          근처에 한산한 곳이 있어요 ·
          <template v-for="(o, i) in nearby" :key="o.s.n">
            <a style="color:var(--calm);cursor:pointer;font-weight:700"
              @click="emit('open-place', o.s.n)">{{ o.s.n }}</a><template v-if="i < nearby.length - 1">, </template>
          </template>
        </template>
      </div>

      <!-- 상세 하단 후기 미리보기 (최근 3개, 실 API) -->
      <div class="rvprev">
        <div class="rvp-h"><i></i>방문 후기
          <span v-if="reviewCount" class="rvp-avg">
            <template v-if="ratingAvg != null"><StarIcon filled :size="13" /> {{ ratingAvg.toFixed(1) }} · </template>
            {{ reviewCount }}개
          </span>
        </div>
        <template v-if="previewReviews.length">
          <div v-for="r in previewReviews" :key="r.id" class="rv-i">
            <div class="rv-h">
              <span class="rv-av">{{ (r.nickname ?? '여')[0] }}</span>
              <span class="rv-nm">{{ r.nickname ?? `여행자${r.userId}` }}</span>
              <span class="rv-dt">{{ rvDate(r.createdAt) }} 작성</span>
            </div>
            <div class="rv-mt">
              <template v-if="r.rating"><StarIcon v-for="n in 5" :key="n" :filled="n <= r.rating" :size="12" /></template>
              <span v-if="LEVEL_TO_KEY[r.congestionReport]" class="bdg"
                :style="{ background: `var(--${LEVEL_TO_KEY[r.congestionReport]}-bg)`, color: `var(--${LEVEL_TO_KEY[r.congestionReport]})`, fontSize: '10px', padding: '2px 8px' }">
                {{ CROWD_KO[LEVEL_TO_KEY[r.congestionReport]] }}
              </span>
            </div>
            <div v-if="r.content" class="rv-tx">{{ r.content }}</div>
            <div v-if="r.imageUrls && r.imageUrls.length" class="rv-imgs">
              <img v-for="(p, pi) in r.imageUrls" :key="pi" :src="absUrl(p)" :alt="`후기 사진 ${pi + 1}`"
                title="클릭하면 크게 보기" @click="emit('open-photo', { photos: r.imageUrls.map(absUrl), index: pi })">
            </div>
          </div>
          <button class="rvp-more" @click="view = 'rv'">
            {{ reviewCount > 3 ? `후기 ${reviewCount}개 모두 보기 ›` : '후기 남기기 ›' }}
          </button>
        </template>
        <template v-else>
          <div class="rv-none">아직 후기가 없어요.</div>
          <button class="rvp-more" @click="view = 'rv'">첫 후기 남기기 ›</button>
        </template>
      </div>
    </div>

    <div v-show="view === 'rv'">
      <div class="rvhead">
        <button class="rvback" @click="view = 'info'">‹</button>
        <div style="flex:1"><h4>{{ s.n }}</h4><div class="sub">방문 후기</div></div>
        <button class="pox" @click="emit('close')">×</button>
      </div>
      <ReviewSection :place="s" :rating-avg="ratingAvg"
        @open-photo="p => emit('open-photo', p)" @changed="loadDetail" />
    </div>
  </div>
</template>
