import { reactive, computed, watch } from 'vue'
import { useAuthStore } from '@/stores/auth'
import { crowd, tier } from '@/utils/crowd'
import { iso, today, refreshToday } from '@/utils/date'
import MapPlaceService, { LAZY_LAYERS } from '@/services/map/MapPlaceService'
import CrowdService, { attachSeries } from '@/services/map/CrowdService'
import WeatherService from '@/services/map/MapWeatherService'
import FavoriteApiService from '@/services/map/FavoriteApiService'

/* 지도 페이지 전역 상태.
   Pinia와 같은 모양(state + action)으로 두어 나중에 옮기기 쉽게 했다.
   지금은 페이지가 하나뿐이라 의존성을 늘리지 않고 reactive() 하나로 충분하다 */

/* 찜을 브라우저에 이름으로 저장하던 시절의 키 - 이제 백엔드가 단일 저장소라 남은 값은 지운다(2026-09-07) */
try { localStorage.removeItem('hangat_favs') } catch { /* 저장소 접근 불가 - 무시 */ }

export const REGIONS = ['전체', '동부', '서부', '남부', '북부']

/** 업종 필터 (MAP_001) — 한 번에 4개씩 보이고 좌우로 넘긴다 */
export const LAYERS = [
  { k: 'spot', t: '관광지' }, { k: 'food', t: '착한가격' }, { k: 'dine', t: '식당' },
  { k: 'cafe', t: '카페' }, { k: 'cvs', t: '편의점' }, { k: 'stay', t: '숙소' }, { k: 'mart', t: '마트' },
]
export const FILTER_VISIBLE = 4

/* 권역 기본값은 '전체'. 탭이 살아 있는 동안은 마지막 선택을 기억한다(sessionStorage) -
   메인↔지도를 오가도 보던 권역이 유지되고, 탭을 닫으면 다시 '전체'로 시작한다.
   공유 링크의 ?r= 은 MapView.loadFromURL 이 이 값 위에 덮어쓴다 (MAP_001, 2026-09-11 결정) */
const REGION_KEY = 'hangat_map_region'
export function savedRegion (storage = globalThis.sessionStorage) {
  try {
    const v = storage?.getItem(REGION_KEY)
    return REGIONS.includes(v) ? v : '전체'
  } catch {
    return '전체'   // 저장소 접근 불가(프라이빗 모드 등) - 기본값으로
  }
}

export const state = reactive({
  /* ── 서버에서 받아오는 장소 데이터 ──
     하드코딩 시절엔 import 하는 순간 값이 있었지만 이제 비동기라 처음엔 비어 있다.
     reactive 라서 loadPlaces() 가 채우면 화면이 알아서 다시 그려진다 */
  layers: { spot: [], food: [], dine: [], cafe: [], cvs: [], stay: [], mart: [] },
  loading: true,
  /** false = 장소 레이어를 하나도 못 받았다(백엔드 다운) - 화면이 '새로고침' 안내를 띄운다 */
  live: false,
  /** 이번 진입에서 못 받아온 레이어 키 - 칩을 다시 켜면 그 레이어만 재시도한다 */
  loadFailed: [],
  /** 예보 일수(원시값, 0 = 예보를 못 받음). 화면은 30일 캘린더인데 실측은 21~22일이라 남는 날은 '정보 없음' */
  forecastDays: 0,
  /** 오늘 기준 예보가 있는 마지막 날 인덱스(0 = 오늘, -1 = 없음). 관광지 전체에서 값이 있는 가장 뒤 칸.
      "이 날짜는 아직 예보가 없어요 · 예보는 M/D까지" 같은 문구가 쓴다 - 없는 날을 '예측 대상 아님'이라 부르지 않게(2026-09-13) */
  forecastUntil: -1,
  /** 예보를 장소에 붙일 때마다 1 증가 - 지도가 이걸 보고 핀 색을 다시 칠한다.
      일수(forecastDays)로는 안 된다: 재진입 때 22→22 로 값이 같아 watch 가 안 깨어나 핀이 회색으로 굳는다 */
  forecastVersion: 0,

  di: 0,                 // 선택한 날짜 (오늘로부터 며칠 뒤)
  sel: null,             // 상세를 연 장소
  sort: 'calm',
  course: null,
  courseDay: 'all',
  coursePanel: true,     // 코스 패널 펼침 여부. ×는 패널만 접고 코스(핀·경로·URL)는 남긴다 - 지우는 건 '코스 지우기'뿐(2026-09-14 결정)
  filterOffset: 0,       // 업종 필터 캐러셀 위치
  F: { reg: savedRegion(), cat: '' },   // reg 기본 '전체'(탭 안 마지막 선택 기억), cat='' = 모든 종류
  // 기본은 관광지 핀만 - 착한가격(271)까지 켜면 전체 권역에서 분홍 마커가 혼잡 색을 가린다. 칩으로 켠다 (2026-09-11 결정)
  L: { crowd: 1, spot: 1, food: 0, dine: 0, cafe: 0, cvs: 0, stay: 0, mart: 0, rain: 1 },
  /** 로그인한 회원이 찜한 장소 ID (MAP_009). 백엔드 /favorites 가 원본이고 이건 화면용 사본. 비로그인이면 빈 배열 */
  favIds: [],
  toast: '',
})

