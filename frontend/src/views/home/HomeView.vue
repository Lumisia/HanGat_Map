<script setup lang="ts">
// 메인 페이지 (담당: 정동현)
// 구성: ① 서비스 소개 히어로(MAIN_003) ② 퀵스타트 ③ 코스 추천 3종 → 코스 상세(MAIN_002) ④ 장소 추천 캐러셀(MAIN_001)
// 정직성 원칙: 혼잡은 '날짜 단위 예보'로만 표현한다 (시간대별 혼잡 표현 금지 - 데이터 없음)
import { computed, nextTick, onBeforeUnmount, onMounted, ref } from 'vue'
import { useTravelStore } from '../../app/stores/travel'
import type { DailyWeather } from '../../services/WeatherService'
import { WeatherService } from '../../services/WeatherService'
import CalmPlaceService, { type CalmPlaceCard } from '../../services/CalmPlaceService'
import CourseService, { type CourseCard } from '../../services/CourseService'
import PlaceImage from '../../components/common/PlaceImage.vue'
import CongestionBadge from '../../components/common/CongestionBadge.vue'

const store = useTravelStore()
const weeklyWeather = ref<DailyWeather[]>([])
// true = 기상청 실데이터, false = 백엔드 미가동 시 시연용 샘플 폴백
const weatherLive = ref(false)

const todayLabel = new Date().toLocaleDateString('ko-KR', {
  month: 'long',
  day: 'numeric',
})
const fmtDate = (iso: string) => {
  const [, m, d] = iso.split('-')
  return `${Number(m)}월 ${Number(d)}일`
}

// MAIN_002: 코스 추천 3종 - 새벽 배치가 만든 실데이터(백엔드 미가동 시 목업 폴백)
const courseCards = ref<CourseCard[]>([])
const coursesLive = ref(false)

// MAIN_001: 오늘 날짜 예보 기준 한적한 관광지 (백엔드 실데이터, 실패 시 목업 폴백)
const calmPlaces = ref<CalmPlaceCard[]>([])
const calmLive = ref(false)
// 장소 추천 캐러셀: 화살표로 후보를 한 장씩 넘긴다
const track = ref<HTMLElement | null>(null)
const canPrev = ref(false)
const canNext = ref(false)
const updateArrows = () => {
  const el = track.value
  if (!el) return
  canPrev.value = el.scrollLeft > 4
  canNext.value = el.scrollLeft < el.scrollWidth - el.clientWidth - 4
}
const slideBy = (dir: 1 | -1) => {
  const el = track.value
  if (!el) return
  const card = el.querySelector<HTMLElement>('.poster-item')
  const gap = 16
  el.scrollBy({ left: dir * ((card?.clientWidth ?? 280) + gap), behavior: 'smooth' })
}

