<script setup>
/* MAP_002 검색 — 백엔드 통합 검색 하나로(2026-09-14 단일화). 매칭·정렬·범위는 서버가 정하고 여기선 결과만 보여준다.
   전엔 받아 둔 관광지를 로컬에서 따로 뒤져 서버 결과와 합치고 자체 정렬했다 - 규칙이 두 벌이라 순서가 어긋나고,
   서버 검색이 켜진 업종 칩 안에서만 찾아 기본 화면에선 '국수'가 국수집 대신 관광지 2곳만 냈다(최종점검 #9). 이제 업종 칩과 무관하게 전체 업종 */
import { ref, computed, watch } from 'vue'
import { state, placeKey } from '@/stores/mapStore'

import { crowd, tier } from '@/utils/crowd'
import { poiMarkerClass } from './mapPresentation'
import { won } from '@/utils/geo'
import { mapBridge } from '@/composables/mapBridge'
import MapPlaceService, { hasCoords } from '@/services/map/MapPlaceService'

const emit = defineEmits(['pick-spot'])

const q = ref('')
const open = ref(false)
const input = ref(null)
const EXAMPLES = ['오름', '해변', '전시', '국수']

/* 권역 칩은 검색 범위로 남긴다(결과 행에 읍면이 붙어 어디인지는 보인다). 업종 칩은 범위가 아니다 - 서버에 categories 를 보내지 않는다 */
const REGION_CODE = { 동부: 'EAST', 서부: 'WEST', 남부: 'SOUTH', 북부: 'NORTH' }

/* 입력이 멈추면(300ms) 서버에 묻는다. 상태 4가지 - idle(2글자 미만) / loading / ok / fail.
   실패를 빈 결과로 보이면 "없어요"가 거짓말이 된다(최종점검 #26) */
const apiHits = ref([])
const apiState = ref('idle')
let seq = 0
let timer = null
async function runSearch () {
  const key = q.value.trim()
  const my = ++seq
  apiState.value = 'loading'
  const rows = await MapPlaceService.search(key, { region: REGION_CODE[state.F.reg] ?? null })
  if (my !== seq) return                       // 이전 검색어의 늦은 응답은 버린다
  apiHits.value = rows ?? []
  apiState.value = rows ? 'ok' : 'fail'
}
watch(() => [q.value, state.F.reg], () => {
  clearTimeout(timer)
  if (q.value.trim().length < 2) { seq++; apiHits.value = []; apiState.value = 'idle'; return }
  timer = setTimeout(runSearch, 300)
})

/* 서버 순서 그대로. 관광지는 받아 둔 레이어의 같은 객체로 바꿔 혼잡 색·선택 핀 연동을 그대로 쓴다(없으면 서버 객체) */
const hits = computed(() => apiHits.value.map(p => {
  const s = p.cat === 'TOURIST' ? state.layers.spot.find(x => x.id === p.id) : null
  return s ? { type: 'spot', o: s, c: crowd(s, state.di) } : { type: 'api', o: p }
}))

/* 부제: 업종 · 읍면(주소에서 뽑은 unit). 권역만 적으면 스타벅스 10곳이 전부 "카페 · 북부"로 보여 구분이 안 됐다(최종점검 #10) */
const subOf = o => [o.c, o.unit ?? o.r].filter(Boolean).join(' · ')

/* 검색 결과 점 - 관광지는 혼잡 색 점(.rpin), 그 외는 지도 핀과 같은 업종 원+아이콘(.poi-marker.mk-*).
   전엔 색만 다른 점이라 착한가격(분홍 네모)·식당(보라 점)이 무슨 종류인지 바로 안 읽혔다(2026-09-14 사용자 요청). 코스 패널과 같은 모양 */
const poiCls = h => (h.type === 'spot' ? null : poiMarkerClass(h.o))

const showPanel = computed(() => open.value && q.value.trim().length > 0)

function close() { q.value = ''; open.value = false }

/* 관광지는 레이어의 그 객체를 그대로 넘긴다 - 이름으로 넘기면 동명 관광지 중 첫 번째가 열린다 */
function pickSpot(spot) { close(); emit('pick-spot', spot) }