watch(() => state.F.reg, r => {
  try { sessionStorage.setItem(REGION_KEY, r) } catch { /* 저장소 접근 불가 - 기억만 못 할 뿐 */ }
})

/* ── 파생값 ── */

/** 화면이 SPOTS/FOOD 를 직접 import 하던 자리를 대신한다 */
export const spots = computed(() => state.layers.spot)
export const foods = computed(() => state.layers.food)

/**
 * 세부분류 드롭다운 (MAP-01).
 *
 * 가나다순이 아니라 <b>장소가 많은 순</b>이다 - 실데이터 기준 110종인데 그중 67종이
 * 5곳 미만이라, 가나다순으로 두면 1곳짜리 분류가 위에 오고 오름(92곳)이 한참 밑으로 간다.
 */
export const CATEGORIES = computed(() => {
  const count = new Map()
  for (const s of state.layers.spot) {
    if (s.c) count.set(s.c, (count.get(s.c) ?? 0) + 1)
  }
  return [...count.entries()]
    .sort((a, b) => b[1] - a[1] || a[0].localeCompare(b[0]))
    .map(([name, n]) => ({ name, n }))
})

/**
 * 업종 칩 토글. 비어 있는 레이어는 켜는 순간 받아온다 - 대용량(카페·편의점·마트 5,419곳)은
 * 첫 진입에 싣지 않아서, 첫 진입 때 실패한 기본 레이어는 재시도가 되도록.
 * 한 번 받으면 메모리에 남아 재요청이 없다. 실패하면 칩을 되돌린다 - 켜진 칩에 핀이 없는
 * 상태 불일치를 남기지 않는다.
 */
const layerLoading = new Set()
export async function toggleLayer (key) {
  state.L[key] ^= 1
  if (!state.L[key]) return
  if (state.layers[key].length || layerLoading.has(key)) return
  layerLoading.add(key)
  const rows = await MapPlaceService.getLayer(key)
  layerLoading.delete(key)
  if (rows) {
    state.layers[key] = rows
    state.loadFailed = state.loadFailed.filter(k => k !== key)
  } else {
    state.L[key] = 0
    toast('데이터를 불러오지 못했어요 — 칩을 다시 켜면 재시도해요')
  }
}

/**
 * 딥링크(?place=id) 복원 — 공유 링크·마이페이지 "장소 보기"가 이걸 탄다.
 * 이미 받아온 레이어에서 먼저 찾고, 없으면 지연 레이어(관광지 외 전부, LAZY_LAYERS 순서)를
 * 하나씩 내려받아 찾는다. 찾은 장소의 업종 칩은 켠다 - 핀이 보여야 상세가 말이 된다.
 */
