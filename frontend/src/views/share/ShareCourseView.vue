<script setup lang="ts">
import { computed, onBeforeUnmount, ref, watch } from 'vue'
import { useRoute } from 'vue-router'
import CourseShareService, { type SharedCourse } from '../../services/CourseShareService'
import KakaoMap from '../../components/map/KakaoMap.vue'
import AppIcon from '../../components/common/AppIcon.vue'
import UnavailableCard from '../../components/common/UnavailableCard.vue'
import { stayDuration } from '../../services/course/stayDuration'
import { dayWeatherLabels } from '../../services/course/dailyWeather'
import type { Place } from '../../assets/types'
import { sheetHeight } from '../../composables/useSavedCourseExplorer'
const route = useRoute()
const loading = ref(true), unavailable = ref(false), error = ref('')
const course = ref<SharedCourse | null>(null)
const activeDay = ref(1), selectedStop = ref(''), mapFailed = ref(false), mapAttempt = ref(0)
const workspace = ref<HTMLElement>()
const panelHeight = ref(55)
let drag: { id: number; y: number; height: number; moved: boolean } | null = null
let suppressClick = false
function dragStart(event: PointerEvent) {
  if (event.button !== 0) return
  suppressClick = false
  drag = { id: event.pointerId, y: event.clientY, height: panelHeight.value, moved: false }
  ;(event.currentTarget as HTMLElement).setPointerCapture(event.pointerId)
}
function dragMove(event: PointerEvent) {
  if (!drag || drag.id !== event.pointerId) return
  const delta = event.clientY - drag.y
  if (Math.abs(delta) > 4) drag.moved = true
  if (drag.moved) panelHeight.value = sheetHeight(drag.height, delta, workspace.value?.clientHeight ?? 0)
}
function dragEnd(event: PointerEvent) {
  if (!drag || drag.id !== event.pointerId) return
  suppressClick = drag.moved; drag = null
}
function togglePanel() {
  if (suppressClick) { suppressClick = false; return }
  panelHeight.value = panelHeight.value > 23 ? 18 : 55
}
function panelKey(event: KeyboardEvent) {
  if (!['ArrowUp', 'ArrowDown', 'Home', 'End'].includes(event.key)) return
  event.preventDefault()
  panelHeight.value = event.key === 'Home' ? 18 : event.key === 'End' ? 90
    : sheetHeight(panelHeight.value, event.key === 'ArrowUp' ? -15 : 15, 100)
}
let sequence = 0, alive = true
const policies = ['robots', 'referrer'].map((name, i) => {
  const existing = document.head.querySelector<HTMLMetaElement>(`meta[name="${name}"]`)
  const node = existing ?? document.createElement('meta'), previous = node.getAttribute('content')
  node.name = name; node.content = i === 0 ? 'noindex, nofollow, noarchive' : 'no-referrer'
  if (!existing) document.head.append(node)
  return () => { if (!existing) node.remove(); else if (previous == null) node.removeAttribute('content'); else node.content = previous }
})
async function load() {
  const request = ++sequence
  loading.value = true; error.value = ''; unavailable.value = false; course.value = null
  try {
    const result = await CourseShareService.getPublic(String(route.params.token))
    if (!alive || request !== sequence) return
    course.value = result; activeDay.value = result.days[0]?.day_no ?? 1
    selectedStop.value = String(result.days[0]?.items[0]?.id ?? '')
  } catch (e) {
    if (!alive || request !== sequence) return
    unavailable.value = (e as { code?: number }).code === 3310 || (e as { status?: number }).status === 404
    error.value = unavailable.value ? '이 공유 코스를 볼 수 없어요.' : '코스를 불러오지 못했어요.'
  } finally { if (alive && request === sequence) loading.value = false }
}
watch(() => route.params.token, load, { immediate: true })
onBeforeUnmount(() => { alive = false; sequence++; policies.forEach(restore => restore()) })
const currentDay = computed(() => course.value?.days.find(day => day.day_no === activeDay.value))
watch(activeDay, () => { selectedStop.value = String(currentDay.value?.items[0]?.id ?? '') })
const transport = computed(() => ({ RENTAL_CAR: '차량', TAXI: '택시', PUBLIC_TRANSIT: '대중교통', WALK_BIKE: '도보·자전거' }[course.value?.transport ?? ''] ?? '이동수단 정보 없음'))
const mapPlaces = computed<Place[]>(() => (currentDay.value?.items ?? []).map(item => ({
  id: String(item.id), name: item.place_name, latitude: item.latitude ?? undefined, longitude: item.longitude ?? undefined,
  region: item.region_name ?? '', category: item.category_name ?? '', address: '', description: '', image: item.image_url ?? '',
  score: item.congestion_rate ?? 0, level: item.congestion_level ?? 'QUIET', congestionUnknown: item.congestion_level == null,
  pinLabel: String(item.position), pinDescription: `${item.position}번째 방문지`, time: '', stay: '', cost: '', tags: [],
})))
</script>
<template>
  <main class="shared-course">
    <p v-if="loading" class="share-message" role="status">공유된 코스를 불러오는 중이에요…</p>
    <div v-else-if="unavailable" class="doc">
      <UnavailableCard title="이 공유 코스를 볼 수 없어요." description="공유가 중지되었거나 삭제된 코스일 수 있어요.">
        <RouterLink class="btn2 primary" to="/ai-course">내 코스 만들기</RouterLink>
      </UnavailableCard>
    </div>
    <section v-else-if="error" class="share-message" role="alert"><h1>{{ error }}</h1><p>잠시 후 다시 시도해 주세요.</p><button type="button" @click="load">다시 시도</button><RouterLink to="/ai-course">내 코스 만들기</RouterLink></section>
    <template v-else-if="course">
      <div ref="workspace" class="shared-layout" :style="{ '--sheet-height': panelHeight + '%' }">
        <section class="shared-itinerary" aria-label="공유 코스 일정">
          <button class="sheet-handle" type="button" aria-label="공유 일정 패널 크기 조절" :aria-expanded="panelHeight > 23" aria-controls="shared-sheet-body" @click="togglePanel" @pointerdown="dragStart" @pointermove="dragMove" @pointerup="dragEnd" @pointercancel="dragEnd" @keydown="panelKey"><span /></button>
          <header class="share-heading"><span class="share-label">공유받은 코스</span><h1>{{ course.title || '제주 여행 코스' }}</h1><p>{{ course.start_date }} ~ {{ course.end_date }} · {{ transport }}</p></header>
          <div id="shared-sheet-body" class="shared-sheet-body">
          <p v-if="!course.days.length">등록된 일정이 없어요.</p>
          <section v-for="day in course.days" :key="day.day_no" class="itinerary-day">
            <button class="day-heading" type="button" :aria-expanded="activeDay === day.day_no" :aria-controls="`shared-day-${day.day_no}`" @click="activeDay = day.day_no"><span class="day-number">DAY {{ day.day_no }}</span><strong>{{ day.visit_date }}</strong></button>
            <div v-if="activeDay === day.day_no" class="day-weather"><p v-for="label in dayWeatherLabels(day.items.map(item => ({ visit_date: day.visit_date, weather: item.weather })))" :key="label">{{ label }}</p></div>
            <ol v-if="activeDay === day.day_no" :id="`shared-day-${day.day_no}`" class="stop-list">
              <li v-for="(item, index) in day.items" :key="item.id"><span class="stop-number">{{ item.position }}</span><article class="place-stop" :class="{ selected: selectedStop === String(item.id) }">
                <button type="button" class="stop-card" :aria-pressed="selectedStop === String(item.id)" @click="selectedStop = String(item.id)">
                  <span class="stop-photo"><AppIcon name="album" :size="24" /><img v-if="item.image_url" :src="item.image_url" alt="" loading="lazy" referrerpolicy="no-referrer" @error="($event.target as HTMLImageElement).hidden = true"></span>
                  <span class="stop-copy"><span class="stop-time">{{ item.start_time?.slice(0, 5) || `${item.position}번째 방문` }}</span><strong>{{ item.place_name }}</strong><span class="stop-description">{{ [item.category_name, item.region_name].filter(Boolean).join(' · ') }}</span>
                    <span class="stop-facts"><span v-if="item.congestion_level" class="crowd-pill" :class="item.congestion_level.toLowerCase()">{{ item.congestion_label || ({ QUIET: '한산', NORMAL: '보통', CROWDED: '혼잡' }[item.congestion_level]) }}</span><small v-else>혼잡 예보 없음</small></span>
                    <small v-if="stayDuration(item.start_time, item.end_time)">체류 {{ stayDuration(item.start_time, item.end_time) }}</small>
                    <small v-if="item.congestion_rate != null" class="concentration">집중률 {{ item.congestion_rate.toFixed(1) }}%</small>
                    <small v-if="item.place_business_status === 'CLOSED'" class="closed-place">폐업</small><small v-else-if="item.place_business_status === 'TEMP_CLOSED'">임시 휴업</small>
                  </span>
                </button>
                <div class="stop-footer"><span v-if="index > 0 && (item.inbound_travel_minutes != null || item.inbound_distance_m != null)">이전 장소에서 <template v-if="item.inbound_travel_minutes != null">약 {{ item.inbound_travel_minutes }}분 </template><template v-if="item.inbound_distance_m != null">{{ (item.inbound_distance_m / 1000).toFixed(1) }}km</template></span><span v-else>{{ index === 0 ? '첫 방문지' : '이동 정보 없음' }}</span><RouterLink class="place-detail" :to="`/places/${item.place_id}`" rel="noreferrer">상세 보기</RouterLink></div>
              </article></li>
            </ol>
          </section>
          </div>
        </section>
        <section class="shared-map" aria-label="방문 순서 지도"><KakaoMap v-if="!mapFailed" :key="mapAttempt" :places="mapPlaces" :selected-id="selectedStop" show-route @select="selectedStop = $event.id" @error="mapFailed = true" /><div v-else class="share-message" role="status">지도를 불러오지 못했어요. 일정은 계속 볼 수 있어요.<button type="button" @click="mapFailed = false; mapAttempt++">지도 다시 시도</button></div><p class="shared-map-caption">방문 순서 연결선 · 실제 도로 경로가 아니에요.</p></section>
      </div>
    </template>
  </main>
