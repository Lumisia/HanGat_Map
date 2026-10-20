<script setup>
/* MAP_007 장소 상세 — 상세 화면과 후기 화면을 한 패널 안에서 전환한다 */
import { ref, computed, watch, nextTick, onBeforeUnmount } from 'vue'
import StarIcon from './StarIcon.vue'
import ReviewSection from './ReviewSection.vue'
import ProfileAvatar from '../common/ProfileAvatar.vue'
import { state, toggleFav, isFav, toast, placeKey } from '@/stores/mapStore'

import { crowd, tier, tierKo, forecastDays, bestDay } from '@/utils/crowd'
import { at, fmtK } from '@/utils/date'
import { wxOf, wxIcon, wxIssuedAt } from '@/utils/weather'
import { weatherBasis } from '@/services/map/MapWeatherService'
import { dist, won } from '@/utils/geo'
import { copyText } from '@/utils/clipboard'
import { shareToKakao, preloadKakao } from '@/composables/useKakaoShare'
import { goodPriceSourceLine } from '@/utils/dataSources'
import { introOf, paragraphsOf } from '@/utils/intro'
import MapPlaceService from '@/services/map/MapPlaceService'
import ReviewApiService, { absUrl } from '@/services/map/ReviewApiService'

const props = defineProps({ place: { type: Object, required: true } })
const emit = defineEmits(['close', 'open-place', 'open-photo'])

const view = ref('info')
const hint = ref('')
/* 공유 시트 열림 - loadDetail(즉시 watch)이 닫으므로 선언이 이 위에 있어야 한다 */
const shareOpen = ref(false)
/** 휴무일·입장료는 상세 API에만 있다. 못 받아오면 null - 해당 줄만 안 보인다 */
const detail = ref(null)

/* 코스 경유지가 관광지 레이어에 없으면(식당·카페·숙소 - 첫 진입엔 관광지만 받는다) CourseBridge 가 {id,n,x,y} 만 든 대체 객체를 넘긴다.
   상세 응답은 목록 응답의 상위 집합이라 거기서 업종·권역·주소·태그를 채운다. 대체 객체의 표식은 c(업종)가 없는 것 -
   레이어에서 온 장소는 c 가 항상 있다. 채워지기 전엔 아래 템플릿이 빈 칸·undefined 를 그리지 않게 각 줄을 방어한다(최종점검 #13) */
const s = computed(() => (props.place.c === undefined && detail.value?.place) ? { ...detail.value.place, ...props.place } : props.place)
const c = computed(() => crowd(s.value, state.di))
const t = computed(() => tier(c.value))

/* 혼잡 UI(리드·팁·주간 막대·한산한 날 찾기)는 예보가 있는 곳과 관광지에만 그린다.
   식당·카페·숙소는 관광공사 예측 대상이 아니라 "예보 없음" 설명조차 잡음이다 - 날씨 줄만 남긴다.
   관광지는 예보가 없어도 왜 없는지 말해야 하므로 리드를 유지한다 (MAP_004) */
const hasForecast = computed(() => Array.isArray(s.value.series) && s.value.series.some(v => v != null))
const crowdUi = computed(() => hasForecast.value || s.value.cat === 'TOURIST')
/* 값이 없는(c == null) 이유는 셋이고 문구가 달라야 한다(2026-09-13, 최종점검 #4):
   ① 예보 API 자체를 못 받음(forecastDays 0) ② 이 장소는 예보가 있는데 선택 날짜가 범위 밖(outOfRange)
   ③ 원래 관광공사 예측 대상이 아님. 전엔 ①②도 ③ 문구("예측 대상이 아니라")로 나가 성산일출봉이 10월 초에 '대상 아님'이 됐다 */
const forecastDown = computed(() => state.forecastDays === 0)
const outOfRange = computed(() => hasForecast.value && c.value == null)
const untilText = computed(() => (state.forecastUntil >= 0 ? fmtK(at(state.forecastUntil)) : null))

/* 리드 문장은 그 장소의 30일 예보 안에서의 순위만 말한다 — 다른 장소와 비교하지 않는다 */
/* 리드 문장 = 선택한 날의 혼잡 단계를 쉬운 말로("9월 15일 예보는 혼잡한 편이에요"). 전엔 그 장소의 예보 안 순위를 말했는데
   ("이곳의 21일 예보 중에선 한산한 편") 읽기 어려웠다(2026-09-15 후경). "언제가 더 한산한가"는 아래 팁 박스가 말한다 */