export async function findPlaceById (id) {
  for (const [k, rows] of Object.entries(state.layers)) {
    const p = rows.find(x => x.id === id)
    if (p) { state.L[k] = 1; return { place: p, error: false } }
  }
  // 장소를 하나도 못 받은 상태(백엔드 다운)면 더 두드리지 않는다
  if (!state.live) return { place: null, error: true }
  // 상세 한 건으로 있는 장소인지·어느 업종인지 먼저 본다. 전엔 지연 레이어 6개(3MB)를 순서대로 다 받은 뒤에야
  // "없는 장소"를 알았고, 카페 링크 하나에 식당·숙소까지 받았다(최종점검 #49)
  const { place: single, missing } = await MapPlaceService.getById(id)
  if (missing) return { place: null, error: false }
  if (!single) return { place: null, error: true }
  // 그 업종 레이어 하나만 받아 목록의 같은 객체로 연다 - 핀·찜·예보가 목록 객체에 붙어 있다
  const k = layerOf(single)
  if (k && !state.layers[k].length && !layerLoading.has(k)) {
    layerLoading.add(k)
    const rows = await MapPlaceService.getLayer(k)
    layerLoading.delete(k)
    if (rows) {
      state.layers[k] = rows
      state.loadFailed = state.loadFailed.filter(x => x !== k)
      const p = rows.find(x => x.id === id)
      if (p) { state.L[k] = 1; return { place: p, error: false } }
    }
  }
  // 목록에 없는 장소(폐업 등)는 상세 객체로 연다 - 선택 핀은 레이어 밖이라도 뜬다
  return { place: single, error: false }
}

/** 장소의 업종 → 그 장소가 들어 있는 레이어. 착한가격은 업종과 무관하게 food, 쇼핑 등은 어느 레이어에도 없다(null) */
export function layerOf (p) {
  if (p.cat === 'TOURIST') return 'spot'
  if (p.good) return 'food'
  return { FOOD: 'dine', CAFE: 'cafe', LODGING: 'stay', CONVENIENCE: 'cvs', MART: 'mart' }[p.cat] ?? null
}

/* ── 재진입 재사용 ──
   스토어는 모듈 전역이라 지도를 나갔다 와도 장소·예보(series)·날씨 캐시가 메모리에 다 있다.
   그런데도 진입마다 처음부터 다시 받으면(요청 9건·1MB) 그동안 목록이 "불러오는 중"으로 비고,
   장소가 도착하는 순간 예보 안 붙은 새 객체로 바뀌어 핀이 전부 회색 + "예보가 있는 곳이 없어요"가 잠깐 뜬다(3G 실측 0.5초).
   장소 02:20·예보 03:00·날씨 03:30/06:30, 하루 한두 번 갱신이라 12시간 묵어도 서버와 같다.
   날짜가 바뀌면 예보가 '오늘' 기준으로 붙어 있어 기간 안이라도 다시 받는다.
   새로고침·새 탭은 메모리가 비어 어차피 처음부터 받는다 (2026-09-12, docs/성능_브랜치B_재분석_MAP_20260912.md) */
const REUSE_MS = 12 * 60 * 60 * 1000
let loadedAt = 0        // 마지막으로 전부 제대로 받은 시각(ms). 0 = 아직
let loadedDate = ''     // 그때의 날짜(YYYY-MM-DD) - attachSeries 가 쓰는 iso() 와 같은 기준

/** 마지막으로 전부 받은 지 12시간 안이고 같은 날이면 true - loadPlaces 가 요청을 건너뛴다 */
export function canReuse (now = new Date()) {
  return loadedAt > 0 && now.getTime() - loadedAt < REUSE_MS && iso(now) === loadedDate
}

/**
 * 장소·예보를 받아 state에 채운다. 지도 화면에 들어올 때마다 호출하지만, 같은 날 12시간 안 재진입은 건너뛴다.
 * 예보는 장소보다 늦게 와도 되므로 따로 기다렸다가 붙인다 - 지도가 먼저 뜬다.
 */
