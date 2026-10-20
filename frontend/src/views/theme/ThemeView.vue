<script setup>
/**
 * 테마 페이지 (/themes) - 구석구석 테마 페이지 구조를 우리 데이터로.
 * 세부분류 전부를 묶음(자연·문화와 시설·역사·체험·레저·축제·캠핑·식당·숙소·한갓지도가 고른 명소)별 타일로 그린다.
 * 곳수는 장소 목록 API 에서 세고 예보 유무는 보지 않는다. 타일을 눌렀을 때의 소개·장소 목록은 다음 커밋.
 *
 * 밋밋함 대책(2026-09-17 사용자 요청): ① 맨 위 제주 사진 히어로 ② 묶음 머리마다 대표 장소 사진 띠
 * (곳수 가장 많은 타일의 첫 장소 상세를 화면에 들어올 때 받아 첫 사진을 깐다 - 묶음 10개 = 요청 최대 10번)
 * ③ 히어로 아래 묶음 바로가기 칩 줄(제자리 고정 - 처음엔 스크롤을 따라오게 했는데 사용자가 고정을 원함). 사진이 없는 묶음은 묶음 색 배경으로 남는다.
 *
 * 가독성(2026-09-19 외부 피드백 "카테고리가 너무 많아 뭐가 있는지 안 들어온다"): 타일 121개 중 49개가 1~2곳뿐이라 큰 타일이
 * 똑같은 크기로 서 있었다. 묶음마다 곳수 많은 순 6개만 타일로 보이고, 나머지는 "그 밖의 종류 N개 보기"를 누르면 작은 칩으로 펼친다.
 * 분류를 합치거나 빼지 않는다(관광공사 분류 그대로 - 페이지 약속). 펼친 묶음은 상세로 갔다가 **뒤로 가기**로 돌아올 때만 유지한다 -
 * 새로고침·다른 메뉴 갔다 오기는 전부 접힌 첫 화면(2026-09-19 사용자 지적: 세션 내내 기억하면 한 번 펼친 뒤론 정돈 효과가 없다).
 */
import { onBeforeUnmount, onMounted, ref } from 'vue'
import { onBeforeRouteLeave } from 'vue-router'
import MapPlaceService from '../../services/map/MapPlaceService'
import { loadThemeLayers } from '../../services/themeData.js'
import { buildThemes } from '../../config/themes.js'

const groups = ref([])
const loading = ref(true)
const failed = ref([])          // 못 받은 레이어 이름들 - 그 묶음만 비어 보이는 이유를 말해 준다
const photos = ref({})          // 묶음 key → 대표 사진 URL(없으면 null)
const sectionEls = ref({})      // 묶음 key → section 요소
const headerH = ref(80)         // 앱 헤더 높이 - 칩으로 이동할 때 묶음 머리가 헤더에 가리지 않게
let io = null

/* 접기 - 묶음마다 처음엔 곳수 많은 순 TOP 개만 타일. 나머지가 2개 미만이면 접을 게 없으니 전부 보인다 */
const TOP = 6
const OPEN_KEY = 'hangat:themes:open'   // 상세로 나갈 때만 적고, 돌아와 한 번 읽으면 지운다 - 새로고침·다른 메뉴 경유는 접힌 채
const open = ref(new Set())
try {
  const saved = sessionStorage.getItem(OPEN_KEY)
  if (saved) { sessionStorage.removeItem(OPEN_KEY); open.value = new Set(JSON.parse(saved)) }
} catch { /* 저장소 접근 불가 - 전부 접힌 채 시작 */ }
onBeforeRouteLeave(to => {
  if (to.name !== 'theme-detail' || !open.value.size) return
  try { sessionStorage.setItem(OPEN_KEY, JSON.stringify([...open.value])) } catch { /* 기억만 못 할 뿐 */ }
})
const foldable = g => g.tiles.length - TOP >= 2
const shownTiles = g => foldable(g) ? g.tiles.slice(0, TOP) : g.tiles   // 접을 수 있는 묶음은 펼쳐도 타일은 6개, 나머지는 칩
const restTiles = g => g.tiles.slice(TOP)
function toggleOpen (key) {
  const next = new Set(open.value)
  next.has(key) ? next.delete(key) : next.add(key)
  open.value = next
}

