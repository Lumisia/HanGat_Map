<script setup>
/**
 * 테마 상세 (/themes/:key) - 구석구석 테마 페이지의 아래 절반.
 * 묶음 › 테마 제목 · 한 줄 소개 · 설명 · 권역 칩 · 장소 카드(이름순, 24개씩 더 보기). 해시태그 줄과 '지도에서 이 종류만 보기'는 넣었다가 뺐다(2026-09-18 사용자).
 * 혼잡도가 아니라 장소 자체를 소개하는 페이지라 정렬은 이름순이고 예보 유무는 보지 않는다(2026-09-17 사용자 결정).
 * 카드를 누르면 장소 소개 페이지(/places/:id)로.
 */
import { computed, onMounted, ref, watch } from 'vue'

/* 뒤로 가기 복원 - 장소 페이지에 갔다 돌아오면 권역·'더 보기'로 편 개수가 처음(전체·24개)으로 돌아가 있었다(2026-09-18 점검).
   테마 키별로 탭 세션에만 남긴다(새 탭·새 세션이면 처음부터). 스크롤 위치는 라우터 scrollBehavior 가 되돌린다 */
const STATE_KEY = key => `hangat:theme:${key}`
function readState (key) {
  try { const v = JSON.parse(sessionStorage.getItem(STATE_KEY(key)) ?? 'null'); return v && typeof v === 'object' ? v : null } catch { return null }
}
function writeState (key, state) {
  try { sessionStorage.setItem(STATE_KEY(key), JSON.stringify(state)) } catch { /* 저장 못 해도 화면은 그대로 */ }
}
import { useRoute } from 'vue-router'
import MapPlaceService from '../../services/map/MapPlaceService'
import { loadThemeLayers } from '../../services/themeData.js'
import { buildThemes, findTile, placesOfTile, tileCopy } from '../../config/themes.js'
import ThemePlaceCard from '../../components/theme/ThemePlaceCard.vue'
import UnavailableCard from '../../components/common/UnavailableCard.vue'

const PAGE = 24
const REGIONS = ['전체', '동부', '서부', '남부', '북부']

const route = useRoute()
const loading = ref(true)
const failed = ref([])
const found = ref(null)        // { group, tile } - 없으면 null
const places = ref([])
const region = ref('전체')
const shown = ref(PAGE)
const photo = ref(null)        // 머리 띠 사진 - 첫 장소의 원본 사진

const copy = computed(() => (found.value ? tileCopy(found.value.tile) : null))
const filtered = computed(() => region.value === '전체' ? places.value : places.value.filter(p => p.r === region.value))
const visible = computed(() => filtered.value.slice(0, shown.value))
const regionCounts = computed(() => Object.fromEntries(REGIONS.map(r => [r, r === '전체' ? places.value.length : places.value.filter(p => p.r === r).length])))

async function load () {
  loading.value = true
  region.value = '전체'
  shown.value = PAGE
  photo.value = null
  const { layers, failed: f } = await loadThemeLayers()
  failed.value = f
  const groups = buildThemes(layers)
  const key = String(route.params.key)
  found.value = findTile(groups, key)
  places.value = found.value ? placesOfTile(layers, found.value.tile) : []
  const saved = readState(key)
  if (saved && found.value) {
    if (REGIONS.includes(saved.region)) region.value = saved.region
    if (Number.isInteger(saved.shown) && saved.shown > PAGE) shown.value = Math.min(saved.shown, places.value.length + PAGE)
  }
  if (found.value) document.title = `${found.value.tile.title} · 테마 · 한갓지도`   // 라우터가 준 '테마 · 한갓지도' 를 구체화(공유·즐겨찾기용)
  loading.value = false
  // 머리 띠 사진: 앞쪽 장소 넷 중 사진 있는 첫 곳
  for (const p of places.value.slice(0, 4)) {
    const d = await MapPlaceService.getDetail(p.id).catch(() => null)
    const url = d?.images?.[0]?.url
    if (url) { photo.value = url; break }
  }
}
onMounted(load)
watch(() => route.params.key, load)
watch([region, shown], () => { if (found.value) writeState(String(route.params.key), { region: region.value, shown: shown.value }) })
</script>

