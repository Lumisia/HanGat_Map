<script setup>
/* MAP_002 검색 — 로컬 즉시 필터(관광지·착한가격) + 백엔드 통합 검색(이름·메뉴, 전 카테고리)을 합쳐 보여준다 */
import { ref, computed, watch } from 'vue'
import { state } from '@/stores/mapStore'

import { crowd, tier } from '@/utils/crowd'
import { won } from '@/utils/geo'
import { mapBridge } from '@/composables/mapBridge'
import MapPlaceService from '@/services/map/MapPlaceService'

const emit = defineEmits(['pick-spot'])

const q = ref('')
const open = ref(false)
const input = ref(null)
const EXAMPLES = ['오름', '해변', '전시', '국수']

/* 화면 필터(권역·업종 칩)가 곧 검색 범위다 - 켜진 것 안에서만 찾는다 */
const REGION_CODE = { 동부: 'EAST', 서부: 'WEST', 남부: 'SOUTH', 북부: 'NORTH' }
const LAYER_CAT = [['spot', 'TOURIST'], ['food', 'FOOD'], ['dine', 'FOOD'],
  ['cafe', 'CAFE'], ['cvs', 'CONVENIENCE'], ['stay', 'LODGING'], ['mart', 'MART']]
const scopeCats = () => [...new Set(LAYER_CAT.filter(([k]) => state.L[k]).map(([, c]) => c))]

/* 백엔드 통합 검색 - 입력이 멈추면(300ms) 상위 20건을 받아 로컬 결과를 보강한다.
   일반 식당·카페와 메뉴 검색은 이 경로로만 잡힌다 (로컬엔 그 데이터가 없다).
   필터가 바뀌어도 다시 찾는다 - 검색 범위가 달라지니까 */
const apiHits = ref([])
let seq = 0
let timer = null
watch(() => [q.value, state.F.reg, ...LAYER_CAT.map(([k]) => state.L[k])], () => {
  clearTimeout(timer)
  const key = q.value.trim()
  if (key.length < 2) { apiHits.value = []; return }
  timer = setTimeout(async () => {
    const my = ++seq
    // 칩이 전부 꺼져 있으면 범위 없이 찾는다 - 빈 지도에서의 검색까지 막으면 길이 없다
    const cats = scopeCats()
    const rows = await MapPlaceService.search(key, {
      region: REGION_CODE[state.F.reg] ?? null,
      categories: cats.length ? cats : undefined
    })
    if (my === seq) apiHits.value = rows   // 이전 검색어의 늦은 응답은 버린다
  }, 300)
})

const hits = computed(() => {
  const k = q.value.trim()
  if (!k) return []
  const inReg = p => state.F.reg === '전체' || p.r === state.F.reg
  const local = [
    ...(state.L.spot ? state.layers.spot
      .filter(s => inReg(s) && (s.n.includes(k) || s.c.includes(k) || s.r.includes(k)))
      .map(s => ({ type: 'spot', o: s, c: crowd(s, state.di) })) : []),
    ...(state.L.food ? state.layers.food
      .filter(f => inReg(f) && (f.n.includes(k) || (f.c ?? '').includes(k) || f.r.includes(k)))
      .map(f => ({ type: 'food', o: f })) : []),
  ]
  const seen = new Set(local.map(h => h.o.id).filter(v => v != null))
  const anyChip = LAYER_CAT.some(([lk]) => state.L[lk])
  const extras = []
  for (const p of apiHits.value) {
    if (p.id != null && seen.has(p.id)) continue
    // 착한가격·일반식당은 백엔드에선 같은 FOOD라 칩 상태로 여기서 가른다
    if (anyChip && p.cat === 'FOOD' && (p.good ? !state.L.food : !state.L.dine)) continue
    // 관광지는 적재 레이어의 같은 객체로 바꿔 혼잡 색·상세 연동을 그대로 쓴다
    const s = p.cat === 'TOURIST' ? state.layers.spot.find(x => x.id === p.id) : null
    extras.push(s ? { type: 'spot', o: s, c: crowd(s, state.di) } : { type: 'api', o: p })
  }
  // 연관도: 이름 접두 일치 > 이름 포함(앞쪽 우선) > 종류·권역·메뉴 매칭.
  // 같은 급이면 관광지 먼저(혼잡 예보가 있는 제품의 중심), 그다음 짧은 이름 -
  // "성산"에 성산일출봉이 성산해촌·성산세화해안도로보다 위로 온다.
  // 길이 비교는 "[유네스코...]" 같은 주석을 뗀 본이름으로 한다
  const baseLen = o => o.n.split('[')[0].trim().length
  const rank = h => {
    const i = h.o.n.indexOf(k)
    const spotFirst = h.type === 'spot' ? 0 : 1
    return i < 0 ? [2, spotFirst, 0, baseLen(h.o)] : [i === 0 ? 0 : 1, spotFirst, i, baseLen(h.o)]
  }
  return [...local, ...extras].sort((a, b) => {
    const ra = rank(a), rb = rank(b)
    return (ra[0] - rb[0]) || (ra[1] - rb[1]) || (ra[2] - rb[2]) || (ra[3] - rb[3])
  }).slice(0, 10)
})

/* 검색 결과 핀 색 - 관광지는 혼잡색, 착한가격은 분홍, 나머지는 지도 마커와 같은 업종색 */
const API_PIN = { FOOD: 'dine', CAFE: 'cafe', CONVENIENCE: 'cvs', LODGING: 'stay', MART: 'mart' }
function pinCls(h) {
  if (h.type === 'spot') return tier(h.c)
  if (h.type === 'food' || h.o.good) return 'food'
  return API_PIN[h.o.cat] ?? 'none'
}

const showPanel = computed(() => open.value && q.value.trim().length > 0)

function close() { q.value = ''; open.value = false }

function pickSpot(name) { close(); emit('pick-spot', name) }

/* 착한가격·백엔드 결과는 좌표로 이동해 상세 패널까지 연다 - 핀 클릭과 같은 문법 */
function pick(h) {
  if (h.type === 'spot') { pickSpot(h.o.n); return }
  close()
  mapBridge.panTo(h.o.y, h.o.x)
  mapBridge.zoomTo(7)
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
      <div v-for="h in hits" :key="h.type + h.o.n" class="sr" @click="pick(h)">
        <span class="rpin" :class="pinCls(h)"></span>
        <span class="info">
          <span class="rn">{{ h.o.n }}</span>
          <!-- 라이브 착한가격엔 메뉴·가격 필드가 없다 - 업종으로 통일, 가격은 있을 때만 -->
          <span class="rs">{{ h.o.c }} · {{ h.o.r }}</span>
        </span>
        <span v-if="h.type === 'food' && h.o.p != null" class="bdg"
          style="background:var(--pink-bg);color:var(--pink)">{{ won(h.o.p) }}원</span>
      </div>
    </template>

    <div v-else-if="showPanel" class="sb-none">
      <div class="ic">
        <svg width="26" height="26" viewBox="0 0 24 24" fill="none" stroke="currentColor"
          stroke-width="1.9" stroke-linecap="round"><circle cx="10.4" cy="10.4" r="6.6" />
          <path d="M15.4 15.4L21 21" /></svg>
      </div>
      <b>'{{ q.trim() }}'에 맞는 곳이 없어요</b>
      <p>장소 이름, 종류, 메뉴로 찾을 수 있어요<br>켜진 업종 칩과 권역 안에서만 찾아요</p>
      <div class="sb-eg">
        <button v-for="t in EXAMPLES" :key="t" @click="useExample(t)">{{ t }}</button>
      </div>
    </div>
  </div>
</template>
