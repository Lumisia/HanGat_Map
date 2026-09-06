<script setup lang="ts">
import { computed, onMounted, reactive, ref } from 'vue'
import { useRouter } from 'vue-router'
import { useAuthStore } from '../../app/stores/auth'
import CourseConditionForm from '../../components/course/CourseConditionForm.vue'
import CourseItemCard from '../../components/course/CourseItemCard.vue'
import BudgetGauge from '../../components/course/BudgetGauge.vue'
import AlternativePlaceModal from '../../components/course/AlternativePlaceModal.vue'
import CongestionRescheduleModal from '../../components/course/CongestionRescheduleModal.vue'
import AccommodationRecommendations from '../../components/course/AccommodationRecommendations.vue'
import { courseGenerationErrorMessage, courseMockService } from '../../services/courseMockService'
import { storePendingCourseClaim, takePendingCourseClaim } from '../../services/pendingCourseClaim'
import { routeSummary, accessNotices } from '../../services/course/courseSummary'
import { ApiError } from '../../api/errors.js'
import { levelOf } from '../../utils/congestion'
import { levelLabel } from '../../data/data'
import type { AccommodationInput, AccommodationRecommendation, AlternativePlace, CarDayRoute, CarRouteLeg, CongestionRescheduleOption, CourseCondition, CourseItem, CourseResult } from '../../assets/types/course'

const now = new Date()
const later = new Date(now)
later.setDate(now.getDate() + 2)
const iso = (date: Date) => date.toISOString().slice(0, 10)

const condition = reactive<CourseCondition>({
  start_date: iso(now),
  end_date: iso(later),
  people: 2,
  budget_total: 400000,
  transport: 'RENTAL_CAR',
  course_regions: [],
  course_styles: [],
  course_place_preferences: [],
})

const result = ref<CourseResult>()
const loading = ref(false)
const error = ref('')
const editing = ref(true)
const selected = ref<CourseItem>()
const alternatives = ref<AlternativePlace[]>([])
const altLoading = ref(false)
const altNotice = ref('')
const swapping = ref(false)
const rescheduleSelected = ref<CourseItem>()
const rescheduleOptions = ref<CongestionRescheduleOption[]>([])
const rescheduleLoading = ref(false)
const recommendedAccommodations = ref<AccommodationRecommendation[]>([])
const accommodationLoading = ref(false)
const accommodationError = ref('')
const routeLoading = ref(false)
const routeError = ref('')
const saveOpen = ref(false)
const title = ref('')
const saveError = ref('')
const saveLoading = ref(false)
const toast = ref('')
const auth = useAuthStore()
const router = useRouter()

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
  if (!result.value || result.value.transport !== 'RENTAL_CAR') return
  routeLoading.value = true
  routeError.value = ''
  try {
    result.value = { ...result.value, car_route: await courseMockService.getCarRoute(result.value) }
  } catch {
    routeError.value = '이동 경로를 불러오지 못했어요.'
  } finally {
    routeLoading.value = false
  }
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
const regionSummary = computed(() => condition.course_regions.map(region => region.name).join(' · ') || '전체')
const styleSummary = computed(() => condition.course_styles.map(style => style.name).join(' · '))
const estimatedCost = computed(() => {
  const summary = result.value?.budget_summary
  if (!summary?.has_cost_data) return '정보 없음'
  if (summary.total_expected_min == null || summary.total_expected_max == null) return '정보 없음'
  return summary.total_expected_min === summary.total_expected_max
    ? `${summary.total_expected_max.toLocaleString()}원`
    : `${summary.total_expected_min.toLocaleString()} ~ ${summary.total_expected_max.toLocaleString()}원`
})
// 팀 표준 3단계(여유 <40 / 보통 <70 / 혼잡) - 백엔드 CongestionLevel.from과 같은 컷. 화면마다 다른 컷을 쓰면 같은 평균이 다른 등급으로 보인다
const congestionLabel = (rate?: number) => rate == null ? '-' : levelLabel[levelOf(rate)]

async function generate(next: CourseCondition, regenerate = false) {
  Object.assign(condition, JSON.parse(JSON.stringify(next)) as CourseCondition)
  loading.value = true
  error.value = ''
  try {
    result.value = regenerate
      ? await courseMockService.regenerateCourse(condition)
      : await courseMockService.generateCourse(condition)
    recommendedAccommodations.value = []
    editing.value = false
    void loadCarRoute()
    if (!condition.accommodation) {
      accommodationLoading.value = true
      accommodationError.value = ''
      void courseMockService.getRecommendedAccommodations(result.value).then((items) => {
        recommendedAccommodations.value = items
      }).catch(() => {
        recommendedAccommodations.value = []
        accommodationError.value = '주변 숙소를 불러오지 못했어요.'
      }).finally(() => {
        accommodationLoading.value = false
      })
    }
    requestAnimationFrame(() => window.scrollTo({ top: 0, behavior: 'smooth' }))
  } catch (generationFailure) {
    error.value = courseGenerationErrorMessage(generationFailure)
  } finally {
    loading.value = false
  }
}