<template>
  <div class="td-page">
    <nav class="crumb" aria-label="현재 위치">
      <RouterLink to="/themes">테마</RouterLink>
      <template v-if="found"><span>›</span><span>{{ found.group.title }}</span></template>
    </nav>

    <p v-if="loading" class="note">장소 목록을 불러오고 있어요…</p>

    <template v-else-if="!found">
      <UnavailableCard title="테마를 찾지 못했어요" description="주소가 바뀌었거나 사라진 테마예요.">
        <RouterLink to="/themes" class="btn2 primary">테마 목록으로</RouterLink>
      </UnavailableCard>
    </template>

    <template v-else>
      <header class="band" :class="{ photo: !!photo }"
        :style="photo ? { backgroundImage: `linear-gradient(90deg,rgba(15,25,35,.78) 0%,rgba(15,25,35,.45) 55%,rgba(15,25,35,.2) 100%),url(&quot;${photo}&quot;)` } : { background: found.group.tint }">
        <span class="emoji" :style="{ background: found.group.tint }" aria-hidden="true">{{ found.group.emoji }}</span>
        <div class="band-text">
          <h1>{{ found.tile.title }}<small>{{ found.tile.count.toLocaleString() }}곳</small></h1>
          <p class="tagline">{{ copy.tagline }}</p>
        </div>
        <span v-if="photo" class="band-src">ⓒ한국관광공사</span>
      </header>

      <p class="desc">{{ copy.desc }}</p>
      <p v-if="found.tile.raw !== found.tile.title" class="raw">관광공사 분류명 · {{ found.tile.raw }}</p>

      <div class="tools">
        <div class="regions" role="group" aria-label="권역">
          <button v-for="r in REGIONS" :key="r" type="button" class="chip" :class="{ on: region === r }" :disabled="regionCounts[r] === 0"
            @click="region = r; shown = PAGE">{{ r }}<small>{{ regionCounts[r] }}</small></button>
        </div>
      </div>

      <p v-if="failed.length" class="note warn">{{ failed.join('·') }} 목록을 불러오지 못했어요 · 새로고침해 주세요</p>
      <p v-else-if="!filtered.length" class="note">이 권역에는 해당 장소가 없어요.</p>

      <ul class="cards">
        <ThemePlaceCard v-for="p in visible" :key="p.id ?? p.n" :place="p" :emoji="found.group.emoji" :tint="found.group.tint" />
      </ul>
      <button v-if="filtered.length > shown" type="button" class="more" @click="shown += PAGE">
        {{ Math.min(PAGE, filtered.length - shown) }}곳 더 보기 ({{ shown }} / {{ filtered.length }})
      </button>
    </template>
  </div>
</template>

<style scoped>
.td-page{max-width:1100px;margin:0 auto;padding:18px 20px 60px}
.crumb{display:flex;align-items:center;gap:6px;font-size:12.5px;color:var(--tx3);margin-bottom:12px}
.crumb a{color:var(--ac-dk);font-weight:700;text-decoration:none}
.crumb a:hover{text-decoration:underline}
.note{color:var(--tx2);font-size:14px;margin:0 0 16px}
.note.warn{color:var(--busy)}

.band{position:relative;display:flex;align-items:center;gap:16px;min-height:150px;padding:22px 24px;border-radius:20px;margin-bottom:16px;
  background-size:cover;background-position:center}
.band.photo,.band.photo h1,.band.photo p{color:#fff}
.band.photo h1 small{color:rgba(255,255,255,.8)}
.emoji{flex:0 0 auto;width:56px;height:56px;border-radius:50%;display:flex;align-items:center;justify-content:center;font-size:26px;line-height:1;
  box-shadow:0 2px 10px rgba(0,0,0,.12),inset 0 0 0 1px rgba(0,0,0,.04)}
.band h1{font-size:26px;font-weight:800;letter-spacing:-.03em;margin:0 0 4px;display:flex;align-items:baseline;gap:10px;color:var(--tx)}
.band h1 small{font-size:13px;font-weight:600;color:var(--tx3)}
.band .tagline{margin:0;font-size:14px;color:var(--tx2)}
.band-src{position:absolute;right:12px;bottom:8px;font-size:10px;color:rgba(255,255,255,.75)}

.desc{font-size:14px;line-height:1.7;color:var(--tx2);margin:0 0 8px;max-width:760px;word-break:keep-all}
.raw{margin:0 0 16px;font-size:12px;color:var(--tx3)}

.tools{display:flex;align-items:center;gap:12px;flex-wrap:wrap;margin-bottom:16px}
.regions{display:flex;gap:6px;flex-wrap:wrap}
.chip{display:inline-flex;align-items:center;gap:5px;height:30px;padding:0 12px;border-radius:15px;background:var(--surf2);color:var(--tx2);font-size:12.5px;font-weight:700;line-height:1;cursor:pointer}
.chip small{font-size:10.5px;font-weight:600;color:var(--tx3)}
.chip.on{background:var(--ac);color:var(--on-ac)}.chip.on small{color:rgba(255,255,255,.85)}
.chip:disabled{opacity:.4;cursor:default}
.chip:focus-visible,.btn:focus-visible,.more:focus-visible{outline:2px solid var(--ac);outline-offset:2px}
.btn{display:inline-flex;align-items:center;height:32px;padding:0 13px;border-radius:16px;background:var(--ac-bg);color:var(--ac-dk);font-size:12.5px;font-weight:800;text-decoration:none;white-space:nowrap}
.btn:hover{background:var(--ac);color:var(--on-ac)}

.cards{list-style:none;padding:0;margin:0;display:grid;grid-template-columns:repeat(4,1fr);gap:16px 14px}
.more{display:block;width:100%;margin-top:18px;height:44px;border-radius:12px;background:var(--surf2);color:var(--tx2);font-size:13.5px;font-weight:700;cursor:pointer}
.more:hover{background:var(--ac-bg);color:var(--ac-dk)}

@media (max-width:900px){.cards{grid-template-columns:repeat(3,1fr)}}
@media (max-width:640px){
  .td-page{padding:12px 16px 48px}
  .crumb a{padding:8px 0}                       /* 손가락 크기(전엔 25×20) */
  .chip{height:36px;padding:0 14px}             /* 권역 칩 30 → 36 */
  .band{min-height:120px;padding:16px 16px;gap:12px}.emoji{width:46px;height:46px;font-size:22px}.band h1{font-size:21px}
  .cards{grid-template-columns:repeat(2,1fr);gap:14px 10px}
}
</style>
