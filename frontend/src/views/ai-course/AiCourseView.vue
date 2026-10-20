<script setup lang="ts">
import { computed, onMounted, onUnmounted, reactive, ref, watch } from 'vue'
import '@fontsource/noto-sans-kr/400.css'
import '@fontsource/noto-sans-kr/500.css'
import '@fontsource/noto-sans-kr/700.css'
import { congestionLabel } from '../../utils/congestion'
import DailyWeatherBadges from '../../components/course/DailyWeatherBadges.vue'
import AppIcon from '../../components/common/AppIcon.vue'
import { rainyIndoorNotice } from '../../services/course/dailyWeather'
import { expectedTransitEdges, transitTopologyMatches, useTransitRoute } from '../../services/course/transitRoute'
import { useAccommodationSelection, syncConfirmedCourseCondition } from '../../services/course/accommodationSelection'
import TransitDayRoute from '../../components/course/TransitDayRoute.vue'
import TransitLegCard from '../../components/course/TransitLegCard.vue'
import { todayKst, addCalendarDays, formatCalendarDate } from '../../utils/format.js'
import { useRoute, useRouter } from 'vue-router'
import { useAuthStore } from '../../app/stores/auth'
import { useUiStore } from '../../stores/ui.js'
import CourseConditionForm from '../../components/course/CourseConditionForm.vue'
import CourseItemCard from '../../components/course/CourseItemCard.vue'
import AlternativePlaceModal from '../../components/course/AlternativePlaceModal.vue'
import AccommodationRecommendations from '../../components/course/AccommodationRecommendations.vue'
import { courseGenerationErrorMessage, courseMockService, toCourseRequestPayload } from '../../services/courseMockService'
import { ASYNC_COURSES_ENABLED } from '../../api/notifications.js'
import { getGenerationResult, submitGenerationJob } from '../../api/courseGeneration.js'
import GenerationJobs from '../../components/course/GenerationJobs.vue'
import GenerationArtwork from '../../components/course/GenerationArtwork.vue'
import { storePendingCourseClaim, takePendingCourseClaim, clearPendingCourseClaim } from '../../services/pendingCourseClaim'
import { routeSummary, accessNotices } from '../../services/course/courseSummary'
import { ApiError } from '../../api/errors.js'
import { readRestore, rememberResult, rememberEditing, useResultRestore, validProof, singleFlight, useClaimRenewal, clearCourseProof, fetchRestoredCourse } from '../../services/course/resultRestore'
import type { AccommodationInput, AccommodationRecommendation, AlternativePlace, CarDayRoute, CarRouteLeg, CourseCondition, CourseItem, CourseResult } from '../../assets/types/course'

function defaultCondition(): CourseCondition {
  const today = todayKst()
  return {
  start_date: today,
  end_date: addCalendarDays(today, 2),
  people: 2,
  transport: 'RENTAL_CAR',
  course_regions: [],
  course_styles: [],
  course_place_preferences: [],
  }
}
const condition = reactive<CourseCondition>(defaultCondition())
const formRevision = ref(0)
function resetCondition() {
  // Remove optional/legacy fields too; assigning defaults alone leaves accommodation behind.
  for (const key of Object.keys(condition)) delete (condition as unknown as Record<string, unknown>)[key]
  Object.assign(condition, defaultCondition())
  formRevision.value++ // the step form clones initial props; remount only for a fresh entry
}

const result = ref<CourseResult>()
const selectedDay = ref(1)
const activeDays = computed(() => {
  const days = result.value?.days ?? []
  const day = days.find(day => day.day_no === selectedDay.value) ?? days[0]
  return day ? [day] : []
})
watch(() => result.value?.id, () => { selectedDay.value = result.value?.days[0]?.day_no ?? 1 })
function navigateDay(event: KeyboardEvent, index: number) {
  const days = result.value?.days ?? []
  const next = event.key === 'Home' ? 0 : event.key === 'End' ? days.length - 1
    : event.key === 'ArrowRight' ? (index + 1) % days.length
      : event.key === 'ArrowLeft' ? (index + days.length - 1) % days.length : -1
  if (next < 0 || !days[next]) return
  event.preventDefault()
  selectedDay.value = days[next].day_no
  document.getElementById(`course-tab-${selectedDay.value}`)?.focus()
}
const loading = ref(false)
const error = ref('')
const jobResultError = ref('')
const editing = ref(true)
const selected = ref<CourseItem>()
const alternatives = ref<AlternativePlace[]>([])
const altLoading = ref(false)
const altNotice = ref('')
const swapping = ref(false)
const recommendedAccommodations = ref<AccommodationRecommendation[]>([])
const accommodationLoading = ref(false)
const accommodationError = ref('')
const accommodationPickerOpen = ref(false)
const routeLoading = ref(false)
const routeError = ref('')
const saveOpen = ref(false)
const title = ref('')
const saveError = ref('')
const saveLoading = ref(false)
const toast = ref('')
const auth = useAuthStore()
const ui = useUiStore()
watch(() => ui.aiCourseEntryVersion, () => enterInput())
const historyDialog = ref<HTMLDialogElement>()
const historyTrigger = ref<HTMLButtonElement>()
const historyOpen = ref(false)
function openHistory() {
  if (!auth.isLoggedIn || !editing.value) return
  historyOpen.value = true
  historyDialog.value?.showModal()
}
function closeHistory() {
  const wasOpen = historyOpen.value
  historyOpen.value = false
  historyDialog.value?.close()
  if (wasOpen) historyTrigger.value?.focus()
}
watch(() => [auth.isLoggedIn, (auth.user as { userId: number } | null)?.userId], closeHistory)
const router = useRouter()
const route = useRoute()
const restoration = useResultRestore()
const renewal = useClaimRenewal()
const { notice: renewalNotice, renewing } = renewal
const { restoring, restoreError, terminal } = restoration
const restoringState = ref(readRestore())
// Same public numeric identifier pattern used by existing course links; never a claim credential.
if (typeof route.query.course === 'string' && /^[1-9]\d{0,14}$/.test(route.query.course)) {
  const id = Number(route.query.course)
  if (restoringState.value?.courseId !== id) restoringState.value = { mode: 'result', courseId: id, condition: restoringState.value?.condition ?? { ...condition } }
}
// The form clones its initial props: hydrate before its first render, not onMounted.
if (restoringState.value && route.query.entry !== 'new') Object.assign(condition, restoringState.value.condition)
const fetchRoute = singleFlight(courseMockService.getCarRoute)
const transit = useTransitRoute()
const accommodationSelection = useAccommodationSelection()
const { data: transitData, loading: transitLoading, error: transitError } = transit
const { saving: accommodationSaving, error: accommodationSaveError } = accommodationSelection
const now = ref(Date.now())
let clock: ReturnType<typeof setInterval> | undefined
let viewEpoch = 0
let routeEpoch = 0
const canModify = computed(() => !!result.value && !renewing.value && !accommodationSaving.value && !swapping.value
  && (result.value.status === 'SAVED' ? auth.isAuthenticated : validProof(result.value, now.value)))