export async function loadPlaces () {
  if (canReuse()) return
  // 자정을 넘긴 탭의 재진입: 오늘 기준을 옮기고 선택 날짜를 오늘로 되돌린다 - 글자·달력·날씨가 새로 받는 데이터와 같은 날을 가리키게(최종점검 #16)
  if (refreshToday()) state.di = 0
  state.loading = true
  // 예보·날씨는 장소 목록과 무관하니 목록과 같이 출발시킨다. 전엔 목록을 다 받은 뒤에야 예보를 요청하고 날씨까지 온 다음
  // 색을 칠해서, 지도를 열면 핀이 전부 회색이었다가 1~2초 뒤 색이 바뀌었다(운영 실측 목록 0.6초 + 예보 0.4초·날씨 0.5초,
  // 날씨 DB가 비면 기상청 실시간 호출로 더). 둘 다 실패를 안에서 잡아 값으로 돌려주므로 미리 시작해도 처리 안 된 오류는 없다
  const forecastP = CrowdService.getForecast()
  const weatherP = WeatherService.load()
  const { live, layers, fetched, failed } = await MapPlaceService.getAll()
  // 받으려고 한 레이어만 갈아끼운다. 전엔 통째로 교체해서 칩으로 받아 둔 지연 레이어(카페·식당…)가 빈 배열이 됐고,
  // 칩은 켜진 채 핀만 사라져 두 번 눌러야 돌아왔다(최종점검 #28 - 9/12 재사용 커밋이 만든 회귀). 지연 레이어도 하루 한두 번
  // 갱신되는 데이터라 재진입 때 묵은 채 두는 게 12시간 재사용 규칙과 같다
  for (const k of fetched) state.layers[k] = layers[k]
  state.live = live
  state.loadFailed = failed
  state.loading = false
  // 전부 실패는 화면 배너(MapView)가 맡고, 일부 실패만 여기서 알린다
  if (live && failed.length) {
    const names = failed.map(k => LAYERS.find(l => l.k === k)?.t ?? k).join('·')
    toast(`${names} 데이터를 불러오지 못했어요 — 칩을 다시 켜면 재시도해요`)
  }

  // 핀 색은 예보만 오면 바로 칠한다 - 날씨는 핀 색과 무관하다
  const forecast = await forecastP
  state.forecastDays = forecast.days
  attachSeries(state.layers.spot, forecast, iso(today()))
  state.forecastUntil = forecastUntilOf(state.layers.spot)
  state.forecastVersion++
  // 전부 제대로 받았을 때만 기록한다. 레이어 하나라도 못 받았거나 예보가 비어 왔으면 기록하지 않아
  // 다음 진입에 처음부터 다시 받는다 - "실패한 것만 골라 재시도"하는 코드 없이 재시도가 된다
  if (live && !failed.length && forecast.live) {
    loadedAt = Date.now()
    loadedDate = iso(new Date())
  }
  // 날씨까지 받은 뒤 끝낸다 - 호출부(MapView)가 기다리는 시점은 전과 같다
  await weatherP
}

/** 오늘 기준 예보가 있는 마지막 날 인덱스. attachSeries 뒤의 series 는 0 = 오늘이라 값이 있는 가장 뒤 칸이 답이다.
    장소마다 빠진 날이 달라 "전체 중 가장 뒤"로 잡는다 - 어떤 장소든 예보가 있는 마지막 날. 하나도 없으면 -1 */
export function forecastUntilOf (spots) {
  let last = -1
  for (const s of spots) {
    const arr = s.series
    if (!Array.isArray(arr)) continue
    for (let i = arr.length - 1; i > last; i--) if (arr[i] != null) { last = i; break }
  }
  return last
}

