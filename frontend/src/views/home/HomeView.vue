<script setup lang="ts">
// 메인 페이지 (담당: 정동현 · 2026-09-18 메인 시안대로 재구성: 이후경)
// 구성: ① 히어로(소개 + 조건 바 + 실제 숫자, MAIN_003) ② 오늘 한적한 곳 캐러셀(MAIN_001) ③ 추천 코스 3종 → 코스 상세(MAIN_002)
//       ④ 날씨 띠 ⑤ 테마로 둘러보기(/themes 입구) ⑥ 푸터(출처)   (날씨는 2026-09-18 사용자 결정으로 코스 뒤로)
// 정직성 원칙: 혼잡은 '날짜 단위 예보'로만 표현한다 (시간대별 혼잡 표현 금지 - 데이터 없음).
//             히어로의 숫자는 실제 목록·예보로만 세고, 못 세면 그 항목을 뺀다(자리표시 숫자 금지).
import { computed, nextTick, onBeforeUnmount, onMounted, ref } from 'vue'
import { useTravelStore } from '../../app/stores/travel'
import { todayKst } from '../../utils/format.js'
import { tier } from '../../utils/crowd.js'
import type { DailyWeather } from '../../services/WeatherService'
import { WeatherService } from '../../services/WeatherService'
import CalmPlaceService, { type CalmPlaceCard } from '../../services/CalmPlaceService'
import CourseService, { type CourseCard } from '../../services/CourseService'
import { CrowdService } from '../../services/map/CrowdService'
import { loadThemeLayers } from '../../services/themeData.js'
import type { CongestionLevel } from '../../assets/types'
import PlaceImage from '../../components/common/PlaceImage.vue'
import CongestionBadge from '../../components/common/CongestionBadge.vue'
import WeatherStrip from '../../components/home/WeatherStrip.vue'
import ThemeTiles from '../../components/home/ThemeTiles.vue'
import SiteFooter from '../../components/layout/SiteFooter.vue'

const store = useTravelStore()
const weeklyWeather = ref<DailyWeather[]>([])
// true = 기상청 실데이터, false = 백엔드 미가동 시 시연용 샘플 폴백
const weatherLive = ref(false)
/** 구간 부제에 넣는 오늘 요약(첫 날) */
const weatherToday = computed(() => weeklyWeather.value[0] ?? null)

const fmtDate = (iso: string) => {
  const [, m, d] = iso.split('-')
  return `${Number(m)}월 ${Number(d)}일`
}
// 백엔드 calm-places의 기본 기준일(DateTimes.todayKst)과 같은 한국 날짜여야 한다
// - 브라우저 시계가 UTC면 로컬 날짜는 하루 전을 가리킨다
const todayLabel = ref(fmtDate(todayKst()))

// MAIN_002: 코스 추천 3종 - 새벽 배치가 만든 실데이터(백엔드 미가동 시 목업 폴백)
const courseCards = ref<CourseCard[]>([])
const coursesLive = ref(false)
/** "요금 확인 필요"는 비용을 모른다는 뜻이라 카드에 찍지 않는다(빈 문구). 실측 비용이 있을 때만 */
const budgetOf = (c: CourseCard) => (c.budgetLabel && !c.budgetLabel.includes('확인 필요') ? c.budgetLabel : null)

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
  const gap = 20
  el.scrollBy({ left: dir * ((card?.clientWidth ?? 280) + gap), behavior: 'smooth' })
}

/* 히어로 숫자 + 테마 타일 재료. 테마 페이지와 같은 레이어(세션에 한 번만 받음) */
const layers = ref<Record<string, any[]> | null>(null)
const calmTodayCount = ref<number | null>(null)
const stats = computed(() => {
  const out: { label: string; value: number }[] = []
  const L = layers.value
  if (L?.spot) out.push({ label: '관광지', value: L.spot.filter((p: any) => !p.closed).length })
  if (L?.food) out.push({ label: '착한가격업소', value: L.food.filter((p: any) => !p.closed).length })
  if (calmTodayCount.value != null) out.push({ label: '오늘 한산한 곳', value: calmTodayCount.value })
  return out
})
/** 오늘 집중률이 '한산'(40 미만)인 예보 대상 관광지 수. 예보가 없으면 null → 숫자 줄에서 뺀다 */
async function countCalmToday () {
  const f = await CrowdService.getForecast()
  if (!f.live || !f.from) return null
  const idx = Math.round((Date.parse(todayKst()) - Date.parse(f.from)) / 86400000)
  if (idx < 0 || idx >= f.days) return null
  return Object.values(f.values).filter(row => tier(row[idx]) === 'calm').length
}