// Swap remains governed by its existing independent API contract, not the claim lifetime.
const canSwap = computed(() => !!result.value && !accommodationSaving.value && result.value.swappable !== false)
function rememberCurrent() {
  if (result.value) {
    rememberResult(result.value, condition)
    restoringState.value = readRestore()
  }
}
function editConditions() {
  transit.cancel()
  accommodationSelection.cancel()
  restoration.cancel(); renewal.cancel(); viewEpoch++; routeEpoch++
  if (result.value) clearCourseProof(result.value)
  restoringState.value = null; editing.value = true; loading.value = false; routeLoading.value = false
  jobResultError.value = ''
  selected.value = undefined; saveOpen.value = false
  accommodationPickerOpen.value = false
  rememberEditing(condition)
  if (route.query.course != null || route.query.job != null || route.query.entry != null) void router.replace({ path: route.path, query: { ...route.query, entry: undefined, course: undefined, job: undefined } })
}
watch(condition, () => { if (editing.value && !restoring.value) rememberEditing(condition) }, { deep: true })
function enterInput() {
  editConditions(); closeHistory(); clearPendingCourseClaim()
  resetCondition(); rememberEditing(condition)
  result.value = undefined; error.value = ''; saveError.value = ''
  recommendedAccommodations.value = []; alternatives.value = []
}
watch(() => route.query.entry, entry => { if (entry === 'new') enterInput() })
watch(() => route.query.course, id => {
  if (route.query.entry === 'new' || !id) return
  if (typeof id !== 'string' || !/^[1-9]\d{0,14}$/.test(id)) return
  transit.cancel(); restoration.cancel(); renewal.cancel(); viewEpoch++; routeEpoch++
  restoringState.value = { mode: 'result', courseId: Number(id), condition: { ...condition } }
  void restoreResult()
})
async function restoreResult() {
  if (!restoringState.value || restoringState.value.mode !== 'result') return
  accommodationSelection.cancel()
  const ticket = ++viewEpoch
  const loaded = await restoration.restore(restoringState.value, auth.isAuthenticated)
  if (!loaded || ticket !== viewEpoch) return
  result.value = loaded
  if (loaded.status === 'READY' && validProof(restoringState.value)) {
    loaded.claim_token = restoringState.value.claim_token
    loaded.claim_expires_at = restoringState.value.claim_expires_at
  }
  syncConfirmedCourseCondition(condition, loaded)
  editing.value = false; rememberCurrent()
  void loadCarRoute()
  if (loaded.status === 'READY' && validProof(loaded)) {
    const id = loaded.id
    const proof = await renewal.renew(id, loaded)
    if (proof && ticket === viewEpoch && result.value?.id === id && result.value.status === 'READY' && !editing.value) {
      clearCourseProof(result.value)
      Object.assign(result.value, proof)
      rememberCurrent()
    }
  }
}
onUnmounted(() => { transit.cancel(); accommodationSelection.cancel(); restoration.cancel(); renewal.cancel(); viewEpoch++; routeEpoch++; if (clock) clearInterval(clock) })
watch(now, () => {
  if (result.value?.claim_token && !validProof(result.value, now.value)) { clearCourseProof(result.value); rememberCurrent() }
})

/* Navigate through the existing saved-course URL without sharing route geometry. */
async function viewOnMap() {
  if (!result.value) return
  if (result.value.status !== 'SAVED') {
    toast.value = '코스를 저장한 뒤 지도에서 확인해 주세요.'
    return
  }
  await router.push({ path: '/map', query: { course: String(result.value.id) } })
}

async function loadCarRoute() {
  if (result.value?.transport === 'PUBLIC_TRANSIT') {
    const course = result.value
    const ticket = viewEpoch
    const response = await transit.load(course)
    if (!response || ticket !== viewEpoch || result.value?.id !== course.id || editing.value
      || transitTopologyMatches(course, response)) return
    try {
      const confirmed = await fetchRestoredCourse({ mode: 'result', courseId: course.id, condition }, auth.isAuthenticated)
      if (ticket !== viewEpoch || result.value?.id !== course.id || editing.value) return
      if (confirmed.status === 'READY' && validProof(course)) {
        confirmed.claim_token = course.claim_token
        confirmed.claim_expires_at = course.claim_expires_at
      }
      result.value = confirmed
      syncConfirmedCourseCondition(condition, confirmed)
      rememberCurrent()
    } catch {
      if (ticket === viewEpoch) error.value = '서버의 최신 숙소와 일정을 확인하지 못했어요. 다시 불러와 주세요.'
    }
    return
  }
  transit.cancel()
  if (!result.value || result.value.transport !== 'RENTAL_CAR') return
  const course = result.value
  const ticket = ++routeEpoch
  routeLoading.value = true
  routeError.value = ''
  try {
    const route = await fetchRoute(JSON.stringify([course.id, course.days, course.accommodation]), course)
    if (ticket === routeEpoch && result.value?.id === course.id && !editing.value) result.value = { ...result.value, car_route: route }
  } catch {
    if (ticket === routeEpoch) routeError.value = '이동 경로를 불러오지 못했어요.'
  } finally {
    if (ticket === routeEpoch) routeLoading.value = false
  }
}