const bandStyle = key => photos.value[key]
  ? { backgroundImage: `linear-gradient(90deg,rgba(15,25,35,.72) 0%,rgba(15,25,35,.35) 55%,rgba(15,25,35,.15) 100%),url("${photos.value[key]}")` }
  : null

const jumpTo = key => sectionEls.value[key]?.scrollIntoView({ behavior: 'smooth', block: 'start' })

async function loadPhoto (g) {
  if (g.key in photos.value) return
  photos.value[g.key] = null
  // 곳수 많은 타일부터 그 첫 장소의 사진을 찾는다. 사진 없는 장소(레저 스포츠 등)가 걸리면 다음 타일로 - 최대 4곳
  for (const t of g.tiles.slice(0, 4)) {
    if (!t.sample?.id) continue
    const d = await MapPlaceService.getDetail(t.sample.id).catch(() => null)
    const url = d?.images?.[0]?.url
    if (!url) continue
    // 큰 사진이 한 번에 툭 뜨지 않게 먼저 받아 두고 붙인다
    const ok = await new Promise(resolve => { const im = new Image(); im.onload = () => resolve(true); im.onerror = () => resolve(false); im.src = url })
    if (ok) { photos.value[g.key] = url; return }
  }
}

onMounted(async () => {
  headerH.value = document.querySelector('nav.nav')?.offsetHeight ?? 80   // 앱 헤더(.nav 80px, 폰 60px). 페이지의 <header class="hero"> 가 아니라
  // 관광지·식당·착한가격·숙소 네 레이어 - 테마 상세와 같은 로더(세션 안에서 한 번만 받는다)
  const { layers, failed: f } = await loadThemeLayers()
  failed.value = f
  groups.value = buildThemes(layers)
  loading.value = false
  // 화면에 들어오는 묶음부터 사진을 받는다. 지원 안 되는 브라우저는 전부 받는다
  if (typeof IntersectionObserver === 'undefined') { groups.value.forEach(loadPhoto); return }
  io = new IntersectionObserver(entries => {
    for (const e of entries) {
      const g = groups.value.find(x => x.key === e.target.dataset.group)
      if (g && e.isIntersecting) loadPhoto(g)
    }
  }, { rootMargin: '200px 0px 200px 0px', threshold: 0.01 })
  requestAnimationFrame(() => Object.values(sectionEls.value).forEach(el => el && io.observe(el)))
})
onBeforeUnmount(() => io?.disconnect())
</script>