</template>
<style scoped src="../course/savedCourses.css"></style>
<style scoped>
.shared-course { background:var(--surface); color:var(--text); }
.share-heading { padding:20px 24px 16px; border-bottom:1px solid var(--line); flex-shrink:0; background:var(--surf2); } h1 { font-size:22px; margin:5px 0 8px; overflow-wrap:anywhere; } .share-label { color:var(--primary); font-size:11px; font-weight:700; } .share-heading p { color:var(--tx2); line-height:1.6; font-size:12px; margin:0; }
.shared-layout { position:relative; display:grid; grid-template-columns:minmax(350px,44%) minmax(0,1fr); height:calc(100dvh - var(--nav-h) - var(--mobile-tabbar-h)); min-height:400px; overflow:hidden; }
.shared-itinerary { display:flex; flex-direction:column; min-width:0; min-height:0; border-right:1px solid var(--line); background:var(--surface); }
.shared-sheet-body { overflow-y:auto; min-height:0; flex:1; padding:12px 24px 24px; overscroll-behavior:contain; }
.shared-map { height:100%; min-width:0; position:relative; overflow:hidden; background:var(--surf); }
.shared-map :deep(.kakao-map) { height:100%; width:100%; min-height:0; }
.shared-map-caption { position:absolute; bottom:12px; left:12px; right:12px; padding:10px; background:var(--surf); border-radius:10px; text-align:center; font-size:12px; }
.share-message { padding:40px 20px; text-align:center; display:grid; gap:16px; } .share-message button { padding:12px; border:1px solid var(--line); border-radius:10px; }
.stop-copy small { line-height:1.6; } .day-heading { width:100%; }
.day-weather { font-size:11px; color:var(--tx2); line-height:1.7; margin:-8px 0 16px; } .closed-place { color:var(--busy); }
@media(max-width:760px) {
 .shared-layout { display:block; min-height:0; }
 .shared-map { position:absolute; inset:0 0 var(--sheet-height); height:auto; width:100%; }
 .shared-itinerary { position:absolute; inset:auto 0 0; height:var(--sheet-height); width:100%; z-index:2; border:0; border-radius:24px 24px 0 0; box-shadow:0 -6px 30px #0002; overflow:hidden; }
 .share-heading { padding:0 16px 12px; background:var(--surface); } h1 { font-size:18px; margin:3px 0 5px; } .share-heading p { font-size:11px; }
 .shared-sheet-body { padding:8px 16px 24px; }
 .shared-map-caption { top:10px; bottom:auto; font-size:10px; padding:7px; }
}
</style>