function transitLeg(dayNo: number, fromId: string) {
  return transitData.value?.days.find(d => d.day_no === dayNo)?.legs.find(l => l.from.id === fromId)
}

const routeForDay = (dayNo: number): CarDayRoute | undefined =>
  result.value?.car_route?.days.find(day => day.day_no === dayNo)
const inboundRoute = (dayNo: number, itemId: number): CarRouteLeg | undefined =>
  routeForDay(dayNo)?.legs.find(leg => leg.to.type === 'COURSE_ITEM' && leg.to.id === String(itemId))
const formatDuration = (seconds?: number | null) => seconds == null ? '정보 없음' : `${Math.round(seconds / 60)}분`

const transportLabel = {
  RENTAL_CAR: '렌터카',
  PUBLIC_TRANSIT: '대중교통',
  TAXI: '택시',
  WALK_BIKE: '도보·자전거',
}

const tripDays = computed(() => result.value?.days.length ?? 0)
const tripNights = computed(() => Math.max(0, tripDays.value - 1))
const visitCount = computed(() => result.value?.days.reduce((count, day) => count + day.items.length, 0) ?? 0)
const congestionCoverage = computed(() => {
  const items = result.value?.days.flatMap(day => day.items) ?? []
  return { known: items.filter(item => item.congestion_rate != null).length, total: items.length }
})
const averageCongestionText = computed(() => {
  const coverage = congestionCoverage.value
  if (!coverage.known) return '정보 부족'
  const base = congestionLabel(result.value?.average_congestion_rate)
  return coverage.known === coverage.total ? base : `${base} (${coverage.known}/${coverage.total}개 예보)`
})
const regionSummary = computed(() => condition.course_regions.map(region => region.name).join(' · ') || '전체')
const styleSummary = computed(() => condition.course_styles.map(style => style.name).join(' · '))

async function generate(next: CourseCondition, regenerate = false) {
  if (loading.value) return
  transit.cancel()
  accommodationSelection.cancel()
  restoration.cancel(); renewal.cancel(); routeEpoch++
  const ticket = ++viewEpoch
  Object.assign(condition, JSON.parse(JSON.stringify(next)) as CourseCondition)
  loading.value = true
  error.value = ''
  const previous = regenerate ? result.value : undefined
  try {
    if (ASYNC_COURSES_ENABLED && auth.isAuthenticated) {
      const job = await submitGenerationJob(toCourseRequestPayload(condition, regenerate, previous))
      if (ticket === viewEpoch) await router.push({ name: 'course-generation-job', params: { jobId: job.jobId } })
      return
    }
    const generated = regenerate
      ? await courseMockService.regenerateCourse(condition, previous)
      : await courseMockService.generateCourse(condition)
    if (ticket !== viewEpoch) return
    result.value = generated
    syncConfirmedCourseCondition(condition, generated)
    recommendedAccommodations.value = []
    editing.value = false
    rememberCurrent()
    void loadCarRoute()
    if (!condition.accommodation) {
      accommodationLoading.value = true
      accommodationError.value = ''
      void courseMockService.getRecommendedAccommodations(result.value).then((items) => {
        if (ticket === viewEpoch) recommendedAccommodations.value = items
      }).catch(() => {
        if (ticket !== viewEpoch) return
        recommendedAccommodations.value = []
        accommodationError.value = '주변 숙소를 불러오지 못했어요.'
      }).finally(() => {
        if (ticket === viewEpoch) accommodationLoading.value = false
      })
    }
    requestAnimationFrame(() => window.scrollTo({ top: 0, behavior: 'smooth' }))
  } catch (generationFailure) {
    if (ticket === viewEpoch) error.value = courseGenerationErrorMessage(generationFailure)
  } finally {
    if (ticket === viewEpoch) loading.value = false
  }
}

async function selectRecommendedAccommodation(accommodation: AccommodationInput) {
  if (!result.value || !canModify.value || accommodationSaving.value) return
  const course = result.value
  const ticket = viewEpoch
  const outcome = await accommodationSelection.save(course, accommodation, condition, auth.isAuthenticated)
  if (!outcome || ticket !== viewEpoch || result.value?.id !== course.id || editing.value) return
  transit.cancel(); routeEpoch++
  result.value = outcome.course
  syncConfirmedCourseCondition(condition, outcome.course)
  rememberCurrent()
  void loadCarRoute()
  if (outcome.selectedStored) {
    recommendedAccommodations.value = []
    accommodationPickerOpen.value = false
  }
}

async function chooseAccommodation() {
  if (!result.value || !canModify.value || accommodationLoading.value || accommodationSaving.value) return
  const course = result.value
  const ticket = viewEpoch
  accommodationPickerOpen.value = true
  accommodationLoading.value = true
  accommodationError.value = ''
  try {
    const items = await courseMockService.getRecommendedAccommodations(course)
    if (ticket === viewEpoch && result.value?.id === course.id) recommendedAccommodations.value = items
  } catch {
    if (ticket === viewEpoch) accommodationError.value = '주변 숙소를 불러오지 못했어요. 기존 숙소는 유지됩니다.'
  } finally {
    if (ticket === viewEpoch) accommodationLoading.value = false
  }
}

