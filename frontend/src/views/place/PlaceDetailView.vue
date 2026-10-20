<script setup lang="ts">
/**
 * 장소 소개 페이지 (MAP_008) - 대한민국 구석구석 여행지 상세와 같은 순서로 "이 장소가 어떤 곳인지"를 소개한다(2026-09-18).
 *
 *   제목·지역·한 줄 소개·찜·공유·지도에서 보기 → 구간 이동 탭(사진보기·상세정보·후기) → 사진 슬라이더 1/N →
 *   소개(문단) 또는 메뉴·가격 → 상세정보(작은 지도 + 문의·주소·이용시간·휴일·주차·화장실·요금 표) →
 *   사진 후기(읽은 후기에 달린 사진 띠) → 후기(10건씩 '더보기'로 이어 붙임 · 사진 첨부 등록. 비로그인이면 안내 상자와 사진·등록 버튼만, 누르면 로그인으로)
 *
 * 이전 판(2단 구성)에서 뺀 것: '추천 근거' 카드(추천 화면의 부품이라 소개 페이지에선 뜻이 안 통함),
 * '혼잡 예보 커버 밖' 카드, '← 뒤로', 제목 밑 칩 줄(혼잡·착한가격·숨은 명소·별점), 그리고 날짜별 혼잡 예보 막대와
 * 이번 주 날씨 카드(2026-09-18 사용자 결정 - 이 페이지는 장소 소개만, 혼잡·날씨는 지도 상세 패널이 맡는다).
 * 운영시간·휴무·요금을 항목·소제목·비고로 펴는 표(infoText)와 후기 등록은 그대로 가져왔다.
 *
 * 정직성 규칙
 * - 혼잡은 분류 줄의 '오늘' 배지 하나뿐이다(메인 카드의 '한산 · 동부' 와 같은 날짜·같은 값, 2026-09-18 사용자 결정).
 *   예보 대상 밖이거나 오늘 값이 없으면 배지를 아예 그리지 않는다. 0이나 '예보 없음'으로 채우지 않는다.
 *   날짜별 막대·날씨는 이 페이지에 없다 - 지도 상세 패널(/map?place=)이 맡는다
 * - 가격은 착한가격업소만 검증가로 부르고 기준일을 함께 적는다
 * - 원문(소개·사진 설명)은 고치지 않는다. 문단은 표시만 나눈다. 사진 출처 ⓒ한국관광공사
 */