<template>
  <div class="theme-page">
    <header class="hero">
      <img class="hero-bg" src="/images/hero-jeju.jpg" alt="">
      <div class="hero-inner">
        <span class="pill">테마로 고르기</span>
        <h1>어떤 제주를 찾고 있나요?</h1>
        <p>오름부터 야영장까지, 관광공사가 나눈 종류 그대로 모았어요.<br>종류를 고르면 그 안의 장소를 하나씩 소개합니다.</p>
      </div>
      <span class="hero-src">ⓒ한국관광공사</span>
    </header>

    <nav v-if="groups.length" class="jump" aria-label="묶음 바로가기">
      <button v-for="g in groups" :key="g.key" type="button" class="jump-chip" @click="jumpTo(g.key)">
        <span aria-hidden="true">{{ g.emoji }}</span>{{ g.title }}
      </button>
    </nav>

    <p v-if="loading" class="note">장소 목록을 불러오고 있어요…</p>
    <p v-else-if="failed.length" class="note warn">{{ failed.join('·') }} 목록을 불러오지 못했어요 · 새로고침해 주세요</p>

    <section v-for="g in groups" :key="g.key" :ref="el => (sectionEls[g.key] = el)" :data-group="g.key" class="group"
      :style="{ scrollMarginTop: headerH + 12 + 'px' }" :aria-labelledby="`theme-group-${g.key}`">
      <div class="band" :class="{ photo: !!photos[g.key] }" :style="bandStyle(g.key) ?? { background: g.tint }">
        <span class="group-emoji" :style="{ background: g.tint }" aria-hidden="true">{{ g.emoji }}</span>
        <div class="band-text">
          <h2 :id="`theme-group-${g.key}`">{{ g.title }}<small>{{ g.total.toLocaleString() }}곳 · {{ g.tiles.length }}종류</small></h2>
          <p>{{ g.desc }}</p>
        </div>
        <span v-if="photos[g.key]" class="band-src">ⓒ한국관광공사</span>
      </div>
      <ul class="tiles">
        <li v-for="t in shownTiles(g)" :key="t.key">
          <RouterLink :to="`/themes/${t.key}`" class="tile" :title="t.raw !== t.title ? `관광공사 분류명: ${t.raw}` : undefined">
            <b>{{ t.title }}</b>
            <small>{{ t.count.toLocaleString() }}곳</small>
          </RouterLink>
        </li>
      </ul>
      <!-- 곳수 적은 나머지 종류 - 접혀 있다가 작은 칩으로. 분류는 하나도 안 뺀다 -->
      <template v-if="foldable(g)">
        <ul v-if="open.has(g.key)" class="chips" :aria-label="`${g.title} 나머지 종류`">
          <li v-for="t in restTiles(g)" :key="t.key">
            <RouterLink :to="`/themes/${t.key}`" class="chip-tile" :title="t.raw !== t.title ? `관광공사 분류명: ${t.raw}` : undefined">
              {{ t.title }}<small>{{ t.count.toLocaleString() }}</small>
            </RouterLink>
          </li>
        </ul>
        <button type="button" class="more" :aria-expanded="open.has(g.key)" @click="toggleOpen(g.key)">
          {{ open.has(g.key) ? '접기 ‹' : `그 밖의 종류 ${g.tiles.length - TOP}개 보기 ›` }}
        </button>
      </template>
    </section>
  </div>
</template>

<style scoped>
.theme-page{max-width:1100px;margin:0 auto;padding:22px 20px 60px}