const tierText = computed(() => ({ calm: '한산한 편', mid: '보통', busy: '혼잡한 편' })[t.value] ?? '')
/** 팁 박스의 'N일 중' - 실제로 예보가 있는 날 수(21~22일). '30일'로 적으면 사실과 다르다(#18) */
const forecastLen = computed(() => forecastDays(s.value))
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
    // 권역을 모르면 날씨를 비운다 - wxOf 는 권역이 없으면 북부로 대체하는데, 동부 식당에 북부 날씨를 보여주면 틀린 정보다
    const k = st + j, d = at(k), w = s.value.r ? wxOf(k, s.value.r) : null, cc = crowd(s.value, k)
    return { k, d, w, cc, t: tier(cc), ko: tierKo(cc), label: `${d.getMonth() + 1}/${d.getDate()} ${'일월화수목금토'[d.getDay()]}` }
  })
})

/** 날씨가 제공되는 마지막 날짜 라벨. 창에 날씨 없는 카드가 있을 때 캡션으로 안내한다 */
const wxUntil = computed(() => {
  let last = -1
  for (let i = 0; i < 30; i++) if (wxOf(i, s.value.r)) last = i
  if (last < 0) return null
  const d = at(last)
  return `${d.getMonth() + 1}/${d.getDate()}`
})
const weatherGap = computed(() => week.value.some(w => !w.w))
const hasWx = computed(() => week.value.some(w => w.w))

/* 날씨는 이 장소 권역의 기상청 격자 값이다 - 어느 권역 기준이고 언제 발표된 예보인지 적는다 (MAP_006).
   발표 시각은 선택한 날짜 예보의 것(단기 05시·중기 18시가 섞인다). 적재분에만 있어 라이브 폴백이면 권역만 적는다 */
const wxSource = computed(() => {
  // 4일째부터는 중기예보(제주 한 지점) - 권역 이름을 적으면 거짓이라 '제주 기준(중기예보)'(최종점검 #42)
  const basis = weatherBasis(state.di, s.value.r)
  if (!basis) return '출처: 기상청'          // 권역을 아직 모르면(대체 객체) 'undefined 기준'을 찍지 않는다
  const where = basis === '제주' ? '제주 기준(중기예보)' : `${basis} 기준`
  const issued = wxIssuedAt(s.value.r, state.di)
  if (!issued) return `출처: 기상청 · ${where}`
  const d = new Date(issued)
  const hm = `${String(d.getHours()).padStart(2, '0')}:${String(d.getMinutes()).padStart(2, '0')}`
  return `출처: 기상청 · ${where} · ${d.getMonth() + 1}/${d.getDate()} ${hm} 발표`
})

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

/* 관광공사 소개글 (MAP_008) - 사진 아래 2줄로 접어 두고 더보기로 펼친다. 장소가 바뀌면 다시 접는다 */
const intro = computed(() => introOf(detail.value?.overview))
const introOpen = ref(false)
const introParas = computed(() => paragraphsOf(intro.value))
/* '더보기'는 2줄로 접은 글이 실제로 잘렸을 때만 - 전엔 70자 기준 추정이라 글자 크기·패널 폭이 바뀌면 잘렸는데 버튼이 없거나 그 반대가 됐다 */
const introEl = ref(null)
const introCut = ref(false)
watch([intro, introOpen], () => nextTick(() => {
  const el = introEl.value
  introCut.value = !introOpen.value && !!el && el.scrollHeight > el.clientHeight + 1
}), { flush: 'post' })

/** 관광공사(KTO) 공식 사진 - 상세에 싣는 사진은 이것뿐이다 */
const ktoImages = computed(() => detail.value?.images ?? [])
/** 축소본(_image3_)이 없는 사진(표본 40장 중 2장)은 원본으로 되돌린다. 원본까지 실패하면 그대로 둔다 - 무한 재시도 방지 */
function onThumbError (e, p) {
  if (e.target.src !== p.url) e.target.src = p.url
}
/* 후기 요약은 상세 API(places.rating_avg 비정규화)가 준다 - localStorage 데모 아님.
   상세 API 가 죽었을 땐 후기 목록의 총 건수로 보완한다 - 둘 중 하나만 살아 있어도 "첫 후기를 남겨보세요"라고 거짓말하지 않게 */
