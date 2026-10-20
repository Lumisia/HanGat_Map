<script setup>
/* MAP_001 좌측 카드 — 정렬 / 권역 / 업종(4개씩 넘김) / 종류 / 한산한 곳 목록 / 코스 버튼 / 범례.
   폰(≤768px)에서는 같은 마크업이 가운데 모달로 열리고, 목록이 먼저 보이며 조건은 칩 4개(정렬·권역·업종·종류)를 눌러
   그 조건만 펼친다(2026-09-14 결정, 시안 A) - 바텀시트 때는 조건 UI가 시트의 85%를 먹어 목록이 1.3줄(88px)만 보였다(최종점검 #58) */
import { computed, ref, watch, onMounted, onBeforeUnmount, nextTick } from 'vue'
import SearchBox from './SearchBox.vue'
import LayerIcon from './LayerIcon.vue'
import { state, rankedRows, CATEGORIES, REGIONS, LAYERS, FILTER_VISIBLE, toggleLayer, placeKey } from '@/stores/mapStore'
import { at, fmtK } from '@/utils/date'
import { sourceLine } from '@/utils/dataSources'
import { mapBridge } from '@/composables/mapBridge'

const emit = defineEmits(['open-place', 'toggle-course'])
const props = defineProps({ mobileSuppressed: { type: Boolean, default: false } })
const mobileOpen = ref(false)
/** 폰에서 펼친 조건: null | 'sort' | 'reg' | 'layer' | 'cat'. 한 번에 하나만 - 목록이 주인공이라 조건은 필요할 때만 자리를 차지한다 */
const mdrop = ref(null)
const catSel = ref(null)

/* 폰 폭 여부 - 업종 줄을 폰에선 7개 한 줄로 펼치고(‹ › 넘김 없음) 데스크톱에선 4개씩 넘긴다 */
const mq = typeof matchMedia === 'function' ? matchMedia('(max-width:768px)') : null
const isMobile = ref(!!mq?.matches)
const onMq = e => { isMobile.value = e.matches }
onMounted(() => mq?.addEventListener('change', onMq))
onBeforeUnmount(() => mq?.removeEventListener('change', onMq))

watch(() => props.mobileSuppressed, suppressed => {
  if (suppressed) mobileOpen.value = false
})
watch(mobileOpen, open => { if (!open) mdrop.value = null })   // 닫았다 다시 열면 목록부터

/* 조건 칩 글자 = 현재 선택값 - 펼치지 않아도 무슨 기준의 목록인지 읽힌다 */
const layerNames = computed(() => LAYERS.filter(l => state.L[l.k]).map(l => l.t))
const sortLabel = computed(() => state.sort === 'calm' ? '한산한 순' : '혼잡한 순')
const regLabel = computed(() => state.F.reg === '전체' ? '권역' : state.F.reg)
const layerLabel = computed(() => {
  const n = layerNames.value
  return !n.length ? '업종' : n.length <= 2 ? n.join('·') : `${n[0]} 외 ${n.length - 1}`
})
const catLabel = computed(() => state.F.cat || '종류')

/** 같은 칩을 다시 누르면 접힌다. 종류는 펼치면서 바로 선택창을 연다(지원 브라우저) - 아니면 패널의 선택 상자를 한 번 더 누른다 */
function toggleDrop(k) {
  mdrop.value = mdrop.value === k ? null : k
  if (mdrop.value === 'cat') nextTick(() => { try { catSel.value?.showPicker?.() } catch { /* 지원 안 함 - 선택 상자가 보이니 된다 */ } })
}

/* 목록은 '이런 곳만 모은' 게 아니라 '이 순서로 정렬한' 것이라 제목도 정렬 칩과 같은 글자를 쓴다("한산한 순") */
const sectionTitle = computed(() => `${fmtK(at(state.di))} ${sortLabel.value}`)

/* 예보를 못 받은 날만 범례 아래에 알린다 - 그날은 지도 전체가 회색이라 "원래 예보 없는 곳"과 구분해야 한다.
   평소 회색 핀의 뜻은 범례 라벨('예보 없음')과 상세 문장이 말한다 */
const forecastDown = computed(() => !state.loading && state.live && state.forecastDays === 0)

/* 데이터 출처 - 지도에서 보는 데이터의 출처는 지도에 적는다(공공데이터 이용조건·심사 확인 항목). 켜진 레이어에 따라 원천이 늘어난다 */
const sources = computed(() => sourceLine(state.L))