import { computed, nextTick, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import PlaceDetailService, { type PlaceDetail, type PlaceForecast } from '../../services/PlaceDetailService'
import ReviewApiService, { type ReviewItem, absUrl, failText } from '../../services/map/ReviewApiService'
import { useAuthStore } from '../../stores/auth.js'
import { isFav, loadFavorites, toggleFav, toast, state as mapState } from '../../stores/mapStore'
import PhotoLightbox from '../../components/map/PhotoLightbox.vue'
import CongestionBadge from '../../components/common/CongestionBadge.vue'
import { paragraphsOf } from '../../utils/intro.js'
import { displayName } from '../../config/themes.js'
import { loadKakaoMap } from '../../composables/useKakaoLoader.js'
import { safeLoginReturnTo } from '../../utils/loginReturn.js'
import { isWideInfo, parseInfoText } from './infoText'
import { buildForecastSeries } from './forecastSeries'
import { todayKst } from '../../utils/format.js'

const route = useRoute()
const router = useRouter()
const placeId = computed(() => Number(route.params.placeId))

const loading = ref(true)
const place = ref<PlaceDetail | null>(null)
const loadFailed = ref(false)             // 연결·서버 문제로 못 받음 - "없는 장소"(place null + false)와 구분해 다시 시도를 보여준다
/* 오늘 혼잡 배지 - 관광공사 집중률 예보(날짜 단위)에서 오늘 값만. 대상 밖(식당·카페 등)이거나 오늘 값이 없으면 null → 배지 없음 */
const forecast = ref<PlaceForecast | null>(null)
const today = todayKst()
const todayLevel = computed(() => buildForecastSeries(forecast.value, today).find(d => d.date === today && d.rate != null)?.level ?? null)
const reviews = ref<ReviewItem[]>([])    // 지금까지 읽은 후기 - '더보기'로 다음 페이지를 이어 붙인다(지도 후기 패널과 같은 방식)
const reviewTotal = ref(0)
const reviewPage = ref(0)                 // 마지막으로 읽은 페이지 번호
const reviewPages = ref(0)                // 전체 페이지 수
const reviewsLoading = ref(false)
const reviewsFailed = ref(false)          // 후기 첫 페이지를 못 받음 - "아직 후기가 없어요"라고 거짓말하지 않게(지도 패널 reviewFailed 와 같은 방식)
const reviewNotice = ref('')
const draftStars = ref(0)
const draftText = ref('')
const submitting = ref(false)

const auth = useAuthStore()
const PHOTO_SOURCE = 'ⓒ한국관광공사'
/* 후기 사진 첨부 - 지도 패널 ReviewSection 과 같은 기준(서버 ImageValidator: 5MB 이하, JPG·PNG·WEBP, 최대 5장) */
const MAX_PHOTOS = 5
const MAX_PHOTO_BYTES = 5 * 1024 * 1024
const PHOTO_TYPES = ['image/jpeg', 'image/png', 'image/webp']
const isPhotoType = (f: File) => PHOTO_TYPES.includes(f.type) || (!f.type && /\.(jpe?g|png|webp)$/i.test(f.name))

/* ───────── 사진 ───────── */
type Photo = { url: string; thumb: string; caption: string | null }
const photos = computed<Photo[]>(() => {
  const row = place.value
  if (!row) return []
  const list = row.images?.length ? row.images : row.imageUrl ? [{ url: row.imageUrl, thumbnailUrl: null, caption: null, attribution: null }] : []
  // 공사 URL 규칙: 원본 _image2_, 축소본 _image3_. 축소본이 없는 사진도 있어 실패하면 onThumbError 가 원본으로 바꾼다
  return list.map(im => ({ url: im.url, thumb: im.url.replace('_image2_', '_image3_'), caption: im.caption ?? null }))
})
const idx = ref(0)
const lightbox = ref<InstanceType<typeof PhotoLightbox> | null>(null)
const current = computed(() => photos.value[idx.value] ?? null)
const go = (n: number) => { const len = photos.value.length; if (len) idx.value = (idx.value + n + len) % len }
/* 작은 사진 줄 - 큰 사진을 넘기면 지금 사진의 작은 그림이 줄 가운데로 오게 줄만 옮긴다(페이지는 안 움직임).
   폰에선 줄이 화면보다 길어 5번째부터는 보이지 않았다(2026-09-18 사용자 제보) */
const thumbsEl = ref<HTMLElement | null>(null)
watch(idx, async () => {
  await nextTick()
  const el = thumbsEl.value; const on = el?.querySelector<HTMLElement>('button.on')
  if (!el || !on) return
  el.scrollTo({ left: on.offsetLeft - (el.clientWidth - on.offsetWidth) / 2, behavior: 'smooth' })
})
function onSliderKey (e: KeyboardEvent) {
  if (e.key === 'ArrowLeft') { e.preventDefault(); go(-1) }
  if (e.key === 'ArrowRight') { e.preventDefault(); go(1) }
}
function openLightbox () {
  if (!photos.value.length) return
  // PhotoLightbox 는 JS 컴포넌트라 show 의 인자 타입이 좁게 추론된다(alts: never[]) - any 로 넘긴다
  ;(lightbox.value as any)?.show(photos.value.map(p => p.url), idx.value,
    { alts: photos.value.map((p, i) => p.caption || `${place.value?.name} 사진 ${i + 1}`), source: PHOTO_SOURCE })
}
function onThumbError (e: Event, p: Photo) {
  const img = e.target as HTMLImageElement
  if (img.src !== p.url) img.src = p.url
}

/* ───────── 소개 · 메뉴 ───────── */
const MENU_PREFIX = '대표메뉴:'
const isMenu = computed(() => !!place.value?.overview?.trim().startsWith(MENU_PREFIX))
/** 착한가격 메뉴 문단 → 이름·가격 행. 지도 패널(PlaceDetail.vue menuRows)과 같은 규칙 */
const menuRows = computed(() => {
  const o = place.value?.overview?.trim()
  if (!o || !o.startsWith(MENU_PREFIX)) return []
  return o.slice(MENU_PREFIX.length).trim().split(' · ').map(item => {
    const m = item.match(/^(.*?)\s*([\d,]+원)$/)
    return m ? { n: m[1], p: m[2] } : { n: item, p: '' }
  })
})
const introParas = computed(() => (isMenu.value ? [] : paragraphsOf(place.value?.overview)))
/** 제목 아래 한 줄 소개 = 소개글 첫 문장. 메뉴 문단이면 없다 */
const lead = computed(() => {
  const first = introParas.value[0]
  if (!first) return null
  const m = first.match(/^.*?[다요]\.(?=\s|$)/)
  return m ? m[0] : first
})
const introOpen = ref(false)
const introLong = computed(() => (place.value?.overview?.length ?? 0) > 260)

/* ───────── 상세정보 ───────── */
const address = computed(() => place.value?.roadAddress ?? place.value?.lotAddress ?? null)
const feeText = computed(() => {
  const row = place.value
  if (!row) return '정보 없음'
  if (row.useFeeText) return row.useFeeText
  return row.free ? '무료' : '정보 없음'
})
/** 운영시간·휴무·요금을 항목·소제목·비고로 펴서(infoText) 그린다. 값이 없는 칸은 '정보 없음' - 없는 값을 지어내지 않는다 */
const facts = computed(() => {
  const row = place.value
  if (!row) return []
  const items = [
    { key: 'hours', label: '이용시간', source: row.operatingHoursText },
    { key: 'rest', label: '휴일', source: row.restDayText },
    { key: 'fee', label: row.goodPrice ? '가격(검증가)' : '이용요금', source: feeText.value === '정보 없음' ? null : feeText.value },
  ]
  return items.map(item => {
    const info = parseInfoText(item.source)
    const empty = info.blocks.length === 0 && info.notes.length === 0
    return { key: item.key, label: item.label, info, empty, wide: isWideInfo(info) }
  })
})
const yesNo = (v: boolean | null | undefined, yes: string, no: string) => (v == null ? null : v ? yes : no)

async function copyAddress () {
  if (!address.value) return
  try { await navigator.clipboard.writeText(address.value); toast('주소를 복사했어요') } catch { toast('복사가 막혀 있어요 · 주소를 직접 선택해 복사해 주세요') }
}

/* 작은 지도 - 핀 하나. SDK 를 못 받으면 지도 칸을 숨기고 주소만 남긴다 */
const mapEl = ref<HTMLElement | null>(null)
const mapFailed = ref(false)
let mapObj: any = null
async function drawMap () {
  const row = place.value
  if (!row || row.latitude == null || row.longitude == null || !mapEl.value) { mapFailed.value = true; return }
  try {
    const kakao: any = await loadKakaoMap()
    const center = new kakao.maps.LatLng(Number(row.latitude), Number(row.longitude))
    mapObj = new kakao.maps.Map(mapEl.value, { center, level: 4, draggable: false, scrollwheel: false, disableDoubleClickZoom: true })
    new kakao.maps.Marker({ position: center, map: mapObj })
    mapFailed.value = false
  } catch {
    mapFailed.value = true
  }
}

/* ───────── 탭 · 이동 ───────── */
const TABS = [{ key: 'photos', label: '사진보기' }, { key: 'info', label: '상세정보' }, { key: 'reviews', label: '후기' }]
const headerH = ref(80)
const jumpTo = (key: string) => document.getElementById(`place-${key}`)?.scrollIntoView({ behavior: 'smooth', block: 'start' })

/* ───────── 찜 · 공유 ───────── */
const favOn = computed(() => (place.value ? isFav(place.value) : false))
const canNativeShare = typeof navigator !== 'undefined' && typeof navigator.share === 'function'
async function share () {
  const row = place.value
  if (!row) return
  const url = `${location.origin}/places/${row.id}`
  if (canNativeShare) {
    try { await navigator.share({ title: row.name, text: `${row.name} — 한갓지도`, url }) } catch { /* 시트를 닫은 것 */ }
    return
  }
  try { await navigator.clipboard.writeText(url); toast('링크를 복사했어요 — 붙여넣으면 이 장소가 바로 열려요') } catch { toast('복사가 막혀 있어요 — 주소창의 주소를 직접 복사해 주세요') }
}

/* ───────── 후기 (이전 판 그대로) ───────── */
/** 사진 후기 띠 재료 - 읽은 후기 전부의 사진. '더보기'로 후기를 더 읽으면 띠도 같이 늘어난다 */
const reviewPhotos = computed(() => reviews.value.flatMap(r => r.imageUrls.map(u => ({ url: absUrl(u), who: r.nickname ?? `여행자${r.userId}`, when: r.createdAt.slice(0, 10) }))))
function openReviewPhoto (i: number) {
  const list = reviewPhotos.value
  ;(lightbox.value as any)?.show(list.map(x => x.url), i, { alts: list.map(x => `${x.who}님의 후기 사진 · ${x.when}`), source: '' })
}
/* 띠 넘기기 - 사진이 폭보다 많을 때만 양쪽에 ‹ › (사진 슬라이더와 같은 모양). 한 번에 보이는 폭의 80%씩 옮긴다 */
const stripEl = ref<HTMLElement | null>(null)
const stripCan = ref({ l: false, r: false })
function stripSync () {
  const el = stripEl.value
  stripCan.value = el ? { l: el.scrollLeft > 2, r: el.scrollLeft + el.clientWidth < el.scrollWidth - 2 } : { l: false, r: false }
}
function stripGo (dir: -1 | 1) {
  const el = stripEl.value
  if (el) el.scrollBy({ left: dir * el.clientWidth * 0.8, behavior: 'smooth' })
}
watch(reviewPhotos, () => nextTick(stripSync), { flush: 'post' })

async function loadReviews (id: number) {
  reviewsFailed.value = false
  try {
    const first = await ReviewApiService.getReviews(id, 0)
    if (placeId.value !== id) return   // 기다리는 사이 다른 장소로 갔으면 버린다
    reviews.value = first.content
    reviewTotal.value = first.totalElements
    reviewPage.value = first.number ?? 0
    reviewPages.value = first.totalPages
  } catch {
    reviews.value = []; reviewTotal.value = 0; reviewPage.value = 0; reviewPages.value = 0
    reviewsFailed.value = true
  }
}
/** '더보기' - 다음 페이지(10건)를 목록 뒤에 이어 붙인다 */
async function loadMoreReviews () {
  const id = placeId.value
  if (reviewsLoading.value || reviewPage.value + 1 >= reviewPages.value) return
  reviewsLoading.value = true
  try {
    const page = await ReviewApiService.getReviews(id, reviewPage.value + 1)
    if (placeId.value !== id) return   // 응답을 기다리는 사이 장소가 바뀌었으면 이전 장소의 후기가 붙지 않게
    reviews.value = reviews.value.concat(page.content)
    reviewTotal.value = page.totalElements
    reviewPage.value = page.number
    reviewPages.value = page.totalPages
  } catch {
    toast('후기를 더 불러오지 못했어요')
  } finally {
    reviewsLoading.value = false
  }
}

/* 첨부 사진 - 고르는 순간 서버 기준으로 거른다 */
const attach = ref<{ file: File; preview: string }[]>([])   // 첨부 사진(관광공사 사진 photos 와 다른 것)
const fileInput = ref<HTMLInputElement | null>(null)
function onFiles (e: Event) {
  const input = e.target as HTMLInputElement
  const remain = MAX_PHOTOS - attach.value.length
  if (remain <= 0) { toast(`사진은 최대 ${MAX_PHOTOS}장까지예요`); input.value = ''; return }
  const picked = [...(input.files ?? [])]
  const ok = picked.filter(f => f.size <= MAX_PHOTO_BYTES && isPhotoType(f))
  for (const f of ok.slice(0, remain)) attach.value.push({ file: f, preview: URL.createObjectURL(f) })
  if (ok.length > remain) toast(`사진은 최대 ${MAX_PHOTOS}장까지예요`)
  const bad = picked.filter(f => !ok.includes(f))
  if (bad.length) {
    const why = bad[0].size > MAX_PHOTO_BYTES ? '5MB가 넘는 사진은 뺐어요' : 'JPG·PNG·WEBP 사진만 올릴 수 있어요'
    toast(`${why} · ${bad[0].name}${bad.length > 1 ? ` 외 ${bad.length - 1}장` : ''}`)
  }
  input.value = ''
}
function removePhoto (i: number) {
  URL.revokeObjectURL(attach.value[i].preview)
  attach.value.splice(i, 1)
}
/** 비로그인 상자의 사진·등록 버튼 - 로그인 화면으로 보내고, 로그인 뒤 이 장소로 돌아온다(라우터 가드·useApiError 와 같은 방식) */
function goLogin () {
  const back = safeLoginReturnTo(route.fullPath)
  ;(auth as any).returnTo = back
  router.push({ name: 'login', query: { redirect: back } })
}

async function submitReview () {
  if (!auth.isLoggedIn) { goLogin(); return }
  // 백엔드 계약(ReviewService): 별점 또는 혼잡 제보 중 하나는 있어야 한다. 혼잡 제보 입력은 뺐으므로(2026-09-18 사용자 결정, 지도 패널과 같음) 별점이 필수
  if (!draftStars.value) { reviewNotice.value = '별점을 골라 주세요.'; return }
  const id = placeId.value
  submitting.value = true
  reviewNotice.value = ''
  try {
    const imageUrls = attach.value.length ? await ReviewApiService.uploadPhotos(attach.value.map(p => p.file)) : []
    await ReviewApiService.create(id, { rating: draftStars.value || null, congestionReport: null, content: draftText.value.trim() || null, imageUrls })
    draftStars.value = 0; draftText.value = ''
    attach.value.forEach(p => URL.revokeObjectURL(p.preview)); attach.value = []
    await loadReviews(id)
    reviewNotice.value = imageUrls.length ? `사진 ${imageUrls.length}장과 함께 후기를 남겼어요.` : '후기를 남겼어요.'
  } catch (error) {
    reviewNotice.value = failText(error, '후기를 남기지 못했어요')   // 서버 원문("JWT 토큰 유효하지 않음" 등)은 안 보여준다
  } finally {
    submitting.value = false
  }
}

/* ───────── 로드 ───────── */
async function load () {
  loading.value = true
  place.value = null; forecast.value = null; reviews.value = []; reviewTotal.value = 0; reviewPage.value = 0; reviewPages.value = 0; reviewNotice.value = ''
  loadFailed.value = false; reviewsFailed.value = false
  attach.value.forEach(p => URL.revokeObjectURL(p.preview)); attach.value = []
  idx.value = 0; introOpen.value = false; mapFailed.value = false
  const id = placeId.value
  if (!Number.isFinite(id)) { loading.value = false; return }
  const { detail, missing } = await PlaceDetailService.getById(id)
  place.value = detail
  loadFailed.value = !detail && !missing   // 4xx 는 "없는 장소", 그 외 실패는 "못 받음"
  loading.value = false
  if (!detail) return
  document.title = `${detail.name} · 한갓지도`   // 라우터가 준 '관광지 · 한갓지도' 를 장소 이름으로(공유·즐겨찾기·탭 제목)
  await nextTick()
  drawMap()
  // 오늘 배지는 기다리지 않고 채운다 - 실패하면 배지만 없다
  PlaceDetailService.getForecast(id).then(rows => { if (placeId.value === id) forecast.value = rows }).catch(() => null)
  await loadReviews(id)   // 후기는 상세가 떠 있는 동안 채운다
}

onMounted(() => { headerH.value = document.querySelector('nav.nav')?.clientHeight ?? 80; window.addEventListener('resize', stripSync); load() })
onBeforeUnmount(() => { mapObj = null; window.removeEventListener('resize', stripSync) })
watch(placeId, load)
watch(() => (auth as any).user?.userId ?? null, id => loadFavorites(id), { immediate: true })   // auth 스토어는 JS(user: null 로 시작)라 타입이 never
</script>

<template>
  <section v-if="loading" class="page place-state">
    <h2>장소 정보를 불러오고 있어요.</h2>
  </section>

  <section v-else-if="loadFailed" class="page place-state">
    <h2>장소 정보를 불러오지 못했어요.</h2>
    <p class="muted">인터넷 연결을 확인한 뒤 다시 시도해 주세요.</p>
    <button type="button" class="btn primary" @click="load">다시 시도</button>
  </section>

  <section v-else-if="!place" class="page place-state">
    <h2>장소를 찾지 못했어요.</h2>
    <p class="muted">주소가 잘못되었거나 더 이상 제공하지 않는 장소예요.</p>
    <RouterLink class="btn primary" to="/map">지도로 가기</RouterLink>
  </section>

  <article v-else class="place-page">
    <!-- 제목 · 지역 · 한 줄 소개 · 찜 · 공유 · 지도에서 보기 -->
    <header class="head">
      <div class="kicker-row">
        <p class="kicker">{{ [place.tagName ? displayName(place.tagName) : place.categoryName, place.regionName ? `제주 ${place.regionName}` : null].filter(Boolean).join(' · ') }}</p>
        <!-- 오늘 혼잡 - 메인 카드의 '한산 · 동부' 와 같은 배지. 예보 대상이 아니면 안 그린다 -->
        <CongestionBadge v-if="todayLevel" :level="todayLevel" class="today-badge" title="오늘 혼잡 예보 · 한국관광공사 집중률 예보" />
      </div>
      <h1>{{ place.name }}</h1>
      <p v-if="lead" class="lead">{{ lead }}</p>
      <!-- 제목 밑 칩 줄(혼잡·착한가격·숨은 명소·별점)은 전부 뺐다(2026-09-18 사용자 결정) - 장소 소개 페이지라 혼잡은 아래 예보 구간에서만 -->
      <div class="actions">
        <button type="button" class="act" :class="{ on: favOn }" :aria-pressed="favOn" @click="toggleFav(place)">
          <span aria-hidden="true">{{ favOn ? '♥' : '♡' }}</span>{{ favOn ? '찜함' : '찜하기' }}
        </button>
        <button type="button" class="act" @click="share"><span aria-hidden="true">⇪</span>공유하기</button>
        <RouterLink class="act primary" :to="`/map?place=${place.id}`"><span aria-hidden="true">◎</span>지도에서 보기</RouterLink>
      </div>
    </header>

    <!-- 구간 이동 탭 - 제자리에 두고 따라오지 않는다(2026-09-18 사용자 결정, 테마 페이지 칩과 같은 원칙) -->
    <!-- 클래스 이름이 .tabs/.tab 이면 헤더 전역 스타일과 겹친다 - jumpbar/jump 로 -->
    <nav class="jumpbar" aria-label="구간 이동">
      <button v-for="t in TABS" :key="t.key" type="button" class="jump" @click="jumpTo(t.key)">{{ t.label }}</button>
    </nav>

    <!-- 사진보기 -->
    <section id="place-photos" class="sec" :style="{ scrollMarginTop: headerH + 12 + 'px' }" aria-label="사진보기">
      <div v-if="photos.length" class="slider" tabindex="0" @keydown="onSliderKey">
        <button type="button" class="main" :aria-label="`${idx + 1}번째 사진 크게 보기`" @click="openLightbox">
          <img :src="current!.url" :alt="current!.caption || `${place.name} 사진 ${idx + 1}`" decoding="async">
        </button>
        <button v-if="photos.length > 1" type="button" class="nav prev" aria-label="이전 사진" @click="go(-1)">‹</button>
        <button v-if="photos.length > 1" type="button" class="nav next" aria-label="다음 사진" @click="go(1)">›</button>
        <span class="counter">{{ idx + 1 }} / {{ photos.length }}</span>
        <span class="src">{{ PHOTO_SOURCE }}</span>
      </div>
      <div v-else class="no-photo"><span>🏞️</span>이미지 준비 중입니다</div>
      <p v-if="current?.caption" class="caption">{{ current.caption }}</p>
      <ul v-if="photos.length > 1" ref="thumbsEl" class="thumbs" aria-label="사진 목록">
        <li v-for="(p, i) in photos" :key="p.url">
          <button type="button" :class="{ on: i === idx }" :aria-label="`${i + 1}번째 사진`" :aria-current="i === idx ? 'true' : undefined" @click="idx = i">
            <img :src="p.thumb" :alt="''" loading="lazy" decoding="async" @error="onThumbError($event, p)">
          </button>
        </li>
      </ul>
    </section>

    <!-- 소개 / 메뉴·가격 -->
    <section v-if="place.overview" class="sec card" aria-labelledby="place-intro-h">
      <div class="sec-h"><i></i><h2 id="place-intro-h">{{ isMenu ? '메뉴·가격' : '소개' }}</h2><span v-if="!isMenu" class="sec-src">{{ PHOTO_SOURCE }}</span></div>
      <template v-if="isMenu">
        <ul class="menu">
          <li v-for="m in menuRows" :key="m.n"><span>{{ m.n }}</span><b>{{ m.p }}</b></li>
        </ul>
        <small class="muted">행정안전부 착한가격업소 등록가(검증가){{ place.goodPriceBaseDate ? ` · ${place.goodPriceBaseDate} 기준` : '' }}</small>
      </template>
      <template v-else>
        <div class="intro" :class="{ clamp: introLong && !introOpen }">
          <p v-for="(pg, i) in introParas" :key="i">{{ pg }}</p>
        </div>
        <button v-if="introLong" type="button" class="more" :aria-expanded="introOpen" @click="introOpen = !introOpen">{{ introOpen ? '접기 ‹' : '내용 더보기 ›' }}</button>
      </template>
    </section>

    <!-- 상세정보 -->
    <section id="place-info" class="sec card" :style="{ scrollMarginTop: headerH + 12 + 'px' }" aria-labelledby="place-info-h">
      <div class="sec-h"><i></i><h2 id="place-info-h">상세정보</h2></div>
      <div v-show="!mapFailed" ref="mapEl" class="mini-map" aria-label="위치 지도"></div>
      <dl class="info">
        <div v-if="place.phone"><dt>문의 및 안내</dt><dd><a :href="`tel:${place.phone}`">{{ place.phone }}</a></dd></div>
        <div v-if="address"><dt>주소</dt><dd class="addr"><span>{{ address }}</span><button type="button" class="copy" @click="copyAddress">복사</button></dd></div>
        <div v-for="fact in facts" :key="fact.key" :class="{ wide: fact.wide }">
          <dt>{{ fact.label }}</dt>
          <dd v-if="fact.empty" class="muted">정보 없음</dd>
          <dd v-else>
            <div class="info-blocks">
              <div v-for="(block, i) in fact.info.blocks" :key="i" class="info-block">
                <strong v-if="block.title">{{ block.title }}</strong>
                <ul class="info-lines"><li v-for="(line, j) in block.lines" :key="j" :class="{ bullet: line.bullet }">{{ line.text }}</li></ul>
              </div>
            </div>
            <p v-for="(note, i) in fact.info.notes" :key="`note-${i}`" class="info-note">※ {{ note }}</p>
          </dd>
        </div>
        <div v-if="yesNo(place.parkingAvailable, '가능', '불가')"><dt>주차</dt><dd>{{ yesNo(place.parkingAvailable, '가능', '불가') }}</dd></div>
        <div v-if="yesNo(place.toiletAvailable, '있음', '없음')"><dt>화장실</dt><dd>{{ yesNo(place.toiletAvailable, '있음', '없음') }}</dd></div>
      </dl>
    </section>

    <!-- 사진 후기 - 후기에 달린 사진만 모아 띠로. 누르면 크게 보기 -->
    <section v-if="reviewPhotos.length" class="sec card" aria-labelledby="place-photo-reviews-h">
      <div class="sec-h"><i></i><h2 id="place-photo-reviews-h">사진 후기<small>{{ reviewPhotos.length }}장</small></h2></div>
      <div class="rstrip">
        <button v-if="stripCan.l" type="button" class="nav prev" aria-label="이전 사진들" @click="stripGo(-1)">‹</button>
        <ul ref="stripEl" class="rphotos" aria-label="후기 사진" @scroll.passive="stripSync">
          <li v-for="(ph, i) in reviewPhotos" :key="ph.url + i">
            <button type="button" :aria-label="`${ph.who}님의 후기 사진 크게 보기`" @click="openReviewPhoto(i)"><img :src="ph.url" :alt="`${ph.who}님의 후기 사진`" loading="lazy" decoding="async"></button>
          </li>
        </ul>
        <button v-if="stripCan.r" type="button" class="nav next" aria-label="다음 사진들" @click="stripGo(1)">›</button>
      </div>
    </section>

    <!-- 후기 -->
    <section id="place-reviews" class="sec card" :style="{ scrollMarginTop: headerH + 12 + 'px' }" aria-labelledby="place-reviews-h">
      <div class="sec-h"><i></i><h2 id="place-reviews-h">후기<small v-if="reviewTotal > 0">{{ reviewTotal }}</small></h2></div>
      <ul v-if="reviews.length" class="review-list">
        <li v-for="review in reviews" :key="review.id">
          <div class="review-head">
            <b>{{ review.nickname ?? `여행자${review.userId}` }}</b>
            <span v-if="review.rating != null" class="stars">{{ '★'.repeat(review.rating) }}</span>
            <!-- 혼잡 제보 칩('한산 제보')은 목록에서 안 보여준다(2026-09-18 사용자 결정). 제보 자체는 폼에서 계속 받는다 -->
            <small class="muted">{{ review.createdAt.slice(0, 10) }} <span v-if="review.editedAt">(수정)</span></small>
          </div>
          <p v-if="review.content">{{ review.content }}</p>
          <div v-if="review.imageUrls.length" class="review-photos">
            <img v-for="url in review.imageUrls.slice(0, 3)" :key="url" :src="absUrl(url)" alt="후기 사진">
          </div>
        </li>
      </ul>
      <template v-else-if="reviewsFailed">
        <p class="muted">후기를 불러오지 못했어요.</p>
        <button type="button" class="rv-more" @click="loadReviews(placeId)">다시 시도</button>
      </template>
      <p v-else class="muted">아직 후기가 없어요.</p>
      <button v-if="reviews.length < reviewTotal" type="button" class="rv-more" :disabled="reviewsLoading" @click="loadMoreReviews">
        {{ reviewsLoading ? '불러오는 중…' : `후기 ${reviewTotal - reviews.length}개 더보기` }}
      </button>

      <!-- 비로그인: 구석구석 댓글 상자처럼 안내 한 줄 + 사진·등록 버튼. 누르면 로그인으로(2026-09-18 사용자 요청) -->
      <div v-if="!auth.isLoggedIn" class="review-form guest">
        <textarea rows="3" disabled placeholder="로그인 후 후기를 남길 수 있어요." aria-label="로그인 후 후기를 남길 수 있어요" />
        <div class="form-foot">
          <button type="button" class="btn ghost" @click="goLogin"><span aria-hidden="true">📷</span> 사진</button>
          <button type="button" class="btn primary" @click="goLogin">등록</button>
        </div>
      </div>

      <form v-else class="review-form" @submit.prevent="submitReview">
        <div class="star-picker">
          <button v-for="n in 5" :key="n" type="button" class="star-btn" :class="{ on: n <= draftStars }" :aria-label="`별점 ${n}점`" @click="draftStars = n">★</button>
          <small class="muted">{{ draftStars ? `${draftStars}점` : '별점 선택' }}</small>
        </div>
        <textarea v-model="draftText" rows="3" placeholder="방문 후기를 남겨주세요" />
        <div class="phrow">
          <span v-for="(p, i) in attach" :key="p.preview" class="pht">
            <img :src="p.preview" alt="첨부한 사진">
            <button type="button" class="del" aria-label="사진 삭제" @click="removePhoto(i)">×</button>
          </span>
          <button v-if="attach.length < MAX_PHOTOS" type="button" class="phadd" @click="fileInput?.click()">사진<br>{{ attach.length }}/{{ MAX_PHOTOS }}</button>
          <input ref="fileInput" type="file" accept="image/jpeg,image/png,image/webp" multiple hidden @change="onFiles">
          <small class="muted">JPG·PNG·WEBP, 장당 5MB까지</small>
        </div>
        <div class="form-foot">
          <small v-if="reviewNotice" class="muted">{{ reviewNotice }}</small>
          <button class="btn primary" type="submit" :disabled="submitting">{{ submitting ? '올리는 중...' : '등록' }}</button>
        </div>
      </form>
    </section>

    <PhotoLightbox ref="lightbox" />
    <!-- 토스트 - 지도 화면의 MapToast 는 지도 안에만 있어 이 페이지에선 toast() 가 보이지 않았다.
         찜 로그인 안내('찜은 로그인이 필요해요')·주소·링크 복사·사진 첨부 안내가 여기로 뜬다 -->
    <div class="ptoast" :class="{ on: !!mapState.toast }" role="status" aria-live="polite">{{ mapState.toast }}</div>
  </article>
</template>

<style scoped>
.place-state{display:grid;gap:12px;justify-items:start}
.place-page{max-width:960px;margin:0 auto;padding:22px 20px 60px;display:grid;gap:14px}   /* 전역 .page(위 여백 100px)를 안 쓴다 */

/* 제목 블록 */
.head{display:grid;gap:8px;padding:6px 0 4px}
.kicker-row{display:flex;align-items:center;gap:8px;flex-wrap:wrap}
.kicker{margin:0;font-size:13px;font-weight:600;color:var(--tx3)}
.today-badge{padding:3px 9px;font-size:11.5px;font-weight:700}
.head h1{margin:0;font-size:clamp(1.7rem,3.4vw,2.3rem);font-weight:800;letter-spacing:-.03em;line-height:1.2}
.lead{margin:0;font-size:15px;line-height:1.6;color:var(--tx2);max-width:720px;word-break:keep-all}
.actions{display:flex;flex-wrap:wrap;gap:8px;margin-top:6px}
.act{display:inline-flex;align-items:center;gap:6px;height:38px;padding:0 15px;border-radius:19px;border:1px solid var(--line);background:var(--surf);
  color:var(--tx);font-size:13.5px;font-weight:700;text-decoration:none;cursor:pointer}
.act:hover{border-color:var(--ac);color:var(--ac-dk)}
.act.on{border-color:#e6557a;color:#e6557a;background:#fff0f4}
.act.primary{background:var(--ac);border-color:var(--ac);color:var(--on-ac)}
.act.primary:hover{filter:brightness(.95);color:var(--on-ac)}
.act:focus-visible,.jump:focus-visible,.nav:focus-visible,.main:focus-visible,.thumbs button:focus-visible,.copy:focus-visible,.more:focus-visible{outline:2px solid var(--ac);outline-offset:2px}

/* 구간 이동 탭 (제자리, 따라오지 않음) */
.jumpbar{display:flex;gap:2px;padding:6px 0;border-bottom:1px solid var(--line)}
.jump{height:38px;padding:0 16px;border-radius:10px;background:none;border:0;font-size:14px;font-weight:700;color:var(--tx2);cursor:pointer}
.jump:hover{background:var(--surf2);color:var(--tx)}

/* 구간 공통 */
.sec{display:grid;gap:12px}
.card{padding:20px;background:var(--surf);border:1px solid var(--line);border-radius:18px}
.sec-h{display:flex;align-items:center;gap:8px}
.sec-h i{width:4px;height:16px;border-radius:2px;background:var(--ac)}
.sec-h h2{margin:0;font-size:17px;font-weight:800;letter-spacing:-.02em;display:flex;align-items:baseline;gap:8px}
.sec-h h2 small{font-size:12.5px;font-weight:600;color:var(--tx3)}
.sec-src{margin-left:auto;font-size:11px;color:var(--tx3)}

/* 사진 슬라이더 */
.slider{position:relative;border-radius:18px;overflow:hidden;background:var(--surf2);outline:none}
.slider .main{display:block;width:100%;aspect-ratio:16/9;max-height:520px;padding:0;border:0;background:var(--surf2);cursor:zoom-in}
.slider .main img{width:100%;height:100%;object-fit:cover;display:block}
.nav{position:absolute;top:50%;transform:translateY(-50%);width:40px;height:40px;border-radius:50%;border:0;background:rgba(15,25,35,.55);color:#fff;font-size:24px;line-height:1;cursor:pointer}
.nav:hover{background:rgba(15,25,35,.75)}
.nav.prev{left:12px}.nav.next{right:12px}
.counter{position:absolute;right:12px;bottom:10px;padding:3px 9px;border-radius:999px;background:rgba(15,25,35,.6);color:#fff;font-size:12px;font-weight:700}
.src{position:absolute;left:12px;bottom:10px;font-size:11px;color:rgba(255,255,255,.85);text-shadow:0 1px 3px rgba(0,0,0,.5)}
.no-photo{display:flex;flex-direction:column;align-items:center;justify-content:center;gap:6px;aspect-ratio:16/6;border-radius:18px;background:var(--surf2);color:var(--tx3);font-size:13px}
.no-photo span{font-size:30px;opacity:.6}
.caption{margin:-4px 0 0;font-size:12.5px;color:var(--tx2)}
.thumbs{list-style:none;margin:0;padding:2px 0;display:flex;gap:6px;overflow-x:auto;scrollbar-width:none}
.thumbs::-webkit-scrollbar{display:none}
.thumbs button{width:76px;height:56px;padding:0;border:2px solid transparent;border-radius:10px;overflow:hidden;background:var(--surf2);cursor:pointer;opacity:.75;flex:0 0 auto}
.thumbs button img{width:100%;height:100%;object-fit:cover;display:block}
.thumbs button.on{border-color:var(--ac);opacity:1}
.thumbs button:hover{opacity:1}

/* 소개 · 메뉴 */
.intro p{margin:0 0 10px;font-size:14.5px;line-height:1.8;color:var(--tx2);word-break:keep-all}
.intro p:last-child{margin-bottom:0}
.intro.clamp{display:-webkit-box;-webkit-line-clamp:6;-webkit-box-orient:vertical;overflow:hidden}
.more{justify-self:start;padding:0;border:0;background:none;color:var(--ac-dk);font-size:13px;font-weight:700;cursor:pointer}
.menu{list-style:none;margin:0;padding:0;display:grid;gap:6px}
.menu li{display:flex;justify-content:space-between;gap:12px;padding:8px 0;border-bottom:1px dashed var(--line);font-size:14px}
.menu li b{color:var(--ac-dk)}

/* 상세정보 */
.mini-map{width:100%;height:220px;border-radius:14px;background:var(--surf2);overflow:hidden}
.info{display:grid;gap:0;margin:0}
.info>div{display:grid;grid-template-columns:110px 1fr;gap:12px;padding:11px 0;border-bottom:1px solid var(--line);min-width:0}
.info>div:last-child{border-bottom:0}
.info dt{font-size:13px;font-weight:700;color:var(--tx3)}
.info dd{margin:0;font-size:14px;line-height:1.55;color:var(--tx);min-width:0}
.info dd a{color:inherit;text-decoration:none}
.addr{display:flex;align-items:flex-start;gap:8px}
.copy{flex:0 0 auto;height:24px;padding:0 9px;border-radius:12px;border:0;background:var(--ac-bg);color:var(--ac-dk);font-size:11.5px;font-weight:700;cursor:pointer}
.info-blocks{display:flex;flex-wrap:wrap;gap:6px 28px}
.info-block strong{display:block;font-size:12px;font-weight:700;color:var(--ac-dk);margin-bottom:2px}
.info-lines{list-style:none;margin:0;padding:0}
.info-lines li.bullet{position:relative;padding-left:12px}
.info-lines li.bullet::before{content:'';position:absolute;left:0;top:.62em;width:5px;height:5px;border-radius:50%;background:currentColor;opacity:.45}
.info-note{margin:6px 0 0;font-size:12px;color:var(--tx3);line-height:1.5}

/* 사진 후기 띠 - 넘치면 양쪽 ‹ › 로 넘긴다 */
.rstrip{position:relative;min-width:0}   /* .sec 가 grid 라 min-width 를 안 주면 띠 폭이 내용만큼 늘어나 넘침(화살표)이 안 생긴다 */
.rstrip .nav{width:34px;height:34px;font-size:20px}
.rstrip .nav.prev{left:6px}.rstrip .nav.next{right:6px}
.rphotos{list-style:none;margin:0;padding:2px 0;display:flex;gap:8px;overflow-x:auto;scrollbar-width:none;scroll-snap-type:x proximity}
.rphotos li{scroll-snap-align:start}
.rphotos::-webkit-scrollbar{display:none}
.rphotos li{flex:0 0 auto}
.rphotos button{width:132px;height:132px;padding:0;border:0;border-radius:12px;overflow:hidden;background:var(--surf2);cursor:zoom-in}
.rphotos button img{width:100%;height:100%;object-fit:cover;display:block;transition:transform .15s}
.rphotos button:hover img{transform:scale(1.04)}
.rphotos button:focus-visible{outline:2px solid var(--ac);outline-offset:2px}

/* 후기 사진 첨부 */
.phrow{display:flex;align-items:center;gap:6px;flex-wrap:wrap}
.pht{position:relative;width:52px;height:52px;flex:0 0 auto}
.pht img{width:100%;height:100%;object-fit:cover;border-radius:10px;display:block}
.pht .del{position:absolute;top:-6px;right:-6px;width:18px;height:18px;border-radius:50%;border:0;background:var(--tx);color:#fff;font-size:12px;line-height:1;cursor:pointer}
.phadd{width:52px;height:52px;border:1.5px dashed var(--line2);border-radius:10px;background:none;color:var(--tx3);font-size:11px;line-height:1.3;cursor:pointer}
.phadd:hover{border-color:var(--ac);color:var(--ac-dk)}
.phrow small{margin-left:4px}

/* 후기 (이전 판) */
.review-list{list-style:none;padding:0;margin:0;display:grid;gap:14px}
.review-head{display:flex;align-items:center;gap:8px;flex-wrap:wrap}
.review-head small{margin-left:auto}
.stars{color:#f0a92b;letter-spacing:-1px}
.review-photos{display:flex;gap:6px;margin-top:6px}
.review-photos img{width:72px;height:72px;object-fit:cover;border-radius:10px}
.rv-more{width:100%;padding:11px;border:1px solid var(--line2);border-radius:12px;background:var(--surf2);font:inherit;font-size:13px;font-weight:700;color:var(--tx2);cursor:pointer}
.rv-more:hover{border-color:var(--ac);color:var(--ac-dk)}
.rv-more:disabled{opacity:.6;cursor:default}
.review-form{display:grid;gap:8px;padding-top:14px;border-top:1px solid var(--line)}
.star-picker{display:flex;align-items:center;gap:4px}
.star-btn{background:none;border:0;font-size:20px;color:var(--line);padding:0 1px;cursor:pointer}
.star-btn.on{color:#f0a92b}
.review-form textarea{width:100%;padding:10px 12px;border:1px solid var(--line);border-radius:12px;resize:none;background:transparent;font:inherit}
.form-foot{display:flex;align-items:center;justify-content:flex-end;gap:8px}
.form-foot .btn{padding:9px 16px;font-size:13px;border-radius:10px}   /* 전역 .btn(13px 20px)은 이 폼엔 너무 크다(2026-09-18) */
/* 비로그인 상자 */
.review-form.guest textarea:disabled{background:var(--surf2);color:var(--tx3);cursor:default}
.review-form.guest textarea::placeholder{color:var(--tx3)}
.btn.ghost{background:transparent;border:1px solid var(--line2);color:var(--tx2)}
.btn.ghost:hover{border-color:var(--ac);color:var(--ac-dk)}

/* 토스트 - hangat.css 의 .toast 와 같은 모양(그건 지도 껍데기 안에서만 먹는다) */
.ptoast{position:fixed;left:50%;bottom:34px;transform:translateX(-50%) translateY(14px);background:var(--tx);color:var(--surf);
  font-size:13px;font-weight:600;padding:11px 20px;border-radius:999px;box-shadow:0 8px 24px rgba(0,0,0,.18);opacity:0;pointer-events:none;
  transition:opacity .2s,transform .2s;z-index:2000;width:max-content;max-width:calc(100vw - 32px);text-align:center;line-height:1.4}
.ptoast.on{opacity:1;transform:translateX(-50%) translateY(0)}

@media (max-width:767px){
  .place-page{gap:12px;padding:14px 16px 48px}
  .ptoast{bottom:calc(var(--mobile-tabbar-h, 60px) + 24px)}
  .card{padding:16px}
  .actions .act{flex:1 1 auto;justify-content:center;padding:0 10px}
  .jumpbar{margin:0 -16px;padding:6px 16px}
  .jump{flex:1;padding:0}
  .slider .main{aspect-ratio:4/3}
  .nav{width:34px;height:34px;font-size:20px}
  .info>div{grid-template-columns:86px 1fr;gap:10px}
  .mini-map{height:180px}
  .star-btn{font-size:26px;padding:0 3px}
  .more{min-height:36px}                        /* '내용 더보기' 손가락 크기(전엔 77×21) */
  .copy{height:32px;padding:0 12px;font-size:12px}   /* 주소 '복사'(전엔 41×24) */
  .review-form textarea{font-size:16px}
  .rphotos button{width:108px;height:108px}
  .form-foot{flex-wrap:wrap}
  .form-foot .btn{flex:1 1 100%;text-align:center}
  .review-form.guest .form-foot .btn{flex:1 1 0}
}
</style>