async function openAlternatives(item: CourseItem) {
  if (!result.value || !canSwap.value) return
  selected.value = item
  alternatives.value = []
  altNotice.value = ''
  altLoading.value = true
  try {
    alternatives.value = await courseMockService.getAlternativePlaces(result.value, item.id, condition)
  } catch (failure) {
    // 3401 = 그 날짜 혼잡 예보 없음. 빈 목록으로 뭉개지 않고 이유를 보여준다(정직성)
    altNotice.value = failure instanceof ApiError && Number(failure.code) === 3401
      ? '이 날짜의 혼잡 예보가 아직 없어 대안을 고를 수 없어요.'
      : '대안을 불러오지 못했어요. 잠시 뒤 다시 시도해 주세요.'
  } finally {
    altLoading.value = false
  }
}

async function replace(alternative: AlternativePlace) {
  if (!result.value || !selected.value || swapping.value || !canSwap.value) return
  const previousAverage = result.value.average_congestion_rate
  const replacementName = alternative.place_name
  swapping.value = true
  altNotice.value = ''
  try {
    result.value = await courseMockService.replaceCourseItem(result.value, selected.value.id, alternative)
  } catch (failure) {
    // 서버 메시지(중복 장소·권한·예보 없음)를 모달 안에 보여준다 - 토스트는 모달 뒤에 가려진다. 기존 일정은 그대로
    altNotice.value = failure instanceof ApiError ? `바꾸지 못했어요. ${failure.message}` : '장소를 바꾸지 못했어요. 기존 일정은 그대로예요.'
    return
  } finally {
    swapping.value = false
  }
  selected.value = undefined
  rememberCurrent()
  // 렌터카 경로는 교체된 장소 기준으로 다시 받는다 - 숙소 변경과 같은 처리
  void loadCarRoute()
  toast.value = previousAverage != null && result.value.average_congestion_rate != null
    ? `${replacementName}으로 변경했어요. 평균 혼잡도는 ${congestionLabel(result.value.average_congestion_rate)}이에요.`
    : `${replacementName}으로 변경하고 동선을 다시 계산했어요.`
  setTimeout(() => { toast.value = '' }, 2600)
}

function openSave() {
  if (!canModify.value) return
  title.value = result.value?.title || ''
  saveError.value = ''
  saveOpen.value = true
}

async function save() {
  const clean = title.value.trim()
  if (!result.value || clean.length < 1 || clean.length > 100 || saveLoading.value) return
  saveLoading.value = true
  saveError.value = ''
  try {
    if (!auth.isAuthenticated) {
      storePendingCourseClaim(result.value, condition, clean)
      saveOpen.value = false
      await router.push({ path: '/login', query: { redirect: '/ai-course' } })
      return
    }
    result.value = await courseMockService.saveCourse(result.value, clean)
    renewal.cancel(); clearCourseProof(result.value)
    rememberCurrent()
    saveOpen.value = false
    toast.value = '코스를 저장했어요.'
  } catch (saveFailure) {
    saveError.value = saveFailure instanceof Error ? saveFailure.message : '코스를 저장하지 못했어요.'
  } finally {
    saveLoading.value = false
  }
}

async function restoreJobResult () {
  const id = route.query.job
  if (typeof id !== 'string' || !ASYNC_COURSES_ENABLED) return false
  if (!auth.isAuthenticated) {
    await router.replace({ name: 'login', query: { redirect: route.fullPath } })
    return true
  }
  const ticket = ++viewEpoch
  transit.cancel(); accommodationSelection.cancel(); restoration.cancel(); renewal.cancel(); loading.value = true; jobResultError.value = ''
  try {
    if (!/^[a-f\d]{8}(-[a-f\d]{4}){3}-[a-f\d]{12}$/i.test(id)) throw new Error('INVALID_JOB')
    const response = await getGenerationResult(id)
    if (ticket !== viewEpoch) return true
    // 알림은 며칠 뒤에도 열 수 있다. 생성 당시 JSON 대신 현재 저장/교체된 일정으로 복원한다.
    const current = await restoration.restore({ mode: 'result', courseId: response.course.id,
      condition: response.request, claim_token: response.course.claim_token,
      claim_expires_at: response.course.claim_expires_at }, true)
    if (ticket !== viewEpoch) return true
    if (!current) throw new Error('RESULT_UNAVAILABLE')
    if (current.status === 'READY') {
      current.claim_token = response.course.claim_token
      current.claim_expires_at = response.course.claim_expires_at
    }
    result.value = current
    Object.assign(condition, response.request)
    syncConfirmedCourseCondition(condition, current)
    editing.value = false; error.value = ''; rememberCurrent(); void loadCarRoute()
  } catch {
    if (ticket === viewEpoch) { jobResultError.value = '생성 결과를 불러오지 못했어요. 다시 조회하거나 작업 상태를 확인해 주세요.' }
  } finally { if (ticket === viewEpoch) loading.value = false }
  return true
}
watch(() => route.query.job, (id, previous) => {
  transit.cancel(); accommodationSelection.cancel(); viewEpoch++; routeEpoch++; jobResultError.value = ''
  if (id) void restoreJobResult()
  else if (previous) { loading.value = false; editing.value = true; result.value = undefined }
})
watch(() => (auth.user as { userId: number } | null)?.userId, (_userId, previousUserId) => {
  transit.cancel(); accommodationSelection.cancel(); viewEpoch++; routeEpoch++; restoration.cancel(); renewal.cancel()
  if (result.value) clearCourseProof(result.value)
  result.value = undefined; restoringState.value = null; editing.value = true; loading.value = false
  selected.value = undefined; saveOpen.value = false; saveLoading.value = false
  alternatives.value = []; recommendedAccommodations.value = []
  if (previousUserId != null) clearPendingCourseClaim()
  resetCondition()
  rememberEditing(condition)
  if (route.query.job) { result.value = undefined; editing.value = true; loading.value = false; void restoreJobResult() }
})
onMounted(async () => {
  clock = setInterval(() => { now.value = Date.now() }, 1000)
  if (route.query.entry === 'new') { enterInput(); return }
  if (await restoreJobResult()) return
  const pending = auth.isAuthenticated ? takePendingCourseClaim() : null
  if (!pending) { await restoreResult(); return }

  saveLoading.value = true
  const ticket = ++viewEpoch
  try {
    const saved = await courseMockService.saveCourse(pending.course, pending.title)
    if (ticket !== viewEpoch) return
    result.value = saved
    renewal.cancel(); clearCourseProof(result.value)
    Object.assign(condition, pending.condition)
    syncConfirmedCourseCondition(condition, result.value)
    editing.value = false
    rememberCurrent()
    void loadCarRoute()
    toast.value = '코스를 저장했어요.'
  } catch {
    if (ticket !== viewEpoch) return
    error.value = '로그인 전 생성한 코스를 저장하지 못했어요. 다시 생성해 주세요.'
  } finally {
    if (ticket === viewEpoch) saveLoading.value = false
  }
})