const maxOffset = computed(() => Math.max(0, LAYERS.length - FILTER_VISIBLE))
const shift = computed(() => `translateX(-${state.filterOffset * (100 / FILTER_VISIBLE)}%)`)

/** 한 번에 4칸씩 밀되 마지막은 오른쪽 끝에 맞춰 멈춘다 */
function moveFilter(d) {
  state.filterOffset = Math.min(maxOffset.value, Math.max(0, state.filterOffset + d * FILTER_VISIBLE))
}

function setRegion(r) {
  state.F.reg = r
  mdrop.value = null   // 하나만 고르는 조건은 고르면 접힌다(업종은 여러 개 켜므로 열린 채)
  mapBridge.fitRegion()
}

function setSort(s) {
  state.sort = s
  mdrop.value = null
}

function openPlace(place) {   // 장소 객체(목록 행·검색 결과) - 이름이 아니라 id 로 열려야 동명 장소가 구분된다
  mobileOpen.value = false
  emit('open-place', place)
}

function toggleCourse() {
  mobileOpen.value = false
  emit('toggle-course')
}
</script>

<template>
  <button
    type="button"
    class="filter-fab"
    :aria-expanded="mobileOpen"
    aria-controls="mobile-map-filter-sheet"
    :aria-label="mobileOpen ? '장소 검색 닫기' : '장소 검색 및 필터 열기'"
    @click="mobileOpen = !mobileOpen"
  >
    <svg width="22" height="22" viewBox="0 0 24 24" fill="none" stroke="currentColor"
      stroke-width="2.1" stroke-linecap="round" aria-hidden="true">
      <circle cx="10.5" cy="10.5" r="6.5" />
      <path d="m15.5 15.5 5 5" />
    </svg>
  </button>

  <div id="mobile-map-filter-sheet" class="fl cond" :class="{ 'mobile-open': mobileOpen }">
    <!-- 폰: × 는 검색창 오른쪽 같은 줄(CSS absolute). 데스크톱은 display:none -->
    <div class="mobile-filter-head">
      <button type="button" class="mobile-filter-close" aria-label="장소 검색 닫기"
        @click="mobileOpen = false">×</button>
    </div>

    <SearchBox @pick-spot="openPlace" />

    <!-- 폰 전용 조건 칩 4개 - 누르면 아래 회색 패널에 그 조건만 펼친다. 데스크톱에선 CSS 로 숨긴다(.mdrop display:none) -->
    <div class="mdrop" role="group" aria-label="목록 조건">
      <button type="button" :class="{ open: mdrop === 'sort' }" :aria-expanded="mdrop === 'sort'"
        @click="toggleDrop('sort')"><i class="ic-sort">↕</i><span>{{ sortLabel }}</span></button>
      <button type="button" :class="{ open: mdrop === 'reg', set: state.F.reg !== '전체' }" :aria-expanded="mdrop === 'reg'"
        @click="toggleDrop('reg')"><span>{{ regLabel }}</span></button>
      <button type="button" :class="{ open: mdrop === 'layer', set: layerNames.length > 0 }" :aria-expanded="mdrop === 'layer'"
        @click="toggleDrop('layer')"><i v-if="layerNames.length" class="dot"></i><span>{{ layerLabel }}</span></button>
      <button type="button" :class="{ open: mdrop === 'cat', set: !!state.F.cat }" :aria-expanded="mdrop === 'cat'"
        @click="toggleDrop('cat')"><span>{{ catLabel }}</span></button>
    </div>

    <div id="cond-body" :class="mdrop ? 'open-' + mdrop : null">
      <div class="seg">
        <button :class="{ on: state.sort === 'calm' }" @click="setSort('calm')">한산한 순</button>
        <button :class="{ on: state.sort === 'busy' }" @click="setSort('busy')">혼잡한 순</button>
      </div>

      <div class="chips">
        <!-- button: Tab·Enter 로도 고를 수 있게(전엔 span 이라 키보드로 못 갔다, 최종점검 #34). aria-pressed 로 켜진 칩을 낭독기가 읽는다 -->
        <button v-for="r in REGIONS" :key="r" type="button" class="chip" :class="{ on: r === state.F.reg }"
          :aria-pressed="r === state.F.reg" @click="setRegion(r)">{{ r }}</button>
      </div>

      <div class="ftr-wrap">
        <button class="ftr-nav" aria-label="이전 업종" :disabled="state.filterOffset <= 0"
          @click="moveFilter(-1)">‹</button>
        <div class="ftr-vp">
          <div class="ftr" :style="isMobile ? null : { transform: shift }">
            <button v-for="l in LAYERS" :key="l.k" type="button" :class="[l.k, { on: state.L[l.k] }]"
              :aria-pressed="!!state.L[l.k]" @click="toggleLayer(l.k)">
              <span class="ico"><LayerIcon :name="l.k" /></span>{{ l.t }}
            </button>
          </div>
        </div>
        <button class="ftr-nav" aria-label="다음 업종" :disabled="state.filterOffset >= maxOffset"
          @click="moveFilter(1)">›</button>
      </div>

      <select ref="catSel" class="catsel" :class="{ on: state.F.cat }" v-model="state.F.cat" @change="mdrop = null">
        <option value="">모든 종류의 관광지</option>
        <!-- 곳수를 함께 보여준다 - 110종 중 3분의 2가 5곳 미만이라 고르기 전에 규모를 알아야 한다 -->
        <option v-for="c in CATEGORIES" :key="c.name" :value="c.name">{{ c.name }} ({{ c.n }})</option>
      </select>

      <!-- 곳수(.cnt)는 폰에서만 보인다 - 데스크톱은 목록이 길게 보여 필요 없다 -->
      <div class="sect"><em>✦</em> <span>{{ sectionTitle }}</span><span class="cnt">{{ rankedRows.length }}곳</span></div>

      <div class="rows">
        <div v-if="state.loading" class="empty">장소를 불러오는 중이에요…</div>
        <!-- 빈 이유를 구분한다: 장소 못 받음 / 예보 못 받음 / 선택 날짜가 예보 범위 밖 / 진짜로 조건에 맞는 곳 없음 (2026-09-13) -->
        <div v-else-if="!rankedRows.length" class="empty">
          {{ !state.live ? '장소 데이터를 불러오지 못했어요 · 새로고침해 주세요'
            : forecastDown ? '혼잡 예보를 불러오지 못했어요 · 새로고침해 주세요'
            : (state.forecastUntil >= 0 && state.di > state.forecastUntil) ? `${fmtK(at(state.forecastUntil))} 이후는 아직 혼잡 예보가 없어요`
            : '이 조건에는 혼잡 예보가 있는 곳이 없어요' }}
        </div>
        <!-- 혼잡 상태는 왼쪽 핀 색으로만 표시한다 (오른쪽 뱃지와 의미가 중복되어 제거) -->
        <div v-for="(o, i) in rankedRows" :key="placeKey(o.s)" class="row" :class="o.t"
          @click="openPlace(o.s)">
          <span class="rk">{{ i + 1 }}</span>
          <span class="rpin" :class="o.t"></span>
          <span class="info">
            <span class="rn">{{ o.s.n }}</span>
            <span class="rs">{{ o.s.c }} · {{ o.s.r }}</span>
          </span>
        </div>
      </div>

      <!-- 코스가 있고 패널이 접혀 있으면 '코스 보기' - ×로 접은 패널을 다시 펼치는 유일한 버튼 -->
      <button class="cta" @click="toggleCourse">
        {{ !state.course ? 'AI 코스 만들기' : state.coursePanel ? '코스 지우기' : '코스 보기' }}
      </button>

      <!-- 도트는 핀과 같은 면색(-st), 글자는 가독용 진한 톤 - 주황 글자는 흰 배경에서 못 읽는다 -->
      <div class="cta-leg">
        <span style="color:var(--calm)"><i class="dot tier-bg calm"></i>한산</span>
        <span style="color:var(--mid)"><i class="dot tier-bg mid"></i>보통</span>
        <span style="color:var(--busy)"><i class="dot tier-bg busy"></i>혼잡</span>
        <span style="color:var(--tx3)" title="관광공사 혼잡 예측 대상이 아닌 장소"><i class="dot" style="background:var(--none)"></i>예보 없음</span>
      </div>
      <p v-if="forecastDown" class="cta-note">혼잡 예보를 불러오지 못했어요 · 새로고침해 주세요</p>
      <p class="cta-src">{{ sources }}</p>
    </div>
  </div>

  <!-- 폰 모달 뒤 어두운 배경 - 탭하면 닫힘. 데스크톱은 display:none -->
  <div class="cond-dim" :class="{ on: mobileOpen }" @click="mobileOpen = false"></div>
</template>