const reviewCount = computed(() => detail.value?.reviewCount ?? reviewPage.value?.totalElements ?? 0)
const ratingAvg = computed(() => detail.value?.ratingAvg ?? null)
/** 후기 첫 페이지 원본 - ReviewSection 에 그대로 넘겨 같은 요청을 두 번 보내지 않는다. null = 아직 안 받았거나 실패 */
const reviewPage = ref(null)
/** 후기 목록 요청이 실패했다 - "아직 후기가 없어요"와 구분해 다시 시도를 보여준다(최종점검 #23) */
const reviewFailed = ref(false)
/** 상세·후기 둘 다 아직이면 칩에 건수도 "첫 후기" 문구도 내지 않는다(로딩 중 깜빡임·장애 중 거짓 빈 상태 방지) */
const reviewsKnown = computed(() => detail.value != null || reviewPage.value != null)
/** 하단 미리보기용 최근 3건 */
const previewReviews = ref([])
const rvDate = iso => { const d = new Date(iso); return `${d.getFullYear()}.${d.getMonth() + 1}.${d.getDate()}` }

/* 감시 키는 id(placeKey) - 이름으로 보면 동명 장소(카페 A→B, 관광지 보롬왓→카페 보롬왓)로 바꿔도 안 깨어나
   이전 장소의 사진·소개·메뉴·휴무·후기가 그대로 남았다. MapView 가 :key 로 패널을 새로 만들기도 하지만
   같은 인스턴스에서 place 가 바뀌는 경로가 생겨도 안전하게 여기서도 본다 */
watch(() => placeKey(props.place), loadDetail, { immediate: true })

/** 장소가 바뀌었을 때 - 뷰·힌트·펼침 상태를 초기화하고 처음부터 받는다 */
async function loadDetail() {
  view.value = 'info'
  hint.value = ''
  shareOpen.value = false
  introOpen.value = false
  detail.value = null
  await fetchDetail()
}

/** 상세·후기 첫 페이지만 다시 받는다. 후기 등록·삭제 뒤(@changed)에 쓴다 -
    전엔 loadDetail 을 그대로 불러서 후기 뷰가 정보 뷰로 튕기고, detail 을 비운 한순간 칩이 '첫 후기를 남겨보세요'로 깜빡였다(2026-09-13, 최종점검 #7).
    여기선 뷰를 건드리지 않고 detail 도 비우지 않는다 - 응답이 오면 통째로 바꾼다 */
async function fetchDetail() {
  // 목업 모드는 id 가 없다
  if (s.value.id == null) return
  const id = s.value.id
  const [d, rv] = await Promise.all([
    MapPlaceService.getDetail(id),
    ReviewApiService.getReviews(id, 0).then(page => ({ page, failed: false }), () => ({ page: null, failed: true }))
  ])
  // 응답이 늦게 와도 그새 다른 장소를 열었으면 버린다
  if (s.value.id === id) {
    detail.value = d
    reviewFailed.value = rv.failed
    reviewPage.value = rv.page
    previewReviews.value = rv.page?.content.slice(0, 3) ?? []
  }
}

function jumpToBest() {
  if (best.value.k !== state.di) state.di = best.value.k
}

/* 찾기는 안내 문장을 먼저 보여주고 1초 뒤에 날짜를 옮긴다. 그 1초 안에 패널을 닫거나 다른 장소를 열면
   예약된 이동이 그대로 실행돼 전역 날짜가 이유 없이 튀었다 - 타이머를 보관해 두고 언마운트(닫기·장소 전환) 때 취소한다(최종점검 #20) */
let calmTimer = null
function jumpLater(k) {
  clearTimeout(calmTimer)
  calmTimer = setTimeout(() => { state.di = k }, 1000)
}
onBeforeUnmount(() => clearTimeout(calmTimer))