/* 업소 결과는 좌표로 이동해 상세 패널까지 연다 - 핀 클릭과 같은 문법. 좌표 없는 곳은 이동 없이 상세만(부모가 안내 토스트) -
   전엔 (0,0)으로 날아가 제주 남서쪽 바다가 보였다(최종점검 #27). 업종 칩은 켜지 않는다 - 선택 핀은 레이어가 꺼져 있어도 따로 뜬다 */
function pick(h) {
  if (h.type === 'spot') { pickSpot(h.o); return }
  close()
  if (hasCoords(h.o)) { mapBridge.panTo(h.o.y, h.o.x); mapBridge.zoomTo(7) }
  emit('pick-spot', h.o)
}

function pickFirst() {
  const h = hits.value[0]
  if (!h) { input.value?.focus(); return }
  pick(h)
}

function useExample(t) { q.value = t; open.value = true; input.value?.focus() }

defineExpose({ close })
</script>

<template>
  <div class="srch">
    <!-- v-model은 한글 조합(IME) 중 값을 안 바꿔 '성산'을 치는 동안 '성' 결과가 보인다 - 조합 중에도 읽는다 -->
    <input ref="input" :value="q" placeholder="관광지·맛집 검색" autocomplete="off"
      @input="q = $event.target.value; open = true" @focus="open = true"
      @keydown.esc="close" @keydown.enter="pickFirst">
    <button class="sb-x" :class="{ on: q }" aria-label="지우기" @click="close">×</button>
    <button class="sb-go" aria-label="검색" @click="pickFirst">
      <svg width="17" height="17" viewBox="0 0 24 24" fill="none" stroke="currentColor"
        stroke-width="2.3" stroke-linecap="round"><circle cx="10.4" cy="10.4" r="6.6" />
        <path d="M15.4 15.4L21 21" /></svg>
    </button>
  </div>

  <div class="sb-rs" :class="{ on: showPanel }">
    <template v-if="hits.length">
      <div v-for="h in hits" :key="h.type + placeKey(h.o)" class="sr" @click="pick(h)">
        <span v-if="poiCls(h)" class="poi-marker cpin" :class="poiCls(h)"></span>
        <span v-else class="rpin" :class="h.type === 'spot' ? tier(h.c) : 'none'"></span>
        <span class="info">
          <span class="rn">{{ h.o.n }}</span>
          <span class="rs">{{ subOf(h.o) }}</span>
        </span>
        <span v-if="h.o.good && h.o.p != null" class="bdg"
          style="background:var(--pink-bg);color:var(--pink)">{{ won(h.o.p) }}원</span>
      </div>
    </template>

    <!-- 서버가 답하지 않았을 때 - 0건과 구분한다. 찾는 중엔 이전 결과 대신 짧은 안내만 -->
    <div v-else-if="showPanel && apiState === 'fail'" class="sb-none">
      <b>검색 서버에 연결하지 못했어요</b>
      <p>잠시 뒤 다시 시도해 주세요</p>
      <div class="sb-eg"><button @click="runSearch">다시 시도</button></div>
    </div>
    <div v-else-if="showPanel && apiState === 'loading'" class="sb-none">
      <p>찾는 중…</p>
    </div>
    <div v-else-if="showPanel && apiState === 'idle'" class="sb-none">
      <p>두 글자 이상 입력해 주세요</p>
    </div>

    <div v-else-if="showPanel && apiState === 'ok'" class="sb-none">
      <div class="ic">
        <svg width="26" height="26" viewBox="0 0 24 24" fill="none" stroke="currentColor"
          stroke-width="1.9" stroke-linecap="round"><circle cx="10.4" cy="10.4" r="6.6" />
          <path d="M15.4 15.4L21 21" /></svg>
      </div>
      <b>'{{ q.trim() }}'에 맞는 곳이 없어요</b>
      <p>장소 이름, 종류, 메뉴로 찾을 수 있어요</p>
      <div class="sb-eg">
        <button v-for="t in EXAMPLES" :key="t" @click="useExample(t)">{{ t }}</button>
      </div>
    </div>
  </div>
</template>
