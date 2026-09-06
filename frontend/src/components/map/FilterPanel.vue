<script setup>
/* MAP_001 좌측 카드 — 정렬 / 권역 / 업종(4개씩 넘김) / 종류 / 한산한 곳 목록 / 코스 버튼 / 범례 */
import { computed, ref, watch } from 'vue'
import SearchBox from './SearchBox.vue'
import LayerIcon from './LayerIcon.vue'
import { state, rankedRows, CATEGORIES, REGIONS, LAYERS, FILTER_VISIBLE, toggleLayer } from '@/stores/mapStore'
import { at, fmtK } from '@/utils/date'
import { mapBridge } from '@/composables/mapBridge'

const emit = defineEmits(['open-place', 'toggle-course'])
const props = defineProps({ mobileSuppressed: { type: Boolean, default: false } })
const mobileOpen = ref(false)

watch(() => props.mobileSuppressed, suppressed => {
  if (suppressed) mobileOpen.value = false
})

const sectionTitle = computed(() =>
  `${fmtK(at(state.di))} ${state.sort === 'calm' ? '한산한' : '혼잡한'} 곳`)

const maxOffset = computed(() => Math.max(0, LAYERS.length - FILTER_VISIBLE))
const shift = computed(() => `translateX(-${state.filterOffset * (100 / FILTER_VISIBLE)}%)`)

/** 한 번에 4칸씩 밀되 마지막은 오른쪽 끝에 맞춰 멈춘다 */
function moveFilter(d) {
  state.filterOffset = Math.min(maxOffset.value, Math.max(0, state.filterOffset + d * FILTER_VISIBLE))
}

function setRegion(r) {
  state.F.reg = r
  mapBridge.fitRegion()
}

function openPlace(name) {
  mobileOpen.value = false
  emit('open-place', name)
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
    <div class="mobile-filter-head">
      <div>
        <b>장소 찾기</b>
        <span>한산한 제주를 조건별로 찾아보세요</span>
      </div>
      <button type="button" class="mobile-filter-close" aria-label="장소 검색 닫기"
        @click="mobileOpen = false">×</button>
    </div>

    <SearchBox @pick-spot="openPlace" />

    <div id="cond-body">
      <div class="seg">
        <button :class="{ on: state.sort === 'calm' }" @click="state.sort = 'calm'">한산한 순</button>
        <button :class="{ on: state.sort === 'busy' }" @click="state.sort = 'busy'">혼잡한 순</button>
      </div>

      <div class="chips">
        <span v-for="r in REGIONS" :key="r" class="chip" :class="{ on: r === state.F.reg }"
          @click="setRegion(r)">{{ r }}</span>
      </div>

      <div class="ftr-wrap">
        <button class="ftr-nav" aria-label="이전 업종" :disabled="state.filterOffset <= 0"
          @click="moveFilter(-1)">‹</button>
        <div class="ftr-vp">
          <div class="ftr" :style="{ transform: shift }">
            <button v-for="l in LAYERS" :key="l.k" :class="[l.k, { on: state.L[l.k] }]"
              @click="toggleLayer(l.k)">
              <span class="ico"><LayerIcon :name="l.k" /></span>{{ l.t }}
            </button>
          </div>
        </div>
        <button class="ftr-nav" aria-label="다음 업종" :disabled="state.filterOffset >= maxOffset"
          @click="moveFilter(1)">›</button>
      </div>

      <select class="catsel" :class="{ on: state.F.cat }" v-model="state.F.cat">
        <option value="">모든 종류의 관광지</option>
        <!-- 곳수를 함께 보여준다 - 110종 중 3분의 2가 5곳 미만이라 고르기 전에 규모를 알아야 한다 -->
        <option v-for="c in CATEGORIES" :key="c.name" :value="c.name">{{ c.name }} ({{ c.n }})</option>
      </select>

      <div class="sect"><em>✦</em> <span>{{ sectionTitle }}</span></div>

      <div class="rows">
        <div v-if="state.loading" class="empty">장소를 불러오는 중이에요…</div>
        <div v-else-if="!rankedRows.length" class="empty">
          {{ state.live ? '이 조건에는 혼잡 예보가 있는 곳이 없어요' : '조건에 맞는 곳이 없어요' }}
        </div>
        <!-- 혼잡 상태는 왼쪽 핀 색으로만 표시한다 (오른쪽 뱃지와 의미가 중복되어 제거) -->
        <div v-for="(o, i) in rankedRows" :key="o.s.n" class="row" :class="o.t"
          @click="openPlace(o.s.n)">
          <span class="rk">{{ i + 1 }}</span>
          <span class="rpin" :class="o.t"></span>
          <span class="info">
            <span class="rn">{{ o.s.n }}</span>
            <span class="rs">{{ o.s.c }} · {{ o.s.r }}</span>
          </span>
        </div>
      </div>

      <button class="cta" @click="toggleCourse">
        {{ state.course ? '코스 지우기' : 'AI 코스 만들기' }}
      </button>

      <!-- 도트는 핀과 같은 면색(-st), 글자는 가독용 진한 톤 - 주황 글자는 흰 배경에서 못 읽는다 -->
      <div class="cta-leg">
        <span style="color:var(--calm)"><i class="dot" style="background:var(--calm-st)"></i>한산</span>
        <span style="color:var(--mid)"><i class="dot" style="background:var(--mid-st)"></i>보통</span>
        <span style="color:var(--busy)"><i class="dot" style="background:var(--busy-st)"></i>혼잡</span>
        <span style="color:var(--tx3)"><i class="dot" style="background:var(--tx3)"></i>정보 없음</span>
      </div>
    </div>
  </div>
</template>