async function selectRecommendedAccommodation(accommodation: AccommodationInput) {
  if (!result.value) return
  loading.value = true
  error.value = ''
  try {
    const savedAccommodation = await courseMockService.updateAccommodation(
      result.value,
      accommodation,
    )
    condition.accommodation = { ...savedAccommodation }
    result.value = courseMockService.applyAccommodationSelection(
      result.value,
      savedAccommodation,
    )
    delete result.value.car_route
    void loadCarRoute()
    recommendedAccommodations.value = []
  } catch {
    error.value = '숙소를 저장하지 못했어요. 기존 일정은 그대로 유지됩니다.'
  } finally {
    loading.value = false
  }
}

async function openAlternatives(item: CourseItem) {
  if (!result.value) return
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
  if (!result.value || !selected.value || swapping.value) return   // 더블클릭이면 두 번째 스왑이 첫 교체를 '원래 장소'로 덮는다
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
  // 렌터카 경로는 교체된 장소 기준으로 다시 받는다 - 숙소 변경과 같은 처리
  void loadCarRoute()
  toast.value = previousAverage != null && result.value.average_congestion_rate != null
    ? `${replacementName}으로 변경했어요. 평균 혼잡도는 ${congestionLabel(result.value.average_congestion_rate)}이에요.`
    : `${replacementName}으로 변경하고 동선을 다시 계산했어요.`
  setTimeout(() => { toast.value = '' }, 2600)
}

async function openReschedule(item: CourseItem) {
  if (!result.value) return
  rescheduleSelected.value = item
  rescheduleOptions.value = []
  rescheduleLoading.value = true
  try {
    rescheduleOptions.value = await courseMockService.getQuieterTimeOptions(result.value, item.id)
  } finally {
    rescheduleLoading.value = false
  }
}

async function reschedule(option: CongestionRescheduleOption) {
  if (!result.value || !rescheduleSelected.value) return
  const item = rescheduleSelected.value
  result.value = await courseMockService.rescheduleCourseItem(result.value, item.id, option)
  rescheduleSelected.value = undefined
  toast.value = `${item.place_name} 방문 시간을 ${formatDate(option.visit_date)} ${option.start_time}로 변경했어요. 변경 시간대는 ${congestionLabel(option.congestion_rate)}으로 예상돼요.`
  setTimeout(() => { toast.value = '' }, 3200)
}

function openSave() {
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
    saveOpen.value = false
    toast.value = '코스를 저장했어요.'
  } catch (saveFailure) {
    saveError.value = saveFailure instanceof Error ? saveFailure.message : '코스를 저장하지 못했어요.'
  } finally {
    saveLoading.value = false
  }
}

onMounted(async () => {
  if (!auth.isAuthenticated) return
  const pending = takePendingCourseClaim()
  if (!pending) return

  saveLoading.value = true
  try {
    result.value = await courseMockService.saveCourse(pending.course, pending.title)
    Object.assign(condition, pending.condition)
    editing.value = false
    toast.value = '코스를 저장했어요.'
  } catch {
    error.value = '로그인 전 생성한 코스를 저장하지 못했어요. 다시 생성해 주세요.'
  } finally {
    saveLoading.value = false
  }
})

const formatDate = (value: string) => new Intl.DateTimeFormat('ko-KR', {
  month: 'long',
  day: 'numeric',
  weekday: 'short',
  timeZone: 'UTC',
}).format(new Date(`${value}T00:00:00Z`))

const formatShortDate = (value: string) => new Intl.DateTimeFormat('ko-KR', {
  month: 'numeric',
  day: 'numeric',
  timeZone: 'UTC',
}).format(new Date(`${value}T00:00:00Z`))

const formatDistance = (metres?: number | null) => metres == null ? '정보 없음' : `${(metres / 1000).toFixed(1)}km`
</script>