onMounted(async () => {
  // 자정을 넘겨 다시 들어온 탭이 지나간 일정·날짜를 그대로 보여주지 않게 다시 맞춘다
  store.refreshDefaultDates()
  todayLabel.value = fmtDate(todayKst())
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
  // 숫자·테마는 본문이 뜬 뒤에 - 하나가 실패해도 나머지는 그대로
  const [themeRes, calmCount] = await Promise.all([
    loadThemeLayers().catch(() => null),
    countCalmToday().catch(() => null)
  ])
  if (themeRes?.layers) layers.value = themeRes.layers
  calmTodayCount.value = calmCount
})
onBeforeUnmount(() => window.removeEventListener('resize', updateArrows))
</script>
<template>
  <div class="home">
    <!-- ① MAIN_003: 히어로 - 소개 + 조건 바(AI 코스 입구) + 실제 숫자 -->
    <section class="hero-full">
      <img class="hero-bg" src="/images/hero-jeju.jpg" alt="광치기해변에서 바라본 성산일출봉">
      <div class="hero-scrim" />
      <div class="hero-inner">
        <span class="pill">제주 분산 여행 가이드</span>
        <h1>사람을 피해,<br><em>제주를 더 깊이.</em></h1>
        <p class="hero-desc">혼잡도와 날씨, 이동 시간을 함께 읽어 나만의 한적한 제주 코스를 만들어요.</p>
        <!-- 조건 바 - 소개 문장 바로 아래, 다른 구간과 같은 폭(2026-09-18 사용자 결정). 누르면 AI 코스 화면(조건 편집)으로. 값은 travel 스토어 그대로 -->
        <div class="cond">
          <RouterLink class="cond-f" to="/ai-course"><small>여행 일정</small><b>{{ fmtDate(store.condition.startDate) }} – {{ fmtDate(store.condition.endDate) }}</b></RouterLink>
          <RouterLink class="cond-f" to="/ai-course"><small>함께 가는 사람</small><b>{{ store.condition.people }}명 · {{ store.condition.preference }}</b></RouterLink>
          <RouterLink class="cond-f" to="/ai-course"><small>여행 취향</small><b>{{ store.condition.styles.join(' · ') }}</b></RouterLink>
          <RouterLink class="cond-go" to="/ai-course">
            <svg width="18" height="18" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2.2" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true"><path d="M4 12h13" /><path d="M12 6l6 6-6 6" /></svg>
            AI 코스 추천받기
          </RouterLink>
        </div>
        <p v-if="stats.length" class="hero-stats">
          <template v-for="(s, i) in stats" :key="s.label">
            <span v-if="i" class="dot" aria-hidden="true" />
            <span>{{ s.label }} {{ s.value.toLocaleString() }}곳</span>
          </template>
        </p>
      </div>
      <span class="hero-credit">ⓒ한국관광공사</span>
    </section>

    <!-- ② MAIN_001: 오늘 한적한 곳 - 히어로 바로 아래 첫 구간이라 선 없이 여백만 -->
    <section class="hm-sec hm-first">
      <div class="hm-head">
        <div>
          <h2>오늘 한적한 곳부터</h2>
          <p>{{ todayLabel }} 예보 기준 집중률 낮은 순 · {{ calmLive ? '한국관광공사 집중률 예보' : '시연용 데이터 · 백엔드 연결 대기' }}</p>
        </div>
        <RouterLink class="hm-more" to="/map">전체 지도 보기 →</RouterLink>
      </div>
      <div class="poster-carousel">
        <button class="poster-nav prev" type="button" aria-label="이전 추천 장소 보기" :disabled="!canPrev" @click="slideBy(-1)">‹</button>
        <div ref="track" class="poster-track" @scroll.passive="updateArrows">
          <div v-for="(p, i) in calmPlaces" :key="p.key" class="poster-item">
            <!-- 목업 폴백 카드는 열 상세가 없어 링크를 걸지 않는다 -->
            <component :is="p.to ? 'RouterLink' : 'div'" class="poster-frame" v-bind="p.to ? { to: p.to } : {}">
              <PlaceImage :src="p.imageUrl ?? '/images/placeholder.svg'" :alt="`${p.name} 사진`" />
              <CongestionBadge v-if="p.level" :level="(p.level as CongestionLevel)" class="poster-badge" />
              <span class="poster-rank">{{ i + 1 }}</span>
            </component>
            <div class="poster-info">
              <h3>{{ p.name }}</h3>
              <p class="poster-stats">{{ p.region }} · {{ p.reason }}</p>
            </div>
          </div>
        </div>
        <button class="poster-nav next" type="button" aria-label="다음 추천 장소 보기" :disabled="!canNext" @click="slideBy(1)">›</button>
      </div>
    </section>

    <!-- ③ MAIN_002: 코스 추천 3종 (클릭 → 코스 상세) -->
    <section class="hm-sec hm-line">
      <div class="hm-head">
        <div>
          <h2>한적한 곳으로 이어 만든 코스</h2>
          <p>{{ coursesLive ? '새벽마다 그날 예보가 한산한 권역으로 다시 짭니다' : '시연용 데이터 · 백엔드 연결 대기' }}</p>
        </div>
      </div>
      <div class="hm-courses">
        <!-- 목업 폴백(백엔드 미가동·배치 전)은 열 상세가 없어 링크를 걸지 않는다 -->
        <component
          :is="coursesLive ? 'RouterLink' : 'article'"
          v-for="course in courseCards"
          :key="course.id"
          class="hm-course"
          v-bind="coursesLive ? { to: `/courses/${course.id}` } : {}"
        >
          <div class="hm-course-ph"><PlaceImage :src="course.imageUrl ?? '/images/placeholder.svg'" :alt="`${course.title} 대표 사진`" /></div>
          <div class="hm-course-body">
            <span class="hm-kicker">{{ course.conditionLabel }}</span>
            <h3>{{ course.title }}</h3>
            <p v-if="course.stops" class="hm-stops">{{ course.stops }}</p>
            <p class="hm-sub">{{ course.highlight }}<template v-if="budgetOf(course)"> · {{ budgetOf(course) }}</template></p>
            <div class="hm-course-foot">
              <CongestionBadge v-if="course.level" :level="course.level" />
              <span v-else />
              <span v-if="coursesLive" class="hm-more">코스 상세 →</span>
            </div>
          </div>
        </component>
      </div>
    </section>

    <!-- ④ 날씨 띠 - 코스와 테마 사이(2026-09-18 사용자 결정: 히어로 아래엔 사진 구간이 바로 오게). 제목은 다른 구간과 같은 모양 -->
    <section class="hm-sec hm-line hm-wx">
      <div class="hm-head">
        <div>
          <h2>제주 일주일 날씨</h2>
          <p><template v-if="weatherToday">오늘 {{ weatherToday.temperature === null ? '-' : `${weatherToday.temperature}°` }} {{ weatherToday.sky ?? '' }} · </template>{{ weatherLive ? '기상청 단기·중기예보 · 날짜 단위' : '시연용 데이터 · 백엔드 연결 대기' }}</p>
        </div>
      </div>
      <WeatherStrip :days="weeklyWeather" />
    </section>

    <!-- ⑤ 테마로 둘러보기 -->
    <section class="hm-sec hm-line hm-last">
      <ThemeTiles :layers="layers ?? undefined" />
    </section>

    <!-- ⑥ 푸터 - 출처 고지(MAIN_003) -->
    <SiteFooter />
  </div>