/** 장소 식별자 - id 가 있으면 id, 없으면(목업·검색 API 임시 객체) 이름.
    상세 갱신 감시·패널 key·선택 강조·목록 key 가 전부 이걸 쓴다. 이름은 식별자가 못 된다:
    같은 이름 장소가 310그룹 802곳(스타벅스 35·씨유 44, 관광지↔카페 동명 보롬왓·거문오름·미깡창고).
    이름으로 감시하던 시절엔 카페 A→카페 B 로 바꿔도 상세를 다시 안 받아 이전 장소의 사진·메뉴가 남았다(2026-09-13) */
export const placeKey = p => (p?.id ?? p?.n)

/** 지역·종류 필터를 함께 적용 */
export const inFilter = s =>
  (state.F.reg === '전체' || s.r === state.F.reg) && (!state.F.cat || s.c === state.F.cat)

export const inRegion = o => state.F.reg === '전체' || o.r === state.F.reg

/** 좌측 목록 — 필터 안 관광지 전체를 정렬해 보여준다. 집중률 결측 장소는 순위에서 제외 (지도에는 회색 핀으로 남는다) */
export const rankedRows = computed(() =>
  state.layers.spot.filter(s => inFilter(s) && crowd(s, state.di) != null)
    .map(s => ({ s, c: crowd(s, state.di), t: tier(crowd(s, state.di)) }))
    .sort((a, b) => (state.sort === 'calm' ? a.c - b.c : b.c - a.c)))

/* ── 액션 ── */
let toastTimer = null
export function toast(msg) {
  state.toast = msg
  clearTimeout(toastTimer)
  toastTimer = setTimeout(() => { state.toast = '' }, 1900)
}

const currentUser = () => useAuthStore().user

/* ── MAP_009 찜 — 회원 전용. 백엔드 /favorites 가 단일 저장소라 마이페이지 찜 목록과 항상 같다 ── */

let favOwner = null
/**
 * 로그인한 회원의 찜 장소 ID를 받아온다. MapView 가 로그인 상태가 바뀔 때마다 부른다(로그아웃이면 null).
 * 실패해도 던지지 않는다 - 하트만 안 켜질 뿐 지도는 정상이어야 한다. 늦게 온 응답이 다른 계정 것을 덮지 않게 주인을 확인한다.
 */
export async function loadFavorites (userId) {
  favOwner = userId ?? null
  if (favOwner == null) { state.favIds = []; return }
  try {
    const ids = await FavoriteApiService.ids()
    if (favOwner === userId) state.favIds = ids
  } catch (e) {
    console.error('찜 목록을 불러오지 못했어요', e)
  }
}

export const isFav = place => place?.id != null && state.favIds.includes(place.id)

function setFav (id, on) {
  const i = state.favIds.indexOf(id)
  if (on && i < 0) state.favIds.push(id)
  if (!on && i >= 0) state.favIds.splice(i, 1)
}

/**
 * 하트 토글. 화면을 먼저 바꾸고 서버에 알린다 - 응답을 기다렸다 바꾸면 늦게 켜지는 하트를 두 번 누르게 된다.
 * 실패하면 되돌리고 알린다. 비로그인은 토스트만(로그인 페이지로 보내지 않는다 - 2026-09-07 결정).
 * @returns {Promise<boolean>} 서버에 반영됐으면 true
 */
export async function toggleFav (place) {
  if (!currentUser()) { toast('찜은 로그인이 필요해요'); return false }
  if (place?.id == null) { toast('이 장소는 찜할 수 없어요'); return false }
  const on = !isFav(place)
  setFav(place.id, on)
  try {
    const res = on ? await FavoriteApiService.add(place.id) : await FavoriteApiService.remove(place.id)
    setFav(place.id, res.favorited)   // 서버가 말한 상태가 정답이다(연타로 순서가 엇갈려도 마지막 응답 기준)
    toast(res.favorited ? '찜했어요 — 마이페이지에서 볼 수 있어요' : '찜을 해제했어요')
    return true
  } catch (e) {
    setFav(place.id, !on)
    toast(e?.status === 401
      ? '로그인이 만료됐어요 — 다시 로그인해 주세요'
      : '찜을 저장하지 못했어요 — 잠시 후 다시 시도해 주세요')
    return false
  }
}

export { today }
