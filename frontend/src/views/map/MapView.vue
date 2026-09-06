<script setup>
/* 지도 페이지 (MAP_001~009) — 패널 배치와 컴포넌트 사이 연결을 담당한다 */
import { ref, computed, watch, onMounted } from 'vue'
import MapCanvas from '@/components/map/MapCanvas.vue'
import FilterPanel from '@/components/map/FilterPanel.vue'
import DatePicker from '@/components/map/DatePicker.vue'
import PlaceDetail from '@/components/map/PlaceDetail.vue'
import CoursePanel from '@/components/map/CoursePanel.vue'
import PhotoLightbox from '@/components/map/PhotoLightbox.vue'
import { state, toast, loadPlaces, findPlaceById } from '@/stores/mapStore'

import { refreshCourse, courseFromNames } from '@/utils/course'
import { at, iso, D0, FORECAST_DAYS } from '@/utils/date'
import { useRouter, useRoute } from 'vue-router'
import { popAiCourse, toMapCourse, toMapCourseFromDetail } from '@/services/map/CourseBridge'
import CourseService from '@/services/CourseService'

const router = useRouter()
const route = useRoute()
import { mapBridge } from '@/composables/mapBridge'
import MapToast from '@/components/map/MapToast.vue'

const lightbox = ref(null)

/* 열린 패널 수만큼 날짜 버튼이 오른쪽으로 비켜난다 */
const openCount = computed(() => (state.sel ? 1 : 0) + (state.course ? 1 : 0))

function openPlace(nameOrSpot) {
  const s = typeof nameOrSpot === 'string' ? state.layers.spot.find(x => x.n === nameOrSpot) : nameOrSpot
  if (!s) return
  if (!s) return
  mapBridge.panTo(s.y, s.x)
  state.sel = s
}
const closeDetail = () => { state.sel = null }

/* 열린 장소를 URL에 반영한다(?place=id) — 링크 복사·새로고침·공유가 이 값으로 복원된다.
   다른 파라미터(?course= 등)는 건드리지 않는다. 목업 장소는 id가 없어 쓰지 않는다 */
function syncPlaceURL() {
  const q = new URLSearchParams(location.search)
  q.delete('placeId')   // 구형 파라미터는 place 로 정규화한다 - 남기면 닫아도 새로고침에 되살아난다
  if (state.sel?.id != null) q.set('place', state.sel.id)
  else q.delete('place')
  const qs = q.toString()
  history.replaceState(null, '', qs ? '?' + qs : location.pathname)
}
watch(() => state.sel, syncPlaceURL)

function toggleCourse() {
  if (state.course) {
    state.course = null
    state.courseDay = 'all'
    history.replaceState(null, '', location.pathname)
    syncPlaceURL()   // 코스 URL을 지워도 열려 있는 장소는 남긴다
    return
  }
  // 샘플 생성기 대신 실 기능으로 안내한다 - 가짜 코스를 화면에 올리지 않는다
  router.push('/ai-course')
}

/** placeId → 적재 장소. 매칭되면 코스 핀 클릭 시 상세도 열린다 */
function placeFinder() {
  const byId = new Map(state.layers.spot.filter(p => p.id != null).map(p => [p.id, p]))
  return id => (id != null && byId.get(id)) || null
}

/** 변환된 코스를 화면에 올린다 - 슬라이더를 여행 시작일로(예보 밖이면 오늘 유지), 경로가 다 보이게 줌 */
function applyCourse(course) {
  state.course = course
  state.courseDay = 'all'
  const k = Math.round((new Date(course.startDate + 'T00:00:00') - D0) / 864e5)
  if (k >= 0 && k < FORECAST_DAYS) state.di = k
  const pts = course.stops.map(s => [s.o.y, s.o.x])
  if (pts.length > 1) mapBridge.fitPoints(pts, 12)
}

/** AI코스 페이지가 담아 둔 코스를 꺼내 그린다 (?course=ai) */
function loadAiCourse() {
  const raw = popAiCourse()
  if (!raw) return false
  applyCourse(toMapCourse(raw, placeFinder()))
  return true
}