const formatDate = (value: string) => formatCalendarDate(value)
const formatShortDate = (value: string) => formatCalendarDate(value, { month: 'numeric', day: 'numeric' })

const formatDistance = (metres?: number | null) => metres == null ? '정보 없음' : `${(metres / 1000).toFixed(1)}km`
</script>

<template>
  <div class="ai-course-page" :class="{ 'builder-page': editing && !loading }">
    <header v-if="editing || loading || !result" class="course-page-header">
      <div>
        <h1>{{ loading ? '제주 여행을 구성하고 있어요' : editing ? '언제, 누구와 같이 한갓진 코스를 생성하고 싶으신가요?' : '추천 코스' }}</h1>
        <p v-if="!editing">{{ loading ? '선택한 조건을 바탕으로 잠시만 기다려 주세요.' : '선택한 조건과 예상 혼잡도를 반영한 제주 여행 일정이에요.' }}</p>
      </div>
      <button v-if="editing && auth.isLoggedIn && ASYNC_COURSES_ENABLED" ref="historyTrigger" class="history-trigger" aria-haspopup="dialog" @click="openHistory">최근 생성 요청</button>
    </header>
    <dialog ref="historyDialog" class="history-dialog" aria-label="최근 생성 코스" @cancel.prevent="closeHistory" @close="closeHistory" @click="($event.target === historyDialog) && closeHistory()">
      <button class="history-close" aria-label="최근 생성 코스 닫기" @click="closeHistory">×</button>
      <GenerationJobs v-if="auth.isLoggedIn && historyOpen" @selected="closeHistory" />
    </dialog>

    <section v-if="jobResultError" class="course-shell generation-state" role="alert">
      <h2>{{ jobResultError }}</h2>
      <button class="btn" @click="restoreJobResult">결과 다시 불러오기</button>
      <RouterLink class="btn" :to="{ name: 'course-generation-job', params: { jobId: String(route.query.job) } }">작업 상태 확인</RouterLink>
    </section>
    <section v-else-if="restoring || restoreError" class="course-shell generation-state" aria-live="polite">
      <h2>{{ restoring ? '저장된 코스를 다시 불러오고 있어요.' : restoreError }}</h2>
      <button v-if="!restoring && !terminal" class="btn" @click="restoreResult">다시 불러오기</button>
      <button class="btn" @click="editConditions">새 코스 만들기</button>
    </section>
    <section v-else-if="loading" class="course-shell generation-state" aria-live="polite">
      <GenerationArtwork status="RUNNING" />
      <span class="result-label">AI 코스 생성 중</span>
      <h2>여행 조건을 분석하고 있어요.</h2>
      <p>혼잡도와 이동 동선을 고려해 코스를 만들고 있어요.</p>
    </section>

    <section v-else-if="editing" class="course-shell builder-shell">
      <div v-if="error" class="generation-failure" role="alert">
        <p class="course-error">{{ error }} <button class="text-link" @click="generate(condition)">다시 시도</button></p>
      </div>
      <CourseConditionForm :key="`${(auth.user as { userId: number } | null)?.userId ?? 'guest'}:${formRevision}`" :initial="condition" :loading="loading" @submit="generate" @draft="draft => Object.assign(condition, draft, { accommodation: draft.accommodation })" />
    </section>

    <section v-else-if="result" class="course-shell result-shell">
      <header class="course-result-head">
        <div class="result-heading-copy">
          <span class="result-label">추천 코스</span>
          <h2>{{ tripNights }}박 {{ tripDays }}일 제주 여행</h2>
          <p>{{ formatShortDate(result.start_date) }} ~ {{ formatShortDate(result.end_date) }} · {{ result.people }}명 · {{ transportLabel[result.transport] }}</p>
          <div class="result-condition-tags">
            <span v-if="regionSummary">{{ regionSummary }}</span>
            <span v-if="styleSummary">{{ styleSummary }}</span>
          </div>
        </div>
        <div class="result-actions-block">
          <div class="result-actions-row">
            <button class="btn" @click="editConditions"><AppIcon name="tune" :size="15" />조건 수정</button>
            <button class="btn result-regenerate" :disabled="loading" @click="generate(condition, true)"><AppIcon name="route" :size="15" />다른 코스 만들기</button>
            <button class="btn result-map" @click="viewOnMap"><AppIcon name="map" :size="15" />지도에서 보기</button>
            <button class="btn result-save" :disabled="result.status === 'SAVED' || !canModify" @click="openSave"><AppIcon name="bookmark" :size="15" />{{ result.status === 'SAVED' ? '저장 완료' : '코스 저장' }}</button>
          </div>
          <p v-if="result.status === 'READY'" class="temporary-course-notice">미저장 코스는 생성 2시간 후 만료돼요.</p>
        </div>
      </header>

      <p v-if="renewing" class="route-status">코스 저장 증명을 갱신하고 있어요.</p>
      <p v-else-if="renewalNotice" class="route-status">{{ renewalNotice }}</p>
      <p v-else-if="!canModify && result.status !== 'SAVED'" class="route-status">유효한 코스 저장 증명이 없어 저장·숙소 변경을 사용할 수 없어요. 일정 조회는 유지됩니다.</p>
      <div class="course-result-grid">
        <main class="itinerary-card">
          <div class="day-tabs" role="tablist" aria-label="여행 날짜">
            <button v-for="(day, index) in result.days" :id="`course-tab-${day.day_no}`" :key="day.day_no" role="tab" :aria-selected="activeDays[0]?.day_no === day.day_no" :aria-controls="`course-day-${day.day_no}`" :tabindex="activeDays[0]?.day_no === day.day_no ? 0 : -1" @click="selectedDay = day.day_no" @keydown="navigateDay($event, index)">DAY {{ day.day_no }} <small>{{ formatDate(day.visit_date) }}</small></button>
          </div>
          <section v-for="day in activeDays" :id="`course-day-${day.day_no}`" :key="day.day_no" class="course-day" role="tabpanel" :aria-labelledby="`course-tab-${day.day_no}`" tabindex="0">
            <header>
              <div class="day-title"><b>● {{ day.day_no }}일차 일정</b><span> · {{ formatDate(day.visit_date) }}</span></div>
              <DailyWeatherBadges :items="day.items" />
              <!-- 비 예보일 + 실내 과반일 때만 - 실내는 이름 휴리스틱이라 '위주'까지만 -->
              <p v-if="rainyIndoorNotice(day.items)" class="rain-notice">{{ rainyIndoorNotice(day.items) }}</p>
            </header>
            <TransitDayRoute v-if="result.transport === 'PUBLIC_TRANSIT'" :day="transitData?.days.find(d => d.day_no === day.day_no)" :expected-edges="expectedTransitEdges(result, day.day_no)" :loading="transitLoading" :error="transitError" />
            <p v-if="result.transport === 'RENTAL_CAR'" class="route-summary">
              총 이동 {{ routeSummary([routeForDay(day.day_no) ?? {}], routeLoading) }}
            </p>
            <div class="day-timeline">
              <p v-for="notice in accessNotices(routeForDay(day.day_no) ? [routeForDay(day.day_no)!] : [])" :key="notice" class="route-status">{{ notice }}</p>
              <TransitLegCard v-if="result.transport === 'PUBLIC_TRANSIT' && result.accommodation && day.items.length" :leg="transitLeg(day.day_no, 'ACCOMMODATION')" :from="result.accommodation.place_name" :to="day.items[0]!.place_name" :loading="transitLoading" />
              <div v-if="result.accommodation && result.transport !== 'PUBLIC_TRANSIT'" class="travel-line"><span>↓</span> 숙소 출발 · {{ result.accommodation.place_name }}<template v-if="day.accommodation_departure_travel_minutes"> · {{ transportLabel[result.transport] }} {{ day.accommodation_departure_travel_minutes }}분 · {{ formatDistance(day.accommodation_departure_distance_m) }}</template></div>
              <template v-for="(item,itemIndex) in day.items" :key="item.id">
                <div v-if="inboundRoute(day.day_no, item.id)" class="travel-line">
                  <span>↓</span> 이동 약 {{ formatDuration(inboundRoute(day.day_no, item.id)?.duration_seconds) }} ·
                  {{ formatDistance(inboundRoute(day.day_no, item.id)?.distance_meters) }}
                </div>
                <CourseItemCard :item="item" :transport="result.transport" :readonly="!canSwap" :show-inbound-estimate="result.transport !== 'PUBLIC_TRANSIT' && !inboundRoute(day.day_no, item.id)" @alternative="openAlternatives" />
                <TransitLegCard v-if="result.transport === 'PUBLIC_TRANSIT' && (day.items[itemIndex + 1] || result.accommodation)" :leg="transitLeg(day.day_no, `ITEM:${item.id}`)" :from="item.place_name" :to="day.items[itemIndex + 1]?.place_name ?? result.accommodation!.place_name" :loading="transitLoading" />
              </template>
              <div v-if="result.accommodation && result.transport !== 'PUBLIC_TRANSIT'" class="travel-line"><span>↓</span> 숙소 복귀 · {{ result.accommodation.place_name }}<template v-if="day.accommodation_return_travel_minutes"> · {{ transportLabel[result.transport] }} {{ day.accommodation_return_travel_minutes }}분 · {{ formatDistance(day.accommodation_return_distance_m) }}</template></div>
            </div>
          </section>
          <p v-if="routeLoading" class="route-status">자동차 이동 경로를 불러오는 중이에요.</p>
          <p v-else-if="routeError" class="course-error">{{ routeError }}</p>
        </main>

        <aside class="course-side">
          <section class="course-summary-card">
            <span class="summary-kicker">TRIP SUMMARY</span>
            <h3>코스 요약</h3>
            <dl>
              <dt>여행 일정</dt><dd>{{ tripNights }}박 {{ tripDays }}일</dd>
              <dt>방문 장소</dt><dd>{{ visitCount }}곳</dd>
              <dt>평균 혼잡도</dt><dd>{{ averageCongestionText }}</dd>
              <dt>이동수단</dt><dd>{{ transportLabel[result.transport] }}</dd>
              <dt>숙소</dt><dd>{{ result.accommodation?.place_name ?? '미정' }}</dd>
            </dl>
            <button v-if="canModify" class="btn accommodation-change" :disabled="accommodationLoading || accommodationSaving" @click="chooseAccommodation">{{ result.accommodation ? '숙소 변경' : '숙소 찾아보기' }}</button>
          </section>
          <p v-if="accommodationSaving" class="route-status">숙소를 서버에 저장하고 확인하고 있어요.</p>
          <p v-if="accommodationSaveError" role="alert" class="course-error">{{ accommodationSaveError }} <button class="text-link" @click="restoreResult">다시 불러오기</button></p>
          <AccommodationRecommendations
            v-if="canModify && (!result.accommodation || accommodationPickerOpen)"
            :items="recommendedAccommodations"
            :loading="accommodationLoading"
            :saving="accommodationSaving"
            :error="accommodationError"
            @select="selectRecommendedAccommodation"
          />
        </aside>
      </div>

    </section>

    <AlternativePlaceModal v-if="selected" :item="selected" :alternatives="alternatives" :loading="altLoading" :notice="altNotice" :busy="swapping" @close="selected = undefined" @select="replace" />
    <div v-if="saveOpen" class="modal-backdrop" @click.self="saveOpen = false">
      <section class="course-modal save-modal">
        <button class="modal-close" @click="saveOpen = false">×</button>
        <h2>코스 저장</h2>
        <label>코스명<input v-model="title" maxlength="100" placeholder="1~100자"></label>
        <p v-if="!title.trim()" class="course-error">공백이 아닌 코스명을 입력해 주세요.</p>
        <p v-if="saveError" class="course-error">{{ saveError }}</p>
        <button class="btn primary wide" :disabled="!title.trim() || saveLoading" @click="save">{{ saveLoading ? '저장 중…' : '저장' }}</button>
      </section>
    </div>
    <div v-if="toast" class="toast" role="status" aria-live="polite">{{ toast }}</div>
  </div>
