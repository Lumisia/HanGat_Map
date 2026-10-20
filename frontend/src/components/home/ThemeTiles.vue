<script setup>
/**
 * 메인 '테마로 둘러보기' - 테마 페이지(/themes)의 입구. 관광공사 세부분류 타일 6개를 사진과 곳수로.
 * 타일 정의·곳수는 테마 페이지와 같은 buildThemes 로 만든다(같은 레이어를 부모가 넘겨준다 - 두 번 받지 않게).
 * 사진은 목록 API 에 없어 타일의 첫 장소 상세를 화면 근처에서만 받는다(테마 카드와 같은 방식). 없으면 묶음 색 + 아이콘.
 */
import { computed, onBeforeUnmount, onMounted, reactive, ref, watch } from 'vue'
import MapPlaceService from '../../services/map/MapPlaceService'
import { buildThemes } from '../../config/themes.js'

const props = defineProps({
  /** loadThemeLayers() 가 준 { spot, dine, food, stay }. 아직 없으면 null */
  layers: { type: Object, default: null }
})

/* 관광공사 분류 코드 - 오름·산, 해변·해수욕장, 해안절경, 숲, 계곡, 박물관. 없는 코드는 건너뛰고 6개가 안 되면 자연 묶음 앞에서 채운다 */
const PICK = ['NA010100', 'NA020900', 'NA020800', 'NA010200', 'NA010400', 'VE070100']

const tiles = computed(() => {
  if (!props.layers) return []
  const groups = buildThemes(props.layers)
  const all = groups.flatMap(g => g.tiles.map(t => ({ ...t, tint: g.tint, emoji: g.emoji })))
  const picked = PICK.map(k => all.find(t => t.key === k)).filter(Boolean)
  for (const t of all) { if (picked.length >= 6) break; if (!picked.includes(t)) picked.push(t) }
  return picked.slice(0, 6)
})

const root = ref(null)
const photos = reactive({})      // key → { url, thumb }
const failed = reactive({})      // key → true (축소본이 깨져 원본으로 바꿈)
let seen = false
let io = null

async function loadPhotos () {
  for (const t of tiles.value) {
    if (photos[t.key] || !t.sample?.id) continue
    const d = await MapPlaceService.getDetail(t.sample.id).catch(() => null)
    const first = d?.images?.[0]
    if (first?.url) photos[t.key] = { url: first.url, thumb: first.thumb ?? first.url }
    else photos[t.key] = null
  }
}
function srcOf (t) {
  const p = photos[t.key]
  if (!p) return null
  return failed[t.key] ? p.url : p.thumb
}
function onError (t) { if (!failed[t.key]) failed[t.key] = true }

onMounted(() => {
  if (!('IntersectionObserver' in window)) { seen = true; loadPhotos(); return }
  io = new IntersectionObserver(entries => {
    if (entries.some(e => e.isIntersecting)) { seen = true; io?.disconnect(); io = null; loadPhotos() }
  }, { rootMargin: '300px 0px' })
  if (root.value) io.observe(root.value)
})
onBeforeUnmount(() => io?.disconnect())
watch(tiles, () => { if (seen) loadPhotos() })
</script>

<template>
  <section ref="root" class="tt">
    <div class="tt-head">
      <div>
        <h2>테마로 둘러보기</h2>
        <p>관광공사가 나눈 종류 그대로, 오름부터 야영장까지</p>
      </div>
      <RouterLink class="tt-more" to="/themes">테마 전체 →</RouterLink>
    </div>
    <div v-if="tiles.length" class="tt-grid">
      <RouterLink v-for="t in tiles" :key="t.key" class="tt-tile" :to="`/themes/${t.key}`">
        <div class="tt-ph" :style="srcOf(t) ? null : { background: t.tint }">
          <img v-if="srcOf(t)" :src="srcOf(t)" :alt="`${t.title} 대표 사진`" loading="lazy" decoding="async" @error="onError(t)">
          <span v-else class="tt-emoji" aria-hidden="true">{{ t.emoji }}</span>
        </div>
        <b>{{ t.title }}</b>
        <small>{{ t.count.toLocaleString() }}곳</small>
      </RouterLink>
    </div>
    <p v-else class="tt-empty">테마를 불러오는 중이에요.</p>
  </section>
</template>

<style scoped>
.tt{display:flex;flex-direction:column;gap:22px}
.tt-head{display:flex;align-items:flex-end;justify-content:space-between;gap:12px}
.tt-head h2{margin:0;font-size:30px;font-weight:800;letter-spacing:-.02em}   /* 메인 다른 구간 제목과 같은 크기 */
.tt-head p{margin:6px 0 0;font-size:14px;color:var(--sub)}
.tt-more{font-size:14px;font-weight:700;color:var(--primary-dark);white-space:nowrap}
.tt-grid{display:grid;grid-template-columns:repeat(6,minmax(0,1fr));gap:16px}
.tt-tile{display:flex;flex-direction:column;gap:4px;color:inherit}
.tt-ph{height:150px;border-radius:14px;overflow:hidden;background:var(--muted);display:flex;align-items:center;justify-content:center;margin-bottom:6px}
.tt-ph img{width:100%;height:100%;object-fit:cover;display:block;transition:transform .2s}
.tt-tile:hover .tt-ph img{transform:scale(1.03)}
.tt-emoji{font-size:34px;opacity:.6}
.tt-tile b{font-size:15px;font-weight:700}
.tt-tile small{font-size:12.5px;color:var(--sub)}
.tt-empty{margin:0;font-size:14px;color:var(--sub)}
@media (max-width:767px){
  .tt{gap:14px}
  .tt-head h2{font-size:22px}.tt-head p{font-size:12.5px}.tt-more{font-size:13px}
  .tt-grid{grid-template-columns:repeat(3,minmax(0,1fr));gap:10px}
  .tt-ph{height:96px;border-radius:12px}
  .tt-tile b{font-size:13.5px}.tt-tile small{font-size:11.5px}
}
</style>