</template>
<style src="../../assets/home.css"></style>
<style scoped>
.home{display:flex;flex-direction:column}
.hm-sec{max-width:1240px;width:100%;margin:0 auto;padding:60px 24px 0;box-sizing:border-box;display:flex;flex-direction:column;gap:22px}
.hm-first{padding-top:48px}
/* 구간 경계 - 본문 폭의 얇은 선. 선 위 72px·선 아래 제목까지 40px 로 비대칭을 줘야 "다음 주제의 시작"으로 읽힌다(2026-09-18, 구석구석 방식) */
.hm-line{padding-top:72px}
.hm-line::before{content:'';display:block;height:1px;background:var(--border);margin-bottom:18px}   /* 18 + 구간 gap 22 = 40 */
.hm-last{padding-bottom:72px}
.hm-head{display:flex;align-items:flex-end;justify-content:space-between;gap:12px}
.hm-head h2{margin:0;font-size:30px;font-weight:800;letter-spacing:-.02em}
.hm-head p{margin:6px 0 0;font-size:14px;color:var(--sub)}
.hm-more{font-size:14px;font-weight:700;color:var(--primary-dark);white-space:nowrap}

/* 히어로 */
.hero-full{position:relative;min-height:600px;display:flex;align-items:center;overflow:hidden;background:var(--deep)}
.hero-bg{position:absolute;inset:0;width:100%;height:100%;object-fit:cover;object-position:center 55%}
.hero-scrim{position:absolute;inset:0;background:rgba(15,25,35,.42)}
.hero-inner{position:relative;z-index:1;width:100%;max-width:1240px;margin:auto;padding:64px 24px 60px;box-sizing:border-box;display:flex;flex-direction:column;gap:18px;color:#fff}
.hero-inner .pill{align-self:flex-start;background:rgba(255,255,255,.18);color:#fff;font-size:12.5px;font-weight:700;padding:6px 12px;border-radius:99px}
.hero-inner h1{margin:0;font-size:48px;line-height:1.18;font-weight:800;letter-spacing:-.03em;color:#fff}
.hero-inner h1 em{font-style:normal;font-family:'Gowun Dodum',sans-serif;font-weight:400;color:#9fe3d2}
.hero-desc{margin:0;font-size:16px;line-height:1.7;color:rgba(255,255,255,.92)}   /* PC 에선 한 줄(2026-09-18 사용자 요청), 폰은 폭대로 접힘 */
.cond{margin-top:8px;display:flex;align-items:stretch;background:#fff;border-radius:16px;padding:8px;color:#17262b}   /* 사진 위라 흰색 고정. 폭은 다른 구간(1240 상자)과 같게 - 좁히면 아래 날씨 띠와 칸이 안 맞는다 */
.cond-f{flex:1 1 0;display:flex;flex-direction:column;gap:4px;padding:10px 18px;border-right:1px solid #e3eae7;color:#17262b;min-width:0}
.cond-f:last-of-type{border-right:0}
.cond-f small{font-size:12px;font-weight:600;color:#5f736f}
.cond-f b{font-size:15px;font-weight:700;white-space:nowrap;overflow:hidden;text-overflow:ellipsis}
.cond-f:hover{background:#f3f7f5;border-radius:10px}
.cond-go{flex:0 0 auto;display:flex;align-items:center;gap:8px;padding:0 22px;border-radius:12px;background:var(--primary);color:#fff;font-size:15px;font-weight:800;white-space:nowrap}
.cond-go:hover{background:var(--primary-dark);color:#fff}
.hero-stats{margin:0;display:flex;align-items:center;flex-wrap:wrap;gap:8px 14px;font-size:13.5px;font-weight:500;color:rgba(255,255,255,.9)}
.hero-stats .dot{width:3px;height:3px;border-radius:50%;background:rgba(255,255,255,.6)}
.hero-credit{position:absolute;right:20px;bottom:14px;z-index:1;color:rgba(255,255,255,.82);font-size:12px}

/* 오늘 한적한 곳 - 포스터 캐러셀 */
.poster-carousel{position:relative}
.poster-track{display:flex;gap:20px;overflow-x:auto;scroll-snap-type:x mandatory;scrollbar-width:none;padding:4px 2px}
.poster-track::-webkit-scrollbar{display:none}
.poster-item{flex:0 0 280px;scroll-snap-align:start;display:flex;flex-direction:column;gap:10px}
.poster-frame{position:relative;display:block;height:340px;border-radius:16px;overflow:hidden;background:var(--muted)}
.poster-frame :deep(img){display:block;width:100%;height:100%;object-fit:cover;transition:transform .25s ease}
.poster-frame:hover :deep(img){transform:scale(1.03)}
.poster-frame::after{content:'';position:absolute;left:0;right:0;bottom:0;height:40%;background:linear-gradient(180deg,transparent,rgba(0,0,0,.45));pointer-events:none}
.poster-badge{position:absolute;left:14px;top:14px;z-index:1;font-size:12.5px;font-weight:800;padding:5px 11px}
.poster-rank{position:absolute;left:16px;bottom:6px;z-index:1;color:#fff;font-size:44px;font-weight:800;line-height:1;letter-spacing:-.04em;text-shadow:0 4px 16px rgba(0,0,0,.45)}
.poster-nav{position:absolute;top:150px;z-index:2;width:40px;height:40px;border:0;border-radius:50%;background:rgba(25,31,40,.55);color:#fff;font-size:22px;line-height:1;cursor:pointer;backdrop-filter:blur(3px)}
.poster-nav.prev{left:-8px}.poster-nav.next{right:-8px}
.poster-nav:hover:not(:disabled){background:rgba(25,31,40,.75)}
.poster-nav:disabled{opacity:0;cursor:default}
.poster-info{display:flex;flex-direction:column;gap:3px}
.poster-info h3{margin:0;font-size:16px;font-weight:700}
.poster-stats{margin:0;font-size:13px;color:var(--sub)}

/* 코스 카드 */
.hm-courses{display:grid;grid-template-columns:repeat(3,minmax(0,1fr));gap:20px}
.hm-course{display:flex;flex-direction:column;background:var(--surface);border:1px solid var(--border);border-radius:18px;overflow:hidden;color:inherit;transition:transform .15s ease}
.hm-course:hover{transform:translateY(-2px)}
.hm-course-ph{height:170px;background:var(--muted)}
.hm-course-ph :deep(img){display:block;width:100%;height:100%;object-fit:cover}
.hm-course-body{display:flex;flex-direction:column;gap:8px;padding:18px 20px 20px;flex:1}
.hm-kicker{font-size:12.5px;font-weight:700;color:var(--primary-dark)}
.hm-course h3{margin:0;font-size:19px;font-weight:800;letter-spacing:-.02em}
.hm-stops{margin:0;font-size:14px;line-height:1.6;color:var(--text)}
.hm-sub{margin:0;font-size:13px;color:var(--sub)}
.hm-course-foot{display:flex;align-items:center;justify-content:space-between;margin-top:auto;padding-top:8px}

@media (max-width:767px){
  .hm-sec{padding:30px 16px 0;gap:14px}
  .hm-first{padding-top:26px}
  .hm-line{padding-top:40px}
  .hm-line::before{margin-bottom:10px}   /* 10 + gap 14 = 24 */
  .hm-last{padding-bottom:40px}
  .hm-head h2{font-size:22px}.hm-head p{font-size:12.5px}.hm-more{font-size:13px}
  .hero-full{min-height:520px;align-items:flex-start}
  .hero-inner{padding:34px 16px 44px;gap:14px}
  .hero-inner h1{font-size:32px}
  .hero-desc{font-size:14px}
  .cond{flex-direction:column;padding:6px}
  .cond-f{border-right:0;border-bottom:1px solid #e3eae7;padding:11px 14px;flex-direction:row;justify-content:space-between;align-items:center}
  .cond-f:last-of-type{border-bottom:1px solid #e3eae7}
  .cond-f small{font-size:12.5px}.cond-f b{font-size:14.5px}
  .cond-go{margin-top:6px;height:46px;justify-content:center}
  .hero-stats{font-size:12.5px;gap:6px 10px}
  .hero-credit{right:12px;bottom:10px;font-size:11px}
  .poster-track{gap:12px}
  .poster-item{flex-basis:176px}
  .poster-frame{height:220px;border-radius:14px}
  .poster-badge{left:10px;top:10px;font-size:11.5px;padding:4px 9px}
  .poster-rank{font-size:34px;left:12px}
  .poster-nav{display:none}
  .poster-info h3{font-size:15px}.poster-stats{font-size:12px}
  .hm-courses{grid-template-columns:1fr;gap:14px}
  .hm-course{border-radius:16px}
  .hm-course-ph{height:130px}
  .hm-course-body{padding:14px 16px 16px;gap:6px}
  .hm-course h3{font-size:17px}.hm-stops{font-size:13.5px}
}
</style>