/** 한산한 날 찾기 — 앞으로 2주 안에서 */
function findCalmDay() {
  if (c.value == null) {
    // 예보는 있는데 선택 날짜가 범위 밖이면 예보가 있는 날 중 최저일로 옮긴다 - '대상 아님'이라고 막지 않는다
    if (outOfRange.value && best.value.c != null) {
      hint.value = `<span style="color:var(--calm)">이 날짜는 아직 예보가 없어 예보가 있는 날 중에서 찾았어요 · <b>${fmtK(at(best.value.k))}</b>로 옮길게요.</span>`
      jumpLater(best.value.k)
      return
    }
    hint.value = forecastDown.value
      ? '<span style="color:var(--tx3)">혼잡 예보를 불러오지 못해 지금은 찾을 수 없어요 · 새로고침해 주세요.</span>'
      : '<span style="color:var(--tx3)">관광공사 혼잡 예측 대상이 아니라 예보가 없는 장소예요.</span>'
    return
  }
  // 요구사항(155행)대로 '오늘~+14일'에서 찾는다 - 선택한 날부터 뒤지던 것을 2026-09-13 결정(가)으로 오늘 기준에 맞춤.
  // 팁 박스(오늘~30일)와 기준이 같아져 두 기능이 서로 다른 날을 가리키지 않는다
  const b = bestDay(s.value, 0, 15)
  if (b.k === state.di) {
    hint.value = `<span style="color:var(--calm)">앞으로 2주 중엔 ${dayWord(state.di)}이 가장 한산해요.</span>`
    return
  }
  if (b.c >= c.value) {
    // 보고 있는 날(2주 밖일 수 있음)이 2주 안 최저보다 한산하면 옮기지 않는다 - 더 붐비는 날로 데려가지 않게
    hint.value = `<span style="color:var(--calm)">앞으로 2주 안엔 ${dayWord(state.di)}보다 한산한 날이 없어요 · 2주 중 최저는 <b>${fmtK(at(b.k))}</b>이에요.</span>`
    return
  }
  const g = c.value - b.c, word = g >= 25 ? '훨씬' : g >= 12 ? '꽤' : '조금'
  hint.value = `<span style="color:var(--calm)"><b>${fmtK(at(b.k))}</b>로 옮기면 ${word} 한산해져요.</span>`
  jumpLater(b.k)
}

/** '오늘' 또는 '9월 22일' - 선택한 날이 오늘이 아닌데 '오늘'이라고 부르던 문구(#19)를 고친다 */
const dayWord = k => (k === 0 ? '오늘' : fmtK(at(k)))

/* 근처 대안 - 버튼을 누른 뒤엔 날짜(state.di)가 바뀔 때마다 다시 계산한다(computed).
   버튼 누른 순간의 스냅샷(ref)으로 두면 7일 카드·달력·'한산한 날 찾기'로 날짜를 옮긴 뒤에도 이전 날짜 기준 3곳이 남았다 -
   가마오름 9/13 기준 3곳이 9/14 화면에 그대로(실제 9/14 대안은 전부 다른 곳). 2026-09-13 */
