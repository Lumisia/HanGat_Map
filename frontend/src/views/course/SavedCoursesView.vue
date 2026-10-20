<script setup lang="ts">
import { computed, nextTick, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { useAuthStore } from '../../stores/auth.js'
import CourseService, { type CourseCard } from '../../services/CourseService'
import CongestionBadge from '../../components/common/CongestionBadge.vue'
import KakaoMap from '../../components/map/KakaoMap.vue'
import { useSavedCourseExplorer, sheetHeight } from '../../composables/useSavedCourseExplorer'
import { preloadKakao } from '../../composables/useKakaoShare.js'
import { hasNaviCoordinates, isNaviMobile, startNavi, type NaviSdk } from '../../services/kakaoNavi'
import type { Place } from '../../assets/types'
import { useCourseTabs } from '../../composables/useCourseTabs'
import PlaceDetailService, { type PlaceDetail } from '../../services/PlaceDetailService'
import AppIcon from '../../components/common/AppIcon.vue'
import CourseShareDialog from '../../components/course/CourseShareDialog.vue'
import CourseActionsMenu from '../../components/course/CourseActionsMenu.vue'
import TripConfirmation from '../../components/course/TripConfirmation.vue'
import AlternativePlaceModal from '../../components/course/AlternativePlaceModal.vue'
import { useSavedCourseActions } from '../../composables/useSavedCourseActions'
const route = useRoute(), router = useRouter(), auth = useAuthStore()
const sharing = ref<{ id: string; title: string } | null>(null)

const { opened, active, open, close, showList } = useCourseTabs()
const placeDetails = ref<Record<number, PlaceDetail | null>>({})
const pendingPlaces = new Set<number>()
const transportLabel = computed(() => ({ RENTAL_CAR: '차량', TAXI: '택시', PUBLIC_TRANSIT: '대중교통', WALK_BIKE: '도보·자전거' }[course.value?.transport ?? ''] ?? ''))

const cards = ref<CourseCard[]>([])
const loading = ref(true)
const ok = ref(true)
const page = ref(1)
const totalPages = ref(1)
const totalElements = ref(0)
const { course, selectedId, expanded, loading: detailLoading, failed, select, reset } =
  useSavedCourseExplorer(id => CourseService.getCourseDetail(id))
const activeDay = ref(1)
const editingDays = ref<number[]>([])
const swaps = useSavedCourseActions(course)
const { modalItem, alternatives, loading: altLoading, busy: swapping, notice: altNotice, message: swapMessage } = swaps
const renameDialog = ref<HTMLDialogElement>()
const renameInput = ref<HTMLInputElement>()
const renameTarget = ref(''), draftTitle = ref(''), renameError = ref(''), renameBusy = ref(false)
const selectedStop = ref('')
const currentDay = computed(() => course.value?.days.find(day => day.dayNo === activeDay.value))
const destination = computed(() => currentDay.value?.items.find(item => String(item.id) === selectedStop.value))
const mapPlaces = computed<Place[]>(() => (currentDay.value?.items ?? []).map(item => ({
  id: String(item.id), name: item.placeName, latitude: item.latitude ?? undefined,
  longitude: item.longitude ?? undefined, region: item.regionName ?? '', category: item.categoryName,
  address: '', description: item.reason ?? '', image: item.imageUrl ?? '',
  score: item.congestionRate ?? 0, level: item.congestionLevel ?? 'QUIET',
  pinLabel: String(item.position), pinDescription: `${item.position}번째 방문지`,
  congestionUnknown: item.congestionLevel == null,
  time: '', stay: '', cost: '', tags: [],
})))
const mapFailed = ref(false)
const mapAttempt = ref(0)
const notice = ref('')
const deleting = ref(false)
const mobile = ref(false)
const naviReady = ref(false)
const naviLoading = ref(false)
const workspace = ref<HTMLElement>()
const courseBrowser = ref<HTMLElement>()
const browserHeight = ref(180)
let browserObserver: ResizeObserver | undefined
const panelHeight = ref(55)
const panelSize = computed(() => panelHeight.value >= 90 ? 'calc(100% - var(--browser-height))' : `${panelHeight.value}%`)
const draggingPanel = ref(false)
let listSequence = 0
let alive = true
let drag: { id: number; y: number; height: number; moved: boolean } | null = null
let suppressClick = false

watch(course, (value, previous) => {
  if (!value) return
  if (value.id !== previous?.id) {
    activeDay.value = value.days[0]?.dayNo ?? 1
    selectedStop.value = String(value.days[0]?.items[0]?.id ?? '')
  }
  const tab = opened.value.find(tab => tab.id === value.id)
  if (tab) tab.title = value.title || '코스 상세'
})
watch(activeDay, () => { selectedStop.value = String(currentDay.value?.items[0]?.id ?? '') })
watch(active, id => {
  notice.value = ''
  editingDays.value = []
  swaps.reset()
  reset()
  if (id) { panelHeight.value = 55; void select(id) }
}, { immediate: true })
watch(() => route.params.courseId, value => {
  const id = typeof value === 'string' ? value : ''
  if (id) open({ id, title: cards.value.find(c => c.id === id)?.title ?? '코스 상세' })
  else showList()
}, { immediate: true })
watch(currentDay, day => {
  for (const item of day?.items ?? []) {
    if (item.placeId in placeDetails.value || pendingPlaces.has(item.placeId)) continue
    pendingPlaces.add(item.placeId)
    void PlaceDetailService.getDetail(item.placeId).then(result => {
      if (alive) placeDetails.value[item.placeId] = result
    }).finally(() => pendingPlaces.delete(item.placeId))
  }
})

async function load(target: number) {
  if (!auth.isLoggedIn) { loading.value = false; return }
  const request = ++listSequence
  loading.value = true
  try {
    const result = await CourseService.getSavedCourses(target - 1, 5)
    if (!alive || request !== listSequence) return
    cards.value = result.cards
    ok.value = result.ok
    totalPages.value = result.totalPages
    totalElements.value = result.totalElements
    page.value = target
  } catch {
    if (alive && request === listSequence) { ok.value = false; cards.value = [] }
  } finally {
    if (alive && request === listSequence) loading.value = false
  }
}
async function choose(id: string) {
  const card = cards.value.find(item => item.id === id)
  open({ id, title: card?.title || opened.value.find(item => item.id === id)?.title || '코스 상세' })
  await router.push(`/courses/${id}`)
}
function showLibrary() { showList(); void router.push('/courses') }
function closeTab(id: string) { close(id); void router.push(active.value ? `/courses/${active.value}` : '/courses') }
function toggleEditing(dayNo: number) {
  activeDay.value = dayNo
  editingDays.value = editingDays.value.includes(dayNo) ? editingDays.value.filter(n => n !== dayNo) : [...editingDays.value, dayNo]
}
async function startRename(card: CourseCard) {
  renameTarget.value = card.id; draftTitle.value = card.title; renameError.value = ''
  renameDialog.value?.showModal()
  await nextTick(); renameInput.value?.focus(); renameInput.value?.select()
}
async function saveRename() {
  const title = draftTitle.value.trim(), id = renameTarget.value
  if (renameBusy.value) return
  if (!title || title.length > 100) { renameError.value = '코스 이름을 1~100자로 입력해 주세요.'; return }
  renameBusy.value = true; renameError.value = ''
  try {
    const saved = await CourseService.renameCourse(id, title)
    if (!alive) return
    cards.value = cards.value.map(card => card.id === id ? { ...card, title: saved } : card)
    const tab = opened.value.find(tab => tab.id === id)
    if (tab) tab.title = saved
    if (course.value?.id === id) course.value = { ...course.value, title: saved }
    renameDialog.value?.close(); notice.value = '코스 이름을 바꿨어요.'
  } catch { if (alive) renameError.value = '이름을 바꾸지 못했어요. 다시 시도해 주세요.' }
  finally { renameBusy.value = false }
}
function togglePanel() {
  if (suppressClick) { suppressClick = false; return }
  expanded.value = !expanded.value
  panelHeight.value = expanded.value ? 55 : 18
}
function dragStart(event: PointerEvent) {
  if (event.button !== 0) return
  suppressClick = false
  draggingPanel.value = true
  drag = { id: event.pointerId, y: event.clientY, height: panelHeight.value, moved: false }
  ;(event.currentTarget as HTMLElement).setPointerCapture(event.pointerId)
}
function dragMove(event: PointerEvent) {
  if (!drag || event.pointerId !== drag.id) return
  const delta = event.clientY - drag.y
  if (Math.abs(delta) > 4) drag.moved = true
  if (!drag.moved) return
  panelHeight.value = sheetHeight(drag.height, delta, workspace.value?.clientHeight ?? 0)
  expanded.value = panelHeight.value > 23
}
function dragEnd(event: PointerEvent) {
  if (!drag || event.pointerId !== drag.id) return
  suppressClick = drag.moved
  if (drag.moved) {
    const stops = [18, 55, 90]
    const delta = panelHeight.value - drag.height
    const candidates = Math.abs(delta) > 3
      ? stops.filter(height => delta > 0 ? height > drag!.height : height < drag!.height)
      : stops
    panelHeight.value = (candidates.length ? candidates : stops).reduce((best, height) =>
      Math.abs(height - panelHeight.value) < Math.abs(best - panelHeight.value) ? height : best)
    expanded.value = panelHeight.value > 23
  }
  draggingPanel.value = false
  drag = null
}
function panelKey(event: KeyboardEvent) {
  if (!['ArrowUp', 'ArrowDown', 'Home', 'End'].includes(event.key)) return
  event.preventDefault()
  panelHeight.value = event.key === 'Home' ? 18 : event.key === 'End' ? 90
    : sheetHeight(panelHeight.value, event.key === 'ArrowUp' ? -15 : 15, 100)
  expanded.value = panelHeight.value > 23
}
async function prepareNavi() {
  naviLoading.value = true
  const ready = await preloadKakao()
  if (!alive) return
  naviReady.value = ready && !!(window as Window & { Kakao?: NaviSdk }).Kakao?.Navi
  naviLoading.value = false
}
function navigate() {
  if (!destination.value) return
  const sdk = (window as Window & { Kakao?: NaviSdk }).Kakao
  if (!startNavi(destination.value, mobile.value, sdk)) notice.value = '카카오내비를 열지 못했어요. 잠시 후 다시 시도해 주세요.'
}
async function removeCourse(id: string) {
  if (deleting.value || !window.confirm('이 코스를 삭제할까요? 삭제한 코스는 복구할 수 없어요.')) return
  deleting.value = true
  try {
    const target = await CourseService.getCourseDetail(id)
    if (!target?.manageable) { notice.value = '삭제 권한을 확인하지 못했어요. 다시 시도해 주세요.'; return }
    await CourseService.deleteCourse(id)
    closeTab(id)
    await load(cards.value.length === 1 && page.value > 1 ? page.value - 1 : page.value)
    notice.value = '코스를 삭제했어요.'
  } catch { notice.value = '삭제하지 못했어요. 다시 시도해 주세요.' }
  finally { deleting.value = false }
}
onMounted(() => {
  browserObserver = new ResizeObserver(entries => {
    browserHeight.value = entries[0]?.target.getBoundingClientRect().height ?? 180
  })
  if (courseBrowser.value) browserObserver.observe(courseBrowser.value)
  mobile.value = isNaviMobile(navigator.userAgent, navigator.maxTouchPoints)
  if (mobile.value) void prepareNavi()
  void load(1)
})
onBeforeUnmount(() => { alive = false; listSequence++; browserObserver?.disconnect(); swaps.reset(); reset() })
</script>

<template>
  <section ref="workspace" class="saved-workspace" :style="{ '--sheet-height': panelHeight + '%', '--sheet-size': panelSize, '--browser-height': browserHeight + 'px', '--map-bottom': selectedId ? panelSize : '0px' }" aria-label="저장한 코스">
    <aside class="course-sidebar">
      <div ref="courseBrowser" class="course-browser">
        <nav class="browser-tabs" aria-label="저장 코스 탭">
          <button type="button" class="browser-tab fixed-tab" :class="{ active: !active }" :aria-current="!active ? 'page' : undefined" @click="showLibrary">저장 코스</button>
          <div v-for="tab in opened" :key="tab.id" class="browser-tab" :class="{ active: active === tab.id }">
            <button type="button" :aria-current="active === tab.id ? 'page' : undefined" @click="choose(tab.id)">{{ tab.title }}</button>
            <button type="button" class="close-tab" :aria-label="`${tab.title} 탭 닫기`" @click="closeTab(tab.id)">×</button>
          </div>
        </nav>
        <div class="tab-toolbar">
          <RouterLink v-if="!active" class="new-course" :to="{ path: '/ai-course', query: { entry: 'new' } }">새 코스 +</RouterLink>
          <template v-else>
            <TripConfirmation v-if="course?.manageable && course.status === 'SAVED'" :course-id="active" compact />
            <RouterLink class="toolbar-map icon-button" :to="`/map?course=${active}`" aria-label="지도에서 보기" title="지도에서 보기"><AppIcon name="map" /></RouterLink>
            <button type="button" :disabled="!course?.manageable || deleting" aria-label="코스 공유" title="코스 공유" @click="sharing = { id: active, title: course?.title || '여행 코스' }"><AppIcon name="share" /></button>
          </template>
        </div>
      </div>
      <div v-show="!active" class="course-library">
        <p v-if="!auth.isLoggedIn"><RouterLink to="/login?redirect=/courses">로그인 후 저장한 코스를 확인해 주세요.</RouterLink></p>
        <p v-else-if="loading" role="status">저장한 코스를 불러오는 중이에요.</p>
        <div v-else-if="!ok" role="alert">코스를 불러오지 못했어요. <button type="button" @click="load(page)">다시 시도</button></div>
        <p v-else-if="!cards.length">아직 저장한 코스가 없어요. 새 코스를 만들어 보세요.</p>
        <template v-else>
          <div class="library-list" aria-label="저장 코스 목록">
            <article v-for="card in cards" :key="card.id" class="library-card">
            <button type="button" class="course-tab"
              :disabled="deleting" @click="choose(card.id)">
              <span class="course-tab-title">{{ card.title }}</span>
              <strong>{{ card.stops || card.conditionLabel }}</strong><small>{{ card.conditionLabel }}</small>
            </button>
            <CourseActionsMenu :title="card.title" :disabled="deleting || renameBusy" @rename="startRename(card)" @share="sharing = { id: card.id, title: card.title }" @delete="removeCourse(card.id)" />
            </article>
          </div>
          <small class="library-count">총 {{ totalElements }}개</small>
          <nav class="saved-pagination" aria-label="저장 코스 페이지">
            <template v-if="totalPages > 1">
              <button type="button" :disabled="page <= 1 || loading || deleting" aria-label="이전 페이지" @click="load(page - 1)">‹</button>
              <span>{{ page }} / {{ totalPages }}</span>
              <button type="button" :disabled="page >= totalPages || loading || deleting" aria-label="다음 페이지" @click="load(page + 1)">›</button>
            </template>
          </nav>
        </template>
        <p v-if="notice" class="saved-notice" role="status">{{ notice }}</p>
      </div>
      <section v-if="selectedId" id="saved-itinerary" class="itinerary-sheet" :class="{ collapsed: !expanded, dragging: draggingPanel }" aria-label="선택한 코스 일정">
        <button class="sheet-handle" type="button" :aria-expanded="expanded" aria-label="일정 패널 펼치기 또는 접기. 위아래 방향키로 높이 조절"
          @pointerdown="dragStart" @pointermove="dragMove" @pointerup="dragEnd" @pointercancel="dragEnd" @lostpointercapture="dragEnd"
          @click="togglePanel" @keydown="panelKey"><span /></button>
        <div v-if="detailLoading" class="sheet-message" role="status">일정을 불러오는 중이에요.</div>
        <div v-else-if="failed" class="sheet-message" role="alert">일정을 불러오지 못했어요. <button type="button" @click="select(selectedId)">다시 시도</button></div>
        <template v-else-if="course">
          <div class="course-summary">
            <div><CongestionBadge v-if="course.level" :level="course.level" /><span v-else>혼잡 정보 없음</span><small>{{ course.conditionLabel }}</small></div>
          </div>
          <div v-show="expanded" class="sheet-body">
            <div class="course-actions">
              <strong>{{ course.title }}</strong>
            </div>
            <p v-if="!course.days.length" class="sheet-message">등록된 일정이 없어요.</p>
            <section v-for="day in course.days" :key="day.dayNo" class="itinerary-day">
              <div class="day-heading">
                <button class="day-toggle" type="button" :aria-expanded="activeDay === day.dayNo" :aria-controls="`day-${day.dayNo}`" @click="activeDay = activeDay === day.dayNo ? 0 : day.dayNo"><span class="day-number">DAY {{ day.dayNo }}</span><strong>{{ day.visitDate }}</strong></button>
                <button v-if="course.swappable" class="day-edit icon-button" type="button" :disabled="swapping" :aria-pressed="editingDays.includes(day.dayNo)" :aria-label="`DAY ${day.dayNo} ${editingDays.includes(day.dayNo) ? '수정 완료' : '일정 수정'}`" :title="editingDays.includes(day.dayNo) ? '수정 완료' : '일정 수정'" @click="toggleEditing(day.dayNo)"><AppIcon :name="editingDays.includes(day.dayNo) ? 'check' : 'edit'" :size="18" /></button>
                <button class="day-chevron" type="button" :aria-label="`DAY ${day.dayNo} ${activeDay === day.dayNo ? '접기' : '펼치기'}`" @click="activeDay = activeDay === day.dayNo ? 0 : day.dayNo">{{ activeDay === day.dayNo ? '∧' : '∨' }}</button>
              </div>
              <ol v-if="activeDay === day.dayNo" :id="`day-${day.dayNo}`" class="stop-list">
                <li v-for="item in day.items" :key="item.id">
                  <span class="stop-number">{{ item.position }}</span>
                  <article class="place-stop" :class="{ selected: selectedStop === String(item.id) }">
                  <button type="button" class="stop-card" :class="{ selected: selectedStop === String(item.id) }" :aria-pressed="selectedStop === String(item.id)" @click="selectedStop = String(item.id)">
                    <span class="stop-photo"><AppIcon name="album" :size="24" /><img v-if="item.imageUrl" :src="item.imageUrl" alt="" loading="lazy" @error="($event.target as HTMLImageElement).hidden = true"></span>
                    <span class="stop-copy"><span class="stop-time">{{ item.startTime?.slice(0, 5) || `${item.position}번째 방문` }}</span><strong>{{ item.placeName }}</strong><span v-if="item.reason" class="stop-description">{{ item.reason }}</span>
                      <span class="stop-facts"><span v-if="item.congestionLevel" class="crowd-pill" :class="item.congestionLevel.toLowerCase()">{{ item.congestionLabel || ({ QUIET: '한산', NORMAL: '보통', CROWDED: '혼잡' }[item.congestionLevel]) }}</span><small v-else>예보 없음</small></span>
                      <span v-if="placeDetails[item.placeId]?.goodPrice" class="price-pill">착한가격업소 · {{ placeDetails[item.placeId]?.useFeeText || '가격 확인 필요' }}</span>
                    </span>
                  </button>
                  <div class="stop-footer">
                    <span v-if="item.inboundTravelMinutes != null || item.inboundDistanceM != null">{{ transportLabel }} <template v-if="item.inboundTravelMinutes != null">약 {{ item.inboundTravelMinutes }}분 </template><template v-if="item.inboundDistanceM != null">({{ (item.inboundDistanceM / 1000).toFixed(1) }}km) </template>이동 · 이전 장소에서</span>
                    <span v-else>이동 정보 없음</span>
                    <RouterLink class="place-detail" :to="`/places/${item.placeId}`">상세 보기</RouterLink>
                  </div>
                  <button v-if="editingDays.includes(day.dayNo) && course.swappable" class="stop-alternative" type="button" :disabled="swapping" :aria-label="`${item.placeName} 대안 보기`" @click="swaps.openSwap(day, item)"><AppIcon name="route" :size="16" />대안 보기</button>
                  </article>
                </li>
              </ol>
            </section>
          </div>
          <div v-if="mobile && expanded" class="navi-action">
            <button v-if="!naviReady" type="button" :disabled="naviLoading" @click="prepareNavi">{{ naviLoading ? '카카오내비 연결 준비 중…' : '카카오내비 연결 다시 시도' }}</button>
            <button v-else type="button" :disabled="!destination || !hasNaviCoordinates(destination)" @click="navigate">{{ destination ? `${destination.placeName} 길안내` : '길안내할 장소를 선택해 주세요' }}</button>
            <small>{{ destination && !hasNaviCoordinates(destination) ? '좌표가 없는 장소는 길안내를 사용할 수 없어요.' : '카카오내비 앱으로 차량 길안내 · 미설치 시 설치 화면으로 이동' }}</small>
          </div>
        </template>
        <p v-if="notice" class="saved-notice" role="status">{{ notice }}</p>
        <p v-if="swapMessage" class="saved-notice" role="status">{{ swapMessage }}</p>
      </section>
    </aside>
    <div class="saved-map" aria-label="선택한 일정 지도">
      <KakaoMap v-if="!mapFailed" :key="mapAttempt" :places="mapPlaces" :selected-id="selectedStop" show-route @select="selectedStop = $event.id" @error="mapFailed = true" />
      <div v-else class="map-error" role="status"><strong>지도를 불러오지 못했어요.</strong><p>일정은 계속 확인할 수 있어요.</p><button type="button" @click="mapFailed = false; mapAttempt++">지도 다시 시도</button></div>
    </div>
  </section>
  <CourseShareDialog v-if="sharing" :key="sharing.id" :course-id="sharing.id" :title="sharing.title" @close="sharing = null" />
  <AlternativePlaceModal v-if="modalItem" :item="modalItem" :alternatives="alternatives" :loading="altLoading" :notice="altNotice" :busy="swapping"
    :forecast-date="swaps.forecastDate.value" :has-more="swaps.hasMore.value" :load-failed="swaps.loadFailed.value"
    @close="swaps.reset()" @select="swaps.applySwap" @more="swaps.loadMore()" @retry="swaps.loadMore(true)" />
  <dialog ref="renameDialog" class="course-rename-dialog" aria-labelledby="rename-title" @cancel="event => { if (renameBusy) event.preventDefault() }">
    <form @submit.prevent="saveRename">
      <h2 id="rename-title">코스 이름 변경</h2>
      <label for="course-name">코스 이름</label><input id="course-name" ref="renameInput" v-model="draftTitle" maxlength="100" :disabled="renameBusy" />
      <p v-if="renameError" role="alert">{{ renameError }}</p>
      <div><button type="button" :disabled="renameBusy" @click="renameDialog?.close()">취소</button><button type="submit" :disabled="renameBusy">{{ renameBusy ? '저장 중…' : '저장' }}</button></div>
    </form>
  </dialog>
</template>

<style scoped src="./savedCourses.css"></style>
<style scoped>
</style>