<template>
  <div class="ai-course-page">
    <header class="course-page-header">
      <div>
        <span>AI 코스 만들기</span>
        <h1>{{ loading ? '제주 여행을 구성하고 있어요' : editing ? '나만의 제주 여행' : '추천 코스' }}</h1>
        <p>{{ loading ? '선택한 조건을 바탕으로 잠시만 기다려 주세요.' : editing ? '여행 조건을 선택하면 혼잡도와 동선을 고려해 코스를 추천해드려요.' : '선택한 조건과 예상 혼잡도를 반영한 제주 여행 일정이에요.' }}</p>
      </div>
    </header>

    <section v-if="loading" class="course-shell generation-state" aria-live="polite">
      <div class="generation-spinner" aria-hidden="true" />
      <span class="result-label">AI 코스 생성 중</span>
      <h2>여행 조건을 분석하고 있어요.</h2>
      <p>혼잡도와 이동 동선을 고려해 코스를 만들고 있어요.</p>
    </section>

    <section v-else-if="editing" class="course-shell">
      <CourseConditionForm :initial="condition" :loading="loading" @submit="generate" />
      <p v-if="error" class="course-error">{{ error }} <button class="text-link" @click="generate(condition)">다시 시도</button></p>
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
          <div class="result-metrics">
            <span>평균 혼잡도 <b>{{ congestionLabel(result.average_congestion_rate) }}</b></span>
            <span>예상 비용 <b>{{ estimatedCost }}</b></span>
          </div>
        </div>
        <div class="result-actions-row">
          <button class="btn" @click="viewOnMap">지도에서 보기</button>
          <button class="btn result-save" :disabled="result.status === 'SAVED'" @click="openSave">{{ result.status === 'SAVED' ? '저장 완료' : '코스 저장' }}</button>
        </div>
      </header>

      <div class="course-result-grid">
        <main>
          <section v-for="day in result.days" :key="day.day_no" class="course-day">
            <header><b>DAY {{ day.day_no }}</b><span>{{ formatDate(day.visit_date) }}</span></header>
            <p v-if="result.transport === 'RENTAL_CAR'" class="route-summary">
              총 이동 {{ routeSummary([routeForDay(day.day_no) ?? {}], routeLoading) }}
            </p>
            <div class="day-timeline">
              <p v-for="notice in accessNotices(routeForDay(day.day_no) ? [routeForDay(day.day_no)!] : [])" :key="notice" class="route-status">{{ notice }}</p>
              <div v-if="result.accommodation" class="travel-line"><span>↓</span> 숙소 출발 · {{ result.accommodation.place_name }}<template v-if="day.accommodation_departure_travel_minutes"> · {{ transportLabel[result.transport] }} {{ day.accommodation_departure_travel_minutes }}분 · {{ formatDistance(day.accommodation_departure_distance_m) }}</template></div>
              <template v-for="item in day.items" :key="item.id">
                <div v-if="inboundRoute(day.day_no, item.id)" class="travel-line">
                  <span>↓</span> 이동 약 {{ formatDuration(inboundRoute(day.day_no, item.id)?.duration_seconds) }} ·
                  {{ formatDistance(inboundRoute(day.day_no, item.id)?.distance_meters) }}
                </div>
                <CourseItemCard :item="item" :transport="result.transport" @alternative="openAlternatives" @reschedule="openReschedule" />
              </template>
              <div v-if="result.accommodation" class="travel-line"><span>↓</span> 숙소 복귀 · {{ result.accommodation.place_name }}<template v-if="day.accommodation_return_travel_minutes"> · {{ transportLabel[result.transport] }} {{ day.accommodation_return_travel_minutes }}분 · {{ formatDistance(day.accommodation_return_distance_m) }}</template></div>
            </div>
          </section>
          <p v-if="routeLoading" class="route-status">자동차 이동 경로를 불러오는 중이에요.</p>
          <p v-else-if="routeError" class="course-error">{{ routeError }}</p>
        </main>

        <aside class="course-side">
          <BudgetGauge :summary="result.budget_summary" />
          <section class="course-summary-card">
            <span class="summary-kicker">TRIP SUMMARY</span>
            <h3>코스 요약</h3>
            <dl>
              <dt>여행 일정</dt><dd>{{ tripNights }}박 {{ tripDays }}일</dd>
              <dt>방문 장소</dt><dd>{{ visitCount }}곳</dd>
              <dt>평균 혼잡도</dt><dd>{{ congestionLabel(result.average_congestion_rate) }}</dd>
              <dt>예상 비용</dt><dd>{{ estimatedCost }}</dd>
              <dt>전체 예산</dt><dd>{{ result.budget_total?.toLocaleString() }}원</dd>
              <dt>이동수단</dt><dd>{{ transportLabel[result.transport] }}</dd>
              <dt>숙소</dt><dd>{{ result.accommodation?.place_name ?? '미정' }}</dd>
            </dl>
          </section>
          <AccommodationRecommendations
            v-if="!result.accommodation"
            :items="recommendedAccommodations"
            :loading="accommodationLoading"
            :error="accommodationError"
            @select="selectRecommendedAccommodation"
          />
        </aside>
      </div>

      <div class="result-actions">
        <button class="btn" @click="editing = true">조건 수정</button>
        <button class="btn primary" :disabled="loading" @click="generate(condition, true)">다른 코스 만들기</button>
      </div>
    </section>

    <AlternativePlaceModal v-if="selected" :item="selected" :alternatives="alternatives" :loading="altLoading" :notice="altNotice" :busy="swapping" @close="selected = undefined" @select="replace" />
    <CongestionRescheduleModal v-if="rescheduleSelected" :item="rescheduleSelected" :options="rescheduleOptions" :loading="rescheduleLoading" @close="rescheduleSelected = undefined" @select="reschedule" />
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
    <div v-if="toast" class="toast">{{ toast }}</div>
  </div>
</template>

<style scoped>
@media (max-width: 767px) {
  .course-result-grid {
    display: flex;
    flex-direction: column;
  }

  .course-side {
    display: contents;
  }

  .course-result-grid > main {
    order: 2;
    min-width: 0;
    width: 100%;
  }

  :deep(.accommodation-recommendations) {
    order: 1;
    min-width: 0;
    width: 100%;
  }

  :deep(.budget) {
    order: 3;
    min-width: 0;
    width: 100%;
  }

  .course-summary-card {
    order: 4;
    min-width: 0;
    width: 100%;
  }
}
</style>