const nearbyOn = ref(false)
const nearby = computed(() => {
  if (!nearbyOn.value) return []
  return state.layers.spot.filter(x => placeKey(x) !== placeKey(s.value))   // 자기 자신만 뺀다 - 동명 다른 장소는 대안 후보
    .map(x => ({ s: x, c: crowd(x, state.di), d: dist(s.value, x) }))
    .filter(o => o.d < 12 && o.c != null && o.c < 40)
    .sort((a, b) => a.c - b.c).slice(0, 3)
})
function findNearby() { nearbyOn.value = true; hint.value = '' }

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
  try {
    const opened = shareToKakao({
      title: s.value.n,
      description: s.value.addr || [s.value.c, s.value.r].filter(Boolean).join(' · '),
      imageUrl: ktoImages.value[0]?.url ?? null,
      url: shareUrl.value,
    })
    if (!opened) toast('카카오톡 준비 중이에요 — 잠시 후 다시 누르거나 링크 복사를 이용해 주세요')
  } catch (e) {
    // SDK가 던지는 경우(템플릿 검증 실패·도메인 미등록 등) - 조용히 죽지 않고 대안을 안내한다
    console.error('카카오톡 공유 실패', e)
    toast('카카오톡 공유를 열지 못했어요 — 링크 복사를 이용해 주세요')
  }
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
          <div class="sub">
            <span>{{ [s.c, s.r].filter(Boolean).join(' · ') }}</span>
            <!-- 전체 상세 페이지(/places/:id)로 - 그 페이지의 '지도에서 보기'와 짝(2026-09-17 사용자 요청).
                 id 없는 목업·검색 임시 객체엔 안 그린다 -->
            <RouterLink v-if="s.id != null" class="more" :to="`/places/${s.id}`">자세히 보기 ›</RouterLink>
          </div>
        </div>
        <!-- MAP_009 찜 -->
        <button class="fav" :class="{ on: isFav(s) }" :aria-pressed="isFav(s)" aria-label="찜하기" @click="toggleFav(s)">♥</button>
        <div class="share-wrap">
          <!-- 글자 대신 공유 아이콘(점 셋을 잇는 모양) - 낭독기·마우스 툴팁은 '공유하기'(2026-09-15 후경) -->
          <button class="share" :aria-expanded="shareOpen" aria-label="공유하기" title="공유하기" @click="toggleShare">
            <svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2.2"
              stroke-linecap="round" stroke-linejoin="round" aria-hidden="true">
              <circle cx="18" cy="5" r="3" /><circle cx="6" cy="12" r="3" /><circle cx="18" cy="19" r="3" />
              <path d="M8.6 13.5l6.8 4M15.4 6.5l-6.8 4" />
            </svg>
          </button>
          <div v-if="shareOpen" class="share-sheet">
            <!-- 항목 아이콘: 카카오톡 말풍선(노랑 바탕) · 링크 사슬 · OS 공유(상자+화살표). 색 상자만 있던 것을 그림으로(2026-09-15 후경) -->
            <button @click="shareKakao"><i class="si ka"><svg viewBox="0 0 24 24" width="13" height="13" aria-hidden="true">
              <path fill="#191919" d="M12 3C6.5 3 2 6.6 2 11c0 2.8 1.8 5.2 4.6 6.6L5.7 21c-.1.3.3.6.6.4l4.2-2.8c.5.1 1 .1 1.5.1 5.5 0 10-3.6 10-8S17.5 3 12 3Z"/></svg></i>카카오톡</button>
            <button @click="copyLink"><i class="si cp"><svg viewBox="0 0 24 24" width="13" height="13" fill="none" stroke="currentColor" stroke-width="2.4" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true">
              <path d="M10 13a5 5 0 0 0 7.1 0l2.8-2.8a5 5 0 0 0-7.1-7.1L11.5 4.4"/><path d="M14 11a5 5 0 0 0-7.1 0l-2.8 2.8a5 5 0 0 0 7.1 7.1l1.3-1.3"/></svg></i>링크 복사</button>
            <button v-if="canNative" @click="shareNative"><i class="si nt"><svg viewBox="0 0 24 24" width="13" height="13" fill="none" stroke="currentColor" stroke-width="2.4" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true">
              <path d="M4 12v7a2 2 0 0 0 2 2h12a2 2 0 0 0 2-2v-7"/><path d="M16 6l-4-4-4 4"/><path d="M12 2v13"/></svg></i>더보기</button>
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
        <template v-else-if="!reviewsKnown">
          <StarIcon :size="15" />
          <span class="ct" style="font-weight:700;color:var(--tx2)">후기</span>
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
        <!-- width/height 는 공사 사진 비율(3:2)의 96px 칸 - 받기 전에 자리를 잡아 띠가 흔들리지 않는다. lazy: 띠 밖 사진은 스크롤할 때 -->
        <img v-for="(p, i) in ktoImages" :key="p.url" :src="p.thumb" :alt="p.caption || `${s.n} 사진`"
          width="144" height="96" loading="lazy"
          title="클릭하면 크게 보기" style="cursor:zoom-in"
          @error="onThumbError($event, p)"
          @click="emit('open-photo', { photos: ktoImages.map(x => x.url), index: i,
            alts: ktoImages.map((x, j) => x.caption || `${s.n} 사진 ${j + 1}`), source: detail?.imageAttribution ?? '' })">
      </div>
      <div v-if="ktoImages.length && detail?.imageAttribution" class="pimg-src">
        {{ detail.imageAttribution }}
      </div>

      <!-- 관광공사 소개글 (MAP_008) - 메뉴 섹션과 같은 제목 줄, 출처는 제목 오른쪽(사진 출처와 두 줄 연달아 안 나오게).
           기본 2줄로 접어 혼잡 정보가 아래로 밀리지 않게 하고 더보기로 펼친다 -->
      <div v-if="intro" class="intro">
        <div class="intro-h"><i></i>소개<span class="intro-src">ⓒ한국관광공사</span></div>
        <!-- 접힘: 한 덩어리를 2줄로 클램프. 펼침: 문장 3개씩 문단(글자는 원문 그대로, 표시만 나눔) -->
        <p v-if="!introOpen" ref="introEl" class="intro-t clamp">{{ intro }}</p>
        <div v-else class="intro-t"><p v-for="(pg, i) in introParas" :key="i" class="intro-p">{{ pg }}</p></div>
        <button v-if="introOpen || introCut" type="button" class="intro-more" :aria-expanded="introOpen"
          @click="introOpen = !introOpen">{{ introOpen ? '접기 ‹' : '더보기 ›' }}</button>
      </div>

      <div v-if="s.closed || crowdUi" class="lead">
        <!-- 폐업: 원천 목록에서 두 번 연속 빠진 장소(PlacePresenceReconciler). 핀은 없고 찜·코스·공유 링크로만 열린다 -->
        <template v-if="s.closed">
          <span class="bdg" style="background:var(--busy);color:#fff">폐업</span>
          &nbsp;폐업했거나 관광 정보에서 삭제된 장소예요. 찜·코스에는 그대로 남아 있어요.
        </template>
        <!-- MAP_004 예외: 예보 미제공 — 없는 데이터는 추측하지 않되, '왜 없는지'는 세 갈래로 정확히 말한다 -->
        <template v-else-if="c == null && forecastDown">
          <span class="bdg" style="background:var(--none);color:#fff">예보 없음</span>
          &nbsp;혼잡 예보를 불러오지 못했어요 · 새로고침해 주세요.
        </template>
        <template v-else-if="outOfRange">
          <span class="bdg" style="background:var(--none);color:#fff">예보 전</span>
          &nbsp;{{ fmtK(at(state.di)) }}은 아직 혼잡 예보가 없어요<template v-if="untilText"> · 예보는 {{ untilText }}까지 있어요</template>.
        </template>
        <template v-else-if="c == null">
          <span class="bdg" style="background:var(--none);color:#fff">예보 없음</span>
          &nbsp;관광공사 혼잡 예측 대상이 아니에요.
        </template>
        <template v-else>
          <span class="bdg tier-bg" :class="t" style="color:#fff">{{ tierKo(c) }}</span>
          &nbsp;{{ fmtK(at(state.di)) }} 예보는 <b>{{ tierText }}</b>이에요.
        </template>
      </div>

      <!-- 범위 밖 날짜여도 이 장소의 예보가 있으면 팁은 살린다 - 예보가 있는 날로 돌아갈 길 -->
      <div v-if="(c != null || outOfRange) && best.c != null && !s.closed" class="tipbox" @click="jumpToBest">
        <template v-if="outOfRange">🕐 예보가 있는 날 중엔 <b>{{ fmtK(at(best.k)) }}</b>이 가장 한산해요.</template>
        <template v-else-if="!tipText">✓ {{ forecastLen }}일 중 <b>{{ dayWord(state.di) }}이 가장 한산</b>해요.</template>
        <template v-else>🕐 <b>{{ fmtK(at(best.k)) }}</b>로 가면 <b>{{ tipText }}</b> 날이에요.</template>
      </div>

      <!-- 착한가격 대표 메뉴 (행안부 실데이터) - 메뉴 없는 업소는 섹션 자체를 숨긴다 -->
      <div v-if="menuRows.length" class="menu">
        <div class="mn-h"><i></i>대표 메뉴<span v-if="s.good" class="mn-b">착한가격</span></div>
        <div v-for="m in menuRows" :key="m.n" class="mn-r">
          <span class="mn-n">{{ m.n }}</span><span class="mn-p">{{ m.p }}</span>
        </div>
        <!-- 가격은 행안부 CSV 기준일의 값이다 - 날짜 없이 보여주면 현재가처럼 읽힌다 (MAP_003) -->
        <div v-if="s.good" class="mn-src">{{ goodPriceSourceLine(detail?.goodPriceBaseDate) }}</div>
      </div>

      <!-- 방문 정보 - 제목을 붙여 '날짜별 날씨와 혼잡'·'방문 후기'와 같은 묶음으로 읽히게(2026-09-17 사용자 요청).
           편의 칩(요금·주차·화장실·실내)도 이 묶음 첫 줄로. 주소(복사) · 운영시간(있을 때만 — 상시 개방은 줄 자체를 표시하지 않음) · 전화 -->
      <div class="pinfo">
        <div class="st"><i></i>방문 정보</div>
        <!-- 없는 정보(null)는 배지를 그리지 않는다 - '주차 없음'과 '주차 정보 없음'은 다르다 -->
        <div v-if="hasAmen" class="amen">
          <span v-if="feeBadge" class="am">{{ feeBadge }}</span>
          <span v-if="s.park != null" class="am" :class="{ no: !s.park }">주차</span>
          <span v-if="s.wc != null" class="am" :class="{ no: !s.wc }">화장실</span>
          <span v-if="s.in" class="am">실내</span>
        </div>
        <!-- 주소가 없으면(대체 객체·원천 결측) 빈 줄과 복사 버튼을 내지 않는다 - 누르면 'undefined'가 복사됐다 -->
        <div v-if="s.addr" class="pi">
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
        <button v-if="crowdUi" class="p1" @click="findCalmDay">한산한 날 찾기</button>
        <button @click="findNearby">근처 대안 보기</button>
      </div>
      <div v-if="hint || nearbyOn" class="hint" style="display:block">
        <span v-if="hint" v-html="hint"></span>
        <template v-if="nearby.length">
          근처에 한산한 곳이 있어요 ·
          <template v-for="(o, i) in nearby" :key="placeKey(o.s)">
            <a style="color:var(--calm);cursor:pointer;font-weight:700"
              @click="emit('open-place', o.s)">{{ o.s.n }}</a><template v-if="i < nearby.length - 1">, </template>
          </template>
        </template>
        <!-- 대안이 없는 날짜로 옮기면 목록 대신 이 문구로 바뀐다 - 날짜와 함께 다시 계산되므로 -->
        <span v-else-if="nearbyOn" style="color:var(--tx3)">반경 12km 안에는 한산한 대안이 없어요.</span>
      </div>

      <!-- 날짜별 날씨와 혼잡 - 후기 바로 위(2026-09-17 사용자 요청). '한산한 날 찾기' 안내문 밑에 오므로 날짜를 옮긴 결과가 그 자리에서 보인다.
           폐업 장소는 날씨·혼잡 줄을 그리지 않는다 - 갈 수 없는 곳의 예보다 -->
      <template v-if="!s.closed">
      <div class="spark" style="margin:18px 0 6px"><div class="st"><i></i>{{ crowdUi ? '날짜별 날씨와 혼잡' : '날짜별 날씨' }}</div></div>
      <div class="wxrow">
        <!-- 날짜는 달력에서만 바꾼다 - 카드는 보기 전용, 달력에서 고른 날만 강조(2026-09-16 사용자 결정) -->
        <div v-for="w in week" :key="w.k" class="wxc" :class="{ on: w.k === state.di }">
          <div class="wd">{{ w.label }}</div>
          <!-- 아이콘은 3종이라 눈·소나기·흐림은 글자(title·낭독기)로 보완한다(최종점검 #41) -->
          <div class="wi" :title="w.w?.label" :aria-label="w.w?.label" v-html="w.w ? wxIcon(w.w.k, 27) : ''"></div>
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
          <div v-if="crowdUi" class="wc tier-bg" :class="w.t" :title="w.ko"></div>
        </div>
      </div>
      <div v-if="hasWx" class="wx-src">{{ wxSource }}</div>
      <div v-if="weatherGap && wxUntil" class="wx-note">날씨는 {{ wxUntil }}까지 제공돼요<template v-if="crowdUi && untilText"> · 혼잡은 {{ untilText }}까지</template></div>
      </template>

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
              <ProfileAvatar :src="r.profileImageUrl" :nickname="r.nickname" />
              <span class="rv-nm">{{ r.nickname ?? `여행자${r.userId}` }}</span>
              <span class="rv-dt">{{ rvDate(r.createdAt) }} 작성 <span v-if="r.editedAt">(수정)</span></span>
            </div>
            <div class="rv-mt">
              <template v-if="r.rating"><StarIcon v-for="n in 5" :key="n" :filled="n <= r.rating" :size="12" /></template>
              <!-- 예전 후기의 혼잡 제보 배지는 더 이상 보여주지 않는다(2026-09-18) - ReviewSection 과 같은 결정 -->
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
        <template v-else-if="reviewFailed">
          <div class="rv-none">후기를 불러오지 못했어요.</div>
          <button class="rvp-more" @click="fetchDetail">다시 시도 ›</button>
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
      <ReviewSection :place="s" :rating-avg="ratingAvg" :first-page="reviewPage" :first-page-failed="reviewFailed"
        @open-photo="p => emit('open-photo', p)" @changed="fetchDetail" />
    </div>
  </div>
</template>