</template>

<style scoped>
.ai-course-page { --course-accent: #1f7a6d; --course-accent-dark: #145147; --course-accent-bg: #eff9f6; --course-on-ac: #fff; --course-bg: #f7faf8; --course-line: #e6ede9; --course-line-2: #d1ded8; --course-surface: #fff; --course-surface-2: #f8faf9; --course-text: #1c2925; --course-text-2: #61736d; --course-text-3: #61736d; --course-muted: var(--course-text-2); background: var(--course-bg); }
/* Follow both the saved theme and the OS preference, like the shared tokens. */
:global(:root[data-theme="dark"] .ai-course-page) { --course-accent: var(--primary); --course-accent-dark: var(--primary-dark); --course-accent-bg: var(--ac-bg); --course-on-ac: var(--on-ac); --course-bg: var(--bg); --course-line: var(--border); --course-line-2: var(--line2); --course-surface: var(--surface); --course-surface-2: var(--muted); --course-text: var(--text); --course-text-2: var(--sub); --course-text-3: var(--tx3); }
@media(prefers-color-scheme:dark) {
  :global(:root:not([data-theme="light"]) .ai-course-page) { --course-accent: var(--primary); --course-accent-dark: var(--primary-dark); --course-accent-bg: var(--ac-bg); --course-on-ac: var(--on-ac); --course-bg: var(--bg); --course-line: var(--border); --course-line-2: var(--line2); --course-surface: var(--surface); --course-surface-2: var(--muted); --course-text: var(--text); --course-text-2: var(--sub); --course-text-3: var(--tx3); }
}
.ai-course-page .generation-state { border: 0; box-shadow: none; background: transparent; }
.ai-course-page .toast {
  bottom: calc(var(--mobile-tabbar-h, 0px) + 24px);
  width: max-content;
  max-width: calc(100vw - 32px);
  box-sizing: border-box;
  white-space: normal;
  overflow-wrap: anywhere;
  text-align: center;
  line-height: 1.5;
  pointer-events: none;
}
.builder-page { background: var(--course-bg); min-height: calc(100vh - 80px); padding-bottom: 40px; }
.builder-page .course-page-header { display: flex; align-items: center; justify-content: space-between; gap: 24px; padding-block: 38px 28px; }
.builder-page .course-page-header h1 { font-size: clamp(22px, 2.5vw, 30px); letter-spacing: -.04em; }
.ai-course-page .course-shell.builder-shell { border: 0; background: transparent; box-shadow: none; overflow: visible; }
.history-trigger { flex-shrink: 0; border: 1px solid var(--line); color: var(--ac); border-radius: 24px; background: var(--ac-bg); padding: 9px 14px; font-size: 12px; font-weight: 700; }
.history-dialog { width: min(600px, calc(100% - 32px)); margin: auto; padding: 26px; border: 1px solid var(--line); border-radius: 22px; background: var(--surf); color: var(--tx); max-height: 80vh; overflow: auto; }
.history-dialog::backdrop { background: #10292380; }
.history-close { display: block; margin-left: auto; width: 36px; height: 36px; font-size: 24px; }
@media(max-width: 767px) { .builder-page .course-page-header { align-items: flex-start; flex-direction: column; gap: 14px; padding-block: 24px; } }
/* 비 예보일 실내 위주 배지 - 일차 머리의 날씨 알약 옆에 붙는 단색 알약 */
.rain-notice{display:inline-flex;align-items:center;margin:0;padding:4px 9px;border-radius:20px;background:var(--course-accent);color:var(--course-on-ac);font-size:11px;font-weight:700;white-space:nowrap}
/* 일차 머리의 예보·총 이동·경로 안내는 본문 크기 그대로라 일정보다 눈에 먼저 들어왔다 - 보조 정보 크기로.
   route-status는 일정 안팎에 흩어져 있다. 한 화면에 같은 성격의 안내가 두 모양으로 뜨지 않게 전부 같이 잡는다 */
.daily-weather,
.route-summary,
.route-status {
  margin: 7px 0 0;
  color: var(--course-text-2);
  font-size: 0.73rem;
  line-height: 1.55;
  word-break: keep-all;
  overflow-wrap: break-word;
}
/* 좌표 보정 같은 긴 안내는 문단으로 흐르면 일정을 가린다 - 옅은 상자에 담는다 */
.route-status {
  padding: 8px 10px;
  border-radius: 10px;
  background: var(--course-surface-2);
}

.result-actions-block{display:grid;align-self:center}
.temporary-course-notice{margin:0;line-height:1.45;word-break:keep-all}
.ai-course-page,.ai-course-page :deep(input),.ai-course-page :deep(button){font-family:'Noto Sans KR','Be Vietnam Pro',Figtree,sans-serif}
.ai-course-page :deep(h1),.ai-course-page :deep(h2),.ai-course-page :deep(h3){font-family:Figtree,'Noto Sans KR',sans-serif}
/* Result layout follows the supplied itinerary reference, independently of the builder card. */
.ai-course-page .result-shell{padding-top:42px;border:0;background:transparent;box-shadow:none}
.course-result-head{padding:0 0 26px;margin-bottom:24px;border:0;border-bottom:1px solid var(--course-line);border-radius:0;background:transparent;box-shadow:none;align-items:center}
.result-label{display:inline-block;font-size:10px;letter-spacing:0;background:var(--course-accent-bg);padding:3px 9px;border-radius:20px}
.course-result-head h2{font-size:26px;line-height:1.4;margin:0 0 5px;color:var(--course-text)}
.course-result-head p{font-size:12px}
.result-condition-tags{display:inline-flex;gap:8px;margin-top:7px}.result-condition-tags span{font-size:11px;padding:0;background:transparent;font-weight:500}
.result-actions-block{flex:0 1 auto;width:auto;gap:8px}
.result-actions-row{display:flex;gap:8px;flex-wrap:wrap;justify-content:flex-end}
.result-actions-row .btn{width:auto;min-height:34px;padding:8px 11px;border-radius:9px;font-size:11px;gap:5px;white-space:nowrap;background:var(--course-surface);border:1px solid var(--course-line);color:var(--course-text)}
.result-actions-row .result-regenerate{background:var(--course-accent-bg);color:var(--course-accent-dark)}
.result-actions-row .result-save{background:var(--course-accent);color:var(--course-on-ac);border-color:var(--course-accent)}
.course-result-head .temporary-course-notice{text-align:right;font-size:10px;color:var(--course-text-3)}
.course-result-grid{grid-template-columns:minmax(0,2.08fr) minmax(280px,1fr);gap:28px;align-items:start}
.itinerary-card{width:100%;min-width:0;border:1px solid var(--course-line);border-radius:16px;overflow:hidden;background:var(--course-surface);box-shadow:0 2px 4px #173a3305}
.day-tabs{display:flex;overflow-x:auto;background:var(--course-surface-2);border-bottom:1px solid var(--course-line);padding:0 24px}
.day-tabs button{flex-shrink:0;padding:20px 14px 17px;border-bottom:2px solid transparent;font-size:13px;color:var(--course-text-2);white-space:nowrap}
.day-tabs button[aria-selected=true]{color:var(--course-accent);border-color:var(--course-accent);font-weight:700}
.day-tabs small{font-size:11px;margin-left:3px}.day-tabs button:focus-visible{outline:2px solid var(--course-accent);outline-offset:-4px}
.course-day{margin:0;padding:0 24px 12px;border:0;border-radius:0;background:transparent;box-shadow:none}
.course-day>header{display:flex;flex-wrap:wrap;gap:10px;padding:20px 0 15px;border-bottom:1px solid var(--course-line)}
.day-title b{font-size:13px;color:var(--course-accent)}.day-title span{font-size:11px;color:var(--course-text-3)}
.course-day :deep(.course-item){padding:16px 14px;grid-template-columns:54px 104px minmax(0,1fr);gap:14px;border:1px solid var(--course-line);border-radius:13px;background:var(--course-surface-2)}
.course-day :deep(.course-item img){width:104px;height:82px;border-radius:10px}
.course-day :deep(.item-time b){font-size:12px}.course-day :deep(.item-time small){font-size:10px}
.course-day :deep(.item-head h3){font-size:15px}.course-day :deep(.item-head small){font-size:10px}
.course-day :deep(.level){font-size:10px;padding:3px 6px}.course-day :deep(.item-reason){font-size:11px;line-height:1.7}
.course-day :deep(.btn.small){font-size:10px;padding:5px 9px;border-radius:8px}.course-day :deep(.travel-line){font-size:10px;padding-block:16px;color:var(--course-text-3)}
.course-side{display:grid;gap:24px;position:static}
.course-summary-card{border:1px solid var(--course-line);border-radius:16px;padding:24px;background:var(--course-surface);box-shadow:0 2px 4px #173a3305}
.summary-kicker{font-size:10px;letter-spacing:.06em}.course-summary-card h3{font-size:17px;margin:6px 0 20px}
.course-summary-card dl{font-size:12px;row-gap:15px}.accommodation-change{margin-top:20px;width:100%;padding:9px;font-size:11px;border:1px solid var(--course-line);border-radius:9px}
:deep(.accommodation-recommendations){padding:24px;border-color:var(--course-line);border-radius:16px;box-shadow:0 2px 4px #173a3305}
:deep(.accommodation-recommendations article){padding:12px;border:1px solid var(--course-line);border-radius:12px;margin-top:12px;background:var(--course-surface-2)}
@media(max-width:1023px){.course-result-head{align-items:flex-start;flex-direction:column;gap:18px}.result-actions-block{width:100%}.result-actions-row{justify-content:flex-start}.course-result-head .temporary-course-notice{text-align:left}.course-result-grid{grid-template-columns:minmax(0,1fr) 280px;gap:18px}}
@media(max-width:767px){.ai-course-page .result-shell{padding-top:24px}.course-result-head h2{font-size:24px}.course-result-grid{display:flex;flex-direction:column}.course-result-grid>main{order:0}.course-side{display:grid;width:100%;order:1}.course-summary-card,:deep(.accommodation-recommendations){order:initial}.result-actions-row{display:grid;grid-template-columns:1fr 1fr;width:100%}.result-actions-row .btn{width:100%;min-height:40px;font-size:11px}.day-tabs{padding:0 8px}.day-tabs button{padding:16px 10px;font-size:12px}.day-tabs small{font-size:10px}.course-day{padding:0 12px 12px}.course-day>header{padding:16px 0;align-items:flex-start;flex-direction:column}.course-day :deep(.course-item){grid-template-columns:72px minmax(0,1fr);padding:12px;gap:6px 10px}.course-day :deep(.course-item img){width:72px;height:76px}.course-day :deep(.item-head h3){font-size:14px}.course-day :deep(.travel-line){padding-left:20px}.course-day :deep(.item-reason){font-size:11px}}
</style>
