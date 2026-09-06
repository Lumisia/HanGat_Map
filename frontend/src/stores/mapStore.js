import { reactive, computed } from 'vue'
import { useAuthStore } from '@/stores/auth'
import { crowd, tier } from '@/utils/crowd'
import { at, iso, ago, D0 } from '@/utils/date'
import MapPlaceService, { LAZY_LAYERS } from '@/services/map/MapPlaceService'
import CrowdService, { attachSeries } from '@/services/map/CrowdService'
import WeatherService from '@/services/map/WeatherService'

/* 지도 페이지 전역 상태.
   Pinia와 같은 모양(state + action)으로 두어 나중에 옮기기 쉽게 했다.
   지금은 페이지가 하나뿐이라 의존성을 늘리지 않고 reactive() 하나로 충분하다 */

const readLS = (k, fallback) => {
  try { const v = localStorage.getItem(k); return v ? JSON.parse(v) : fallback } catch { return fallback }
}
const writeLS = (k, v) => {
  try { localStorage.setItem(k, JSON.stringify(v)); return true } catch { return false }
}

export const REGIONS = ['전체', '동부', '서부', '남부', '북부']

/** 업종 필터 (MAP_001) — 한 번에 4개씩 보이고 좌우로 넘긴다 */
export const LAYERS = [
  { k: 'spot', t: '관광지' }, { k: 'food', t: '착한가격' }, { k: 'dine', t: '식당' },
  { k: 'cafe', t: '카페' }, { k: 'cvs', t: '편의점' }, { k: 'stay', t: '숙소' }, { k: 'mart', t: '마트' },
]
export const FILTER_VISIBLE = 4

export const state = reactive({
  /* ── 서버에서 받아오는 장소 데이터 ──
     하드코딩 시절엔 import 하는 순간 값이 있었지만 이제 비동기라 처음엔 비어 있다.
     reactive 라서 loadPlaces() 가 채우면 화면이 알아서 다시 그려진다 */
  layers: { spot: [], food: [], dine: [], cafe: [], cvs: [], stay: [], mart: [] },
  loading: true,
  /** false = 백엔드가 죽어 하드코딩 폴백으로 그리는 중 (화면에 표시할 근거) */
  live: false,
  /** 예보 일수. 화면은 30일 캘린더인데 실측은 21~22일이라 남는 날은 '정보 없음' */
  forecastDays: 0,

  di: 0,                 // 선택한 날짜 (오늘로부터 며칠 뒤)
  sel: null,             // 상세를 연 장소
  sort: 'calm',
  course: null,
  courseDay: 'all',
  filterOffset: 0,       // 업종 필터 캐러셀 위치
  F: { reg: '서부', bud: 150000, cat: '' },   // cat='' = 모든 종류
  L: { crowd: 1, spot: 1, food: 1, dine: 0, cafe: 0, cvs: 0, stay: 0, mart: 0, rain: 1 },
  favs: readLS('hangat_favs', []),
  courses: readLS('hangat_courses', []),
  toast: '',
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
 * 업종 칩 토글. 대용량 레이어(카페·편의점·마트 5,419곳)는 첫 진입에 싣지 않고
 * 처음 켜는 순간 받아온다 - 한 번 받으면 메모리에 남아 재요청이 없다.
 */
const layerLoading = new Set()
export async function toggleLayer (key) {
  state.L[key] ^= 1
  if (!state.L[key] || !state.live || !LAZY_LAYERS.includes(key)) return
  if (state.layers[key].length || layerLoading.has(key)) return
  layerLoading.add(key)
  const rows = await MapPlaceService.getLayer(key)
  layerLoading.delete(key)
  if (rows) state.layers[key] = rows
  else toast('데이터를 불러오지 못했어요 — 칩을 껐다 다시 켜 주세요')
}

/**
 * 딥링크(?place=id) 복원 — 공유 링크·마이페이지 "장소 보기"가 이걸 탄다.
 * 이미 받아온 레이어에서 먼저 찾고, 없으면 지연 레이어(카페·편의점·마트)를
 * 하나씩 내려받아 찾는다. 찾은 장소의 업종 칩은 켠다 - 핀이 보여야 상세가 말이 된다.
 */
export async function findPlaceById (id) {
  for (const [k, rows] of Object.entries(state.layers)) {
    const p = rows.find(x => x.id === id)
    if (p) { state.L[k] = 1; return p }
  }
  if (!state.live) return null
  for (const k of LAZY_LAYERS) {
    if (state.layers[k].length || layerLoading.has(k)) continue
    layerLoading.add(k)
    const rows = await MapPlaceService.getLayer(k)
    layerLoading.delete(k)
    if (!rows) continue
    state.layers[k] = rows
    const p = rows.find(x => x.id === id)
    if (p) { state.L[k] = 1; return p }
  }
  return null
}

/**
 * 장소·예보를 받아 state에 채운다. 지도 화면 진입 시 한 번 호출한다.
 * 예보는 장소보다 늦게 와도 되므로 따로 기다렸다가 붙인다 - 지도가 먼저 뜬다.
 */
export async function loadPlaces () {
  state.loading = true
  const { live, layers } = await MapPlaceService.getAll()
  state.layers = layers
  state.live = live
  state.loading = false

  const [forecast] = await Promise.all([CrowdService.getForecast(), WeatherService.load()])
  state.forecastDays = forecast.days
  attachSeries(state.layers.spot, forecast, iso(new Date()))
}

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
const currentUserName = user => user?.nickname || user?.name || '한갓이'

/** MAP_009 찜 — 회원 전용 */
export function toggleFav(name) {
  if (!currentUser()) { toast('찜은 로그인이 필요해요'); return false }
  const i = state.favs.indexOf(name)
  if (i >= 0) state.favs.splice(i, 1); else state.favs.push(name)
  writeLS('hangat_favs', state.favs)
  toast(i >= 0 ? '찜을 해제했어요' : '찜했어요 — 마이페이지에서 볼 수 있어요')
  return true
}
export const isFav = name => state.favs.includes(name)

/* ── 코스 저장 (MY_001) ── */
/** 같은 조건·같은 경유지면 같은 코스로 보고 중복 저장을 막는다 */
export const courseKey = c =>
  `${state.F.reg}|${iso(at(state.di))}|${c.stops.map(s => (s.o ? s.o.n : s.f.n)).join('|')}`

export const isCourseSaved = () =>
  !!state.course && state.courses.some(c => c.key === courseKey(state.course))

export function saveCourse(title) {
  if (!currentUser()) { toast('코스 저장은 로그인이 필요해요'); return false }
  const c = state.course
  if (!c) return false
  const key = courseKey(c)
  if (state.courses.some(x => x.key === key)) { toast('이미 저장한 코스예요'); return false }
  state.courses.unshift({
    key, title, region: state.F.reg, startDate: iso(at(state.di)), days: c.days,
    budget: c.bud, spent: c.spent, avg: c.avg, move: c.move,
    stops: c.stops.map(s => (s.o ? s.o.n : s.f.n)), savedAt: iso(new Date()),
  })
  if (!writeLS('hangat_courses', state.courses)) toast('저장 공간이 가득 찼어요 (데모 한계)')
  else toast(`'${title}' 저장했어요 · 마이페이지에서 볼 수 있어요`)
  return true
}

export { D0 }