/** 저장 코스를 URL의 id로 불러와 그린다 (?course=123) - 새로고침·링크 공유가 된다 */
async function loadSavedCourse(id) {
  const detail = await CourseService.getCourseDetail(id)
  if (!detail) { toast('코스를 불러오지 못했어요'); return false }
  applyCourse(toMapCourseFromDetail(detail, placeFinder()))
  return true
}

/* COM_002: 날짜는 순번이 아니라 실제 날짜로 저장한다 —
   기준일(오늘)이 매일 바뀌므로 순번은 링크마다 뜻이 달라진다 */
function syncURL() {
  if (!state.course) return
  history.replaceState(null, '', '?' + new URLSearchParams({
    d: iso(at(state.di)), r: state.F.reg, b: state.course.bud,
    s: state.course.stops.map(x => (x.o ? x.o.n : x.f.n)).join('|'),
  }))
}

function loadFromURL() {
  const p = new URLSearchParams(location.search)
  const dp = p.get('d')
  /* 지난 날짜이거나 예보 범위 밖이면 무시하고 오늘로 둔다 */
  if (dp) {
    const k = Math.round((new Date(dp + 'T00:00:00') - D0) / 864e5)
    if (k >= 0 && k < FORECAST_DAYS) state.di = k
  }
  const r = p.get('r')
  if (r && ['전체', '동부', '서부', '남부', '북부'].includes(r)) state.F.reg = r
  if (p.get('b')) state.F.bud = +p.get('b') || state.F.bud
  const names = (p.get('s') || '').split('|').filter(Boolean)
  if (!names.length) return
  const c = courseFromNames(names, { region: state.F.reg, budget: state.F.bud, dayIndex: state.di })
  if (c) { state.course = c; state.courseDay = 'all' }
}

/* 날짜가 바뀌면 코스의 혼잡도도 다시 계산한다 */
watch(() => state.di, () => {
  // AI·저장 코스의 혼잡은 여행일 기준 값이다 - 슬라이더로 재계산하면 거짓이 된다
  if (state.course?.source) return
  if (state.course) {
    refreshCourse(state.course, { region: state.F.reg, dayIndex: state.di })
    state.course = { ...state.course }
  }
})

/** ?place=(공유 링크·마이페이지) 와 ?placeId=(장소 상세 페이지 링크) 둘 다 받는다 */
async function openPlaceFromURL() {
  const raw = route.query.place ?? route.query.placeId
  if (!/^\d+$/.test(raw ?? '')) return
  const p = await findPlaceById(+raw)
  if (p) openPlace(p)
  else toast('공유받은 장소를 찾지 못했어요')
}

onMounted(async () => {
  // 장소·예보를 먼저 받아야 URL의 ?place= 로 들어온 장소를 찾을 수 있다
  await loadPlaces()
  let courseDrawn = false
  if (route.query.course === 'ai') courseDrawn = loadAiCourse()
  if (!courseDrawn && /^\d+$/.test(route.query.course ?? '')) courseDrawn = await loadSavedCourse(route.query.course)
  if (!courseDrawn) loadFromURL()
  // 코스와 장소가 함께 온 링크도 있다(코스를 보다 장소를 열고 공유) - 코스를 그린 뒤 장소를 연다
  await openPlaceFromURL()
})
</script>

<template>
  <div class="stage" :class="{ both: openCount === 2, 'sheet-open': openCount > 0 }">
    <MapCanvas @select="openPlace" @blank-click="closeDetail" />

    <FilterPanel :mobile-suppressed="openCount > 0"
      @open-place="openPlace" @toggle-course="toggleCourse" />

    <PlaceDetail v-if="state.sel" :place="state.sel" @close="closeDetail"
      @open-place="openPlace" @open-photo="p => lightbox.show(p.photos, p.index)" />

    <CoursePanel @close="state.course = null" @open-place="openPlace" />

    <div class="slid" :class="{ s1: openCount === 1, s2: openCount === 2 }">
      <DatePicker />
    </div>

    <PhotoLightbox ref="lightbox" />
  </div>
  <MapToast />
</template>