onMounted(async () => {
  window.addEventListener('resize', updateArrows)
  await nextTick()
  updateArrows()
  const forecast = await WeatherService.getWeeklyForecast()
  weeklyWeather.value = forecast.days
  weatherLive.value = forecast.live
  const calm = await CalmPlaceService.getCalmPlaces()
  calmPlaces.value = calm.cards
  calmLive.value = calm.live
  const courses = await CourseService.getMainCourses()
  courseCards.value = courses.cards
  coursesLive.value = courses.live
  await nextTick()
  updateArrows()
})
onBeforeUnmount(() => window.removeEventListener('resize', updateArrows))
</script>
<template>
  <div>
    <!-- ① MAIN_003: 서비스 소개 히어로 (전면 이미지 + 좌측 카피) -->
    <section class="hero-full">
      <img
        class="hero-bg"
        src="/images/hero-jeju.jpg"
        alt="광치기해변에서 바라본 성산일출봉"
      >
      <div class="hero-scrim" />
      <div class="hero-inner">
        <span class="pill">제주 분산 여행 가이드</span>
        <h1>사람을 피해,<br><em>제주를 더 깊이.</em></h1>
        <p class="hero-desc">
          혼잡도와 날씨, 이동 시간을 함께 읽어<br>나만의 한적한 제주 코스를 만들어요.
        </p>
        <div class="actions">
          <RouterLink
            class="btn primary"
            to="/ai-course"
          >
            AI 코스 추천받기
          </RouterLink><RouterLink
            class="btn ghost"
            to="/map"
          >
            혼잡도 지도 보기
          </RouterLink>
        </div>
        <div class="trust">
          <span>날짜별 혼잡 예보 반영</span><span>숨은 명소 발견</span><span>착한가격 검증가</span>
        </div>
      </div>
      <span class="hero-credit">사진: 한국관광공사 TourAPI</span>
    </section>
    <!-- ② 퀵스타트 -->
    <section class="quick section">
      <div>
        <span class="eyebrow">QUICK START</span>
        <h2>이번 제주는 어떻게 떠날까요?</h2>
      </div>
      <div class="quick-grid">
        <div><small>여행 일정</small><b>{{ fmtDate(store.condition.startDate) }} ~ {{ fmtDate(store.condition.endDate) }}</b></div>
        <div><small>함께 가는 사람</small><b>{{ store.condition.people }}명 · {{ store.condition.preference }}</b></div>
        <div><small>여행 취향</small><b>{{ store.condition.styles.join(' · ') }}</b></div>
        <RouterLink
          class="btn primary"
          to="/ai-course"
        >
          조건 바꾸기 →
        </RouterLink>
      </div>
    </section>
    <!-- 제주 일주일 날씨 -->
    <section class="section weather-section">
      <div class="section-head">
        <div>
          <span class="eyebrow">WEEKLY JEJU</span>
          <h2>제주 일주일 날씨</h2>
          <p class="muted">
            {{ weatherLive ? '기상청 단기·중기예보 (날짜 단위)' : '시연용 예상 데이터 · 백엔드 연결 대기' }}
          </p>
        </div>
      </div>
      <div class="weather-grid">
        <article
          v-for="weather in weeklyWeather"
          :key="weather.day"
        >
          <b>{{ weather.day }}</b>
          <span
            class="weather-icon"
            aria-hidden="true"
          >{{ weather.icon }}</span>
          <strong>{{ weather.temperature === null ? '-' : `${weather.temperature}°` }}</strong>
          <small>{{ weather.description }}</small>
        </article>
      </div>
    </section>
    <!-- ③ MAIN_002: 코스 추천 3종 (클릭 → 코스 상세) -->
    <section class="section">
      <div class="section-head">
        <div>
          <span class="eyebrow">READY-MADE COURSE</span>
          <h2>한적한 곳으로 이어 만든 추천 코스</h2>
          <p class="muted">
            {{ coursesLive ? '새벽 배치가 그날 여유로운 권역으로 미리 만든 코스' : '시연용 데이터 · 백엔드 연결 대기' }} · 카드를 누르면 코스 상세로 이동해요
          </p>
        </div>
      </div>
      <div class="cards">
        <RouterLink
          v-for="course in courseCards"
          :key="course.id"
          class="home-course-card"
          :to="`/courses/${course.id}`"
        >
          <div class="eyebrow">
            {{ course.conditionLabel }}
          </div>
          <h3>{{ course.title }}</h3>
          <p
            v-if="course.stops"
            class="course-stops"
          >
            {{ course.stops }}
          </p>
          <p class="muted course-highlight">
            {{ course.highlight }}
          </p>
          <p class="muted course-highlight">
            {{ course.budgetLabel }}
          </p>
          <div class="course-foot">
            <CongestionBadge
              v-if="course.level"
              :level="course.level"
            />
            <span v-else />
            <span class="text-link">코스 상세 →</span>
          </div>
        </RouterLink>
      </div>
    </section>
    <!-- ④ MAIN_001: 장소 추천 캐러셀 -->
    <section class="section">
      <div class="section-head">
        <div>
          <span class="eyebrow">TODAY'S CALM</span>
          <h2>오늘 한적한 곳부터 추천해요</h2>
          <p class="muted">
            {{ todayLabel }} 예보 기준 집중률 낮은 순 · {{ calmLive ? '한국관광공사 집중률 예보' : '시연용 데이터 · 백엔드 연결 대기' }}
          </p>
        </div>
        <RouterLink
          class="text-link"
          to="/map"
        >
          전체 지도 보기 →
        </RouterLink>
      </div>
      <div class="poster-carousel">
        <button
          class="poster-nav prev"
          type="button"
          aria-label="이전 추천 장소 보기"
          :disabled="!canPrev"
          @click="slideBy(-1)"
        >
          ‹
        </button>
        <div
          ref="track"
          class="poster-track"
          @scroll.passive="updateArrows"
        >
          <div
            v-for="(p, i) in calmPlaces"
            :key="p.key"
            class="poster-item"
          >
            <!-- 실데이터 장소는 아직 상세 페이지가 없어 링크 없이 렌더 (장소 상세 실연동 때 교체) -->
            <RouterLink
              v-if="p.detailId"
              class="poster-frame"
              :to="`/places/${p.detailId}`"
            >
              <PlaceImage
                :src="p.imageUrl ?? '/images/placeholder.svg'"
                :alt="`${p.name} 사진`"
              />
              <span class="poster-rank">{{ i + 1 }}</span>
            </RouterLink>
            <div
              v-else
              class="poster-frame"
            >
              <PlaceImage
                :src="p.imageUrl ?? '/images/placeholder.svg'"
                :alt="`${p.name} 사진`"
              />
              <span class="poster-rank">{{ i + 1 }}</span>
            </div>
            <div class="poster-info">
              <h3>{{ p.name }}</h3>
              <p class="poster-stats">
                <span :class="['lv', p.level.toLowerCase()]">{{ p.levelLabel }}</span> · {{ p.region }}
              </p>
              <small class="poster-reason">{{ p.reason }}</small>
              <RouterLink
                v-if="p.detailId"
                class="poster-cta"
                :to="`/places/${p.detailId}`"
              >
                자세히 보기
              </RouterLink>
            </div>
          </div>
        </div>
        <button
          class="poster-nav next"
          type="button"
          aria-label="다음 추천 장소 보기"
          :disabled="!canNext"
          @click="slideBy(1)"
        >
          ›
        </button>
      </div>
    </section>
    <!-- MAIN_003: 데이터 출처 고지 -->
    <p class="data-source">
      관광정보·사진: 한국관광공사 TourAPI · 착한가격업소: 행정안전부 · 날씨: 기상청 ·
      혼잡도는 한국관광공사 집중률 예보 기반으로 실제와 다를 수 있습니다 (일부 화면은 시연용 데이터)
    </p>
  </div>