/* 히어로 - 메인의 제주 사진을 그대로, 글자는 흰색 */
.hero{position:relative;overflow:hidden;border-radius:22px;min-height:230px;display:flex;align-items:flex-end;margin-bottom:14px;color:#fff}
.hero-bg{position:absolute;inset:0;width:100%;height:100%;object-fit:cover;object-position:center 60%}
.hero::after{content:'';position:absolute;inset:0;background:linear-gradient(180deg,rgba(15,25,35,.05) 0%,rgba(15,25,35,.62) 100%)}
.hero-inner{position:relative;z-index:1;padding:28px 28px 26px}
.hero-src{position:absolute;right:14px;bottom:10px;z-index:1;font-size:11px;color:rgba(255,255,255,.8)}   /* 사진이 관광공사 것이라 출처(심사 규정) */
.pill{display:inline-block;padding:4px 11px;border-radius:999px;background:rgba(255,255,255,.18);border:1px solid rgba(255,255,255,.35);color:#fff;font-size:12px;font-weight:700;backdrop-filter:blur(4px)}
.hero h1{font-size:28px;font-weight:800;letter-spacing:-.03em;margin:10px 0 6px;text-shadow:0 2px 12px rgba(0,0,0,.25)}
.hero p{margin:0;font-size:14px;color:rgba(255,255,255,.88);max-width:560px;line-height:1.55}

/* 바로가기 칩 줄 - 히어로 아래 제자리(따라오지 않음) */
.jump{display:flex;gap:6px;flex-wrap:wrap;padding:4px 0 6px;margin-bottom:14px}
.jump-chip{flex:0 0 auto;display:inline-flex;align-items:center;gap:5px;height:32px;padding:0 12px;border-radius:16px;
  background:var(--surf2);color:var(--tx2);font-size:12.5px;font-weight:700;white-space:nowrap;cursor:pointer;border:1px solid transparent}
.jump-chip:hover{border-color:var(--ac)}
.jump-chip:focus-visible{outline:2px solid var(--ac);outline-offset:2px}

.note{color:var(--tx2);font-size:14px;margin:0 0 18px}
.note.warn{color:var(--busy)}

/* 묶음 머리 - 사진이 오면 어두운 띠 위에 흰 글자, 없으면 묶음 색 바탕에 기본 글자 */
.group{margin-bottom:30px}
.band{position:relative;display:flex;align-items:center;gap:14px;min-height:112px;padding:18px 20px;border-radius:18px;margin-bottom:12px;
  background-size:cover;background-position:center;transition:background .3s}
.band.photo{color:#fff}
.band.photo h2,.band.photo p{color:#fff}
.band.photo h2 small{color:rgba(255,255,255,.8)}
.group-emoji{flex:0 0 auto;width:50px;height:50px;border-radius:50%;display:flex;align-items:center;justify-content:center;
  font-size:24px;line-height:1;box-shadow:0 2px 10px rgba(0,0,0,.12),inset 0 0 0 1px rgba(0,0,0,.04)}
.band h2{font-size:19px;font-weight:800;letter-spacing:-.02em;margin:0 0 3px;display:flex;align-items:baseline;gap:8px;color:var(--tx)}
.band h2 small{font-size:12px;font-weight:600;color:var(--tx3)}
.band p{margin:0;font-size:12.5px;color:var(--tx2)}
.band-src{position:absolute;right:12px;bottom:8px;font-size:10px;color:rgba(255,255,255,.75)}

.tiles{list-style:none;padding:0;margin:0;display:grid;grid-template-columns:repeat(auto-fill,minmax(150px,1fr));gap:8px}
.tile{width:100%;display:flex;align-items:baseline;justify-content:space-between;gap:8px;padding:11px 13px;text-decoration:none;
  background:var(--surf);border:1px solid var(--line);border-radius:12px;text-align:left;cursor:pointer;
  transition:transform .12s,box-shadow .12s,border-color .12s}
.tile:hover{transform:translateY(-1px);box-shadow:var(--sh);border-color:var(--ac)}
.tile:focus-visible{outline:2px solid var(--ac);outline-offset:2px}
.tile b{font-size:14px;font-weight:700;letter-spacing:-.02em;color:var(--tx);word-break:keep-all}
.tile small{font-size:11.5px;color:var(--tx3);white-space:nowrap}

/* 나머지 종류 칩 - 타일보다 작게, 글자만. 곳수는 숫자만 붙인다 */
.chips{list-style:none;padding:0;margin:8px 0 0;display:flex;flex-wrap:wrap;gap:6px}
.chip-tile{display:inline-flex;align-items:baseline;gap:5px;height:30px;padding:0 11px;border-radius:15px;text-decoration:none;
  background:var(--surf);border:1px solid var(--line);color:var(--tx2);font-size:12.5px;font-weight:600;line-height:28px;white-space:nowrap}
.chip-tile:hover{border-color:var(--ac);color:var(--tx)}
.chip-tile:focus-visible{outline:2px solid var(--ac);outline-offset:2px}
.chip-tile small{font-size:11px;color:var(--tx3)}
.more{display:inline-flex;align-items:center;margin-top:8px;padding:6px 2px;border:0;background:none;color:var(--ac);font:inherit;font-size:13px;font-weight:700;cursor:pointer}
.more:hover{text-decoration:underline}
.more:focus-visible{outline:2px solid var(--ac);outline-offset:2px;border-radius:4px}

@media (max-width:640px){
  .theme-page{padding:14px 16px 48px}
  .hero{min-height:190px;border-radius:18px}.hero-inner{padding:20px 18px}.hero h1{font-size:22px}.hero p{font-size:13px}
  .jump{flex-wrap:nowrap;overflow-x:auto;margin:0 -16px 10px;padding-left:16px;padding-right:16px;scrollbar-width:none}.jump::-webkit-scrollbar{display:none}
  .band{min-height:96px;padding:14px 14px;gap:12px}.group-emoji{width:42px;height:42px;font-size:20px}.band h2{font-size:17px}
  .tiles{grid-template-columns:repeat(2,1fr);gap:7px}.tile{padding:10px 11px}
  .more{min-height:36px}   /* 터치 영역 */
}
</style>