</template>
<style src="../../assets/home.css"></style>
<style scoped>
.hero-full {
  position: relative;
  min-height: 620px;
  display: flex;
  align-items: center;
  overflow: hidden;
}
.hero-bg {
  position: absolute;
  inset: 0;
  width: 100%;
  height: 100%;
  object-fit: cover;
}
.hero-scrim {
  position: absolute;
  inset: 0;
  background: linear-gradient(90deg, rgba(10, 26, 24, 0.72) 0%, rgba(10, 26, 24, 0.38) 45%, rgba(10, 26, 24, 0.06) 78%);
}
.hero-inner {
  position: relative;
  z-index: 1;
  width: 100%;
  max-width: 1380px;
  margin: auto;
  padding: 90px 24px;
}
.hero-inner .pill {
  background: rgba(255, 255, 255, 0.18);
  color: #fff;
  backdrop-filter: blur(6px);
}
.hero-inner h1 {
  margin: 22px 0;
  color: #fff;
}
.hero-inner h1 em {
  font-style: normal;
  color: #9fe3d2;
}
.hero-desc {
  color: rgba(255, 255, 255, 0.88);
  font-size: 1.15rem;
  line-height: 1.8;
}
.hero-inner .trust {
  color: rgba(255, 255, 255, 0.78);
}
.hero-credit {
  position: absolute;
  right: 18px;
  bottom: 14px;
  z-index: 1;
  color: rgba(255, 255, 255, 0.72);
  font-size: 0.7rem;
}
/* 전역 .quick은 padding-top:0 - 히어로가 전면 이미지로 바뀌어 다른 섹션과 같은 상단 여백을 되살린다 */
.quick.section {
  padding-top: 100px;
}
.weather-section {
  padding-bottom: 20px;
}
.weather-grid {
  display: grid;
  grid-template-columns: repeat(7, 1fr);
  gap: 14px;
}
.weather-grid article {
  display: grid;
  gap: 8px;
  justify-items: center;
  padding: 28px 12px;
  border-radius: 20px;
  background: var(--muted);
  text-align: center;
}
.weather-grid b {
  font-size: 0.9rem;
  color: var(--sub);
}
.weather-icon {
  font-size: 1.7rem;
  line-height: 1;
}
.weather-grid strong {
  font-size: 2rem;
  letter-spacing: -0.02em;
}
.weather-grid small {
  color: var(--sub);
  font-size: 0.78rem;
}
@media (max-width: 767px) {
  .hero-full {
    min-height: 520px;
  }
  .weather-grid {
    display: flex;
    gap: 10px;
    overflow-x: auto;
    padding-bottom: 6px;
  }
  .weather-grid article {
    flex: 0 0 112px;
  }
}
.home-course-card {
  display: flex;
  flex-direction: column;
  gap: 8px;
  padding: 26px;
  border: 0;
  border-radius: 20px;
  background: var(--surface);
  box-shadow: var(--shadow);
  color: inherit;
  text-decoration: none;
  transition: transform 0.15s ease, background 0.15s ease;
}
.home-course-card:hover {
  transform: translateY(-2px);
}
.home-course-card h3 {
  margin: 0;
  font-size: 1.05rem;
}
.course-stops {
  margin: 0;
  font-size: 0.86rem;
}
.course-highlight {
  margin: 0;
  font-size: 0.8rem;
}
.course-foot {
  display: flex;
  align-items: center;
  justify-content: space-between;
  margin-top: auto;
  padding-top: 8px;
}
.poster-carousel {
  position: relative;
}
.poster-track {
  display: flex;
  gap: 22px;
  overflow-x: auto;
  scroll-snap-type: x mandatory;
  scrollbar-width: none;
  padding: 8px 4px 4px;
}
.poster-track::-webkit-scrollbar {
  display: none;
}
.poster-item {
  flex: 0 0 300px;
  scroll-snap-align: center;
  display: grid;
  gap: 14px;
  justify-items: center;
}
.poster-frame {
  position: relative;
  display: block;
  width: 100%;
  border-radius: 20px;
  overflow: hidden;
  box-shadow: var(--shadow);
}
.poster-frame :deep(img) {
  display: block;
  width: 100%;
  height: 400px;
  object-fit: cover;
  transition: transform 0.25s ease;
}
.poster-frame:hover :deep(img) {
  transform: scale(1.03);
}
.poster-frame::after {
  content: '';
  position: absolute;
  left: 0;
  right: 0;
  bottom: 0;
  height: 45%;
  background: linear-gradient(180deg, transparent, rgba(0, 0, 0, 0.5));
  pointer-events: none;
}
.poster-rank {
  position: absolute;
  left: 16px;
  bottom: 4px;
  z-index: 1;
  color: #fff;
  font-size: 4.4rem;
  font-weight: 800;
  line-height: 1.1;
  letter-spacing: -0.04em;
  text-shadow: 0 4px 16px rgba(0, 0, 0, 0.45);
}
.poster-nav {
  position: absolute;
  top: 176px;
  z-index: 2;
  width: 46px;
  height: 46px;
  border: 0;
  border-radius: 50%;
  background: rgba(25, 31, 40, 0.55);
  color: #fff;
  font-size: 1.5rem;
  line-height: 1;
  cursor: pointer;
  backdrop-filter: blur(3px);
}
.poster-nav.prev {
  left: -8px;
}
.poster-nav.next {
  right: -8px;
}
.poster-nav:hover:not(:disabled) {
  background: rgba(25, 31, 40, 0.75);
}
.poster-nav:disabled {
  opacity: 0;
  cursor: default;
}
.poster-info {
  display: grid;
  gap: 7px;
  justify-items: center;
  text-align: center;
}
.poster-info h3 {
  margin: 0;
  font-size: 1.12rem;
}
.poster-stats {
  margin: 0;
  font-size: 0.85rem;
  color: var(--sub);
}
.poster-reason {
  color: var(--sub);
  font-size: 0.78rem;
}
.poster-stats .lv {
  font-weight: 700;
}
.poster-stats .lv.quiet {
  color: var(--quiet);
}
.poster-stats .lv.normal {
  color: #b8860b;
}
.poster-stats .lv.crowded {
  color: var(--crowded);
}
.poster-stats .lv.crowded {
  color: var(--crowded);
}
.poster-cta {
  display: inline-flex;
  padding: 11px 26px;
  border-radius: 99px;
  background: #fff;
  border: 1px solid #e5e8eb;
  font-weight: 700;
  font-size: 0.9rem;
}
.poster-cta:hover {
  background: var(--muted);
}
@media (max-width: 640px) {
  .poster-item {
    flex-basis: 78%;
  }
  .poster-frame :deep(img) {
    height: 340px;
  }
  .poster-nav {
    top: 146px;
  }
}
</style>
