<script setup>
/**
 * 찜한 장소 (요구사항 정의서 MY_006 · MY_007).
 *
 *  MY_006 — 내 찜만 조회 / 카드·목록 형태 / 대표 이미지·장소명·카테고리·주소·평점·운영 상태
 *           날씨·혼잡 표시 / 선택 시 카드 안 장소 메뉴 / 지도 보기 / 최근·이름·카테고리 정렬 / 빈 상태
 *  MY_007 — 찜 해제 시 목록에서 즉시 제거
 */
import { computed, onMounted, ref, watch } from 'vue'
import CrowdBadge from '../../components/common/CrowdBadge.vue'
import WeatherBadge from '../../components/common/WeatherBadge.vue'
import EmptyState from '../../components/common/EmptyState.vue'
import StateBlock from '../../components/common/StateBlock.vue'
import ListPagination from '../../components/mypage/ListPagination.vue'
import PlaceThumb from '../../components/mypage/PlaceThumb.vue'
import StarRating from '../../components/mypage/StarRating.vue'
/* 2026-09-07 백엔드 찜 API 연결(지도 담당 이후경) - 목업(api/mypage.js) 대신 api/favorites.js 를 읽는다. 항목 모양은 같다 */
import { listFavorites, removeFavorite, FAVORITE_SORTS } from '../../api/favorites.js'
import { operationStatus } from '../../data/places.js'
import { useUiStore } from '../../stores/ui.js'
import { useApiError } from '../../composables/useApiError.js'

const ui = useUiStore()
const emit = defineEmits(['favorites-changed'])
const toMessage = useApiError()

const sort = ref('recent')
const view = ref('card') // card | list
const deletingId = ref(null)
const actionError = ref('')
const selectedId = ref(null)

const loading = ref(true)
const error = ref(null)
const data = ref({ items: [], total: 0 })
const page = ref(0)
const pageSize = 6
const totalPages = computed(() => Math.ceil(data.value.items.length / pageSize))
const visibleItems = computed(() => data.value.items.slice(page.value * pageSize, (page.value + 1) * pageSize))
watch(totalPages, pages => { page.value = Math.min(page.value, Math.max(0, pages - 1)) })

async function fetchList () {
  loading.value = data.value.items.length === 0
  error.value = null
  try {
    data.value = await listFavorites({ sort: sort.value })
    if (selectedId.value && !data.value.items.some(i => i.placeId === selectedId.value)) {
      selectedId.value = null
    }
  } catch (e) {
    const msg = toMessage(e)
    if (msg) error.value = msg
  } finally {
    loading.value = false
  }
}

onMounted(fetchList)
watch(sort, () => { page.value = 0; fetchList() })

watch([page, sort, view], () => { selectedId.value = null; actionError.value = '' })

function select (placeId) {
  selectedId.value = selectedId.value === placeId ? null : placeId
  actionError.value = ''
}

async function unfavorite (item) {
  if (deletingId.value != null) return
  deletingId.value = item.placeId
  actionError.value = ''
  try {
    await removeFavorite(item.placeId)
    // 즉시 반영 (MY_007)
    data.value = {
      ...data.value,
      items: data.value.items.filter(i => i.placeId !== item.placeId),
      total: data.value.total - 1
    }
    if (selectedId.value === item.placeId) selectedId.value = null
    emit('favorites-changed')
    ui.toast(`${item.name} 찜을 해제했어요`)
  } catch (e) {
    const msg = toMessage(e)
    if (msg) { actionError.value = msg; ui.toast(msg) }
  } finally {
    deletingId.value = null
  }
}

/* 실데이터 운영시간은 자유 텍스트다 - "09:00~18:00" 꼴(hours)만 운영 중/종료를 판정하고 나머지는 원문을 그대로 보여준다 */
/* 폐업이 운영시간보다 먼저다 - 원천 목록에서 두 번 연속 빠진 장소. 찜은 남기고 표시만 한다 */
const status = item => item.closed
  ? { code: 'CLOSED', label: '폐업' }
  : item.hours
    ? operationStatus(item)
    : { code: item.hoursText ? 'TEXT' : 'UNKNOWN', label: item.hoursText ?? '운영시간 정보 없음' }
</script>

<template>
  <section>
    <div class="bar-top">
      <div class="sect">찜한 장소 <span class="cnt tnum">{{ data.total }}</span></div>
      <label class="favorite-sort">정렬 :
        <select v-model="sort" aria-label="찜 정렬 기준">
          <option v-for="option in FAVORITE_SORTS" :key="option.key" :value="option.key">{{ option.label }}</option>
        </select>
      </label>
    </div>

    <div v-if="!loading && !error && data.items.length" class="tools">
      <div class="seg small">
        <button :class="{ on: view === 'card' }" @click="view = 'card'">카드</button>
        <button :class="{ on: view === 'list' }" @click="view = 'list'">목록</button>
      </div>
    </div>

    <StateBlock :loading="loading" :error="error" :rows="3" @retry="fetchList" />

    <template v-if="!loading && !error">
      <EmptyState
        v-if="!data.items.length"
        title="찜한 장소가 없어요"
        hint="지도에서 마음에 드는 장소를 열고 하트를 누르면 여기에 모여요."
        action-label="지도에서 찾아보기"
        action-to="/map"
      />

      <template v-else>
        <!-- 카드 보기 -->
        <ul v-if="view === 'card'" class="cards">
          <li v-for="p in visibleItems" :key="p.placeId">
            <article class="card hoverable" :class="{ sel: p.placeId === selectedId }">
              <button class="hit" :aria-label="`${p.name} 메뉴`" :aria-expanded="selectedId === p.placeId" @click="select(p.placeId)">
                <!-- 없거나 깨진 대표사진은 메인과 같은 이미지 준비 안내를 표시한다. -->
                <PlaceThumb :category="p.category" :name="p.name" :src="p.imageUrl" image-placeholder size="100%" radius="12px" class="th" />
                <div class="cbody">
                  <div class="cname">{{ p.name }}</div>
                  <p class="note addr">{{ p.category }} · {{ p.addr }}</p>
                  <div class="crow">
                    <template v-if="p.rating != null">
                      <StarRating :model-value="Math.round(p.rating)" :size="12" />
                      <span class="score tnum">{{ p.rating }}</span>
                      <span class="note tnum">({{ p.reviewCount }})</span>
                    </template>
                    <span v-else class="note">평점 없음</span>
                    <span class="op" :class="status(p).code.toLowerCase()">{{ status(p).label }}</span>
                  </div>
                  <div class="cbadges">
                    <CrowdBadge :value="p.crowd" />
                    <WeatherBadge v-if="p.weather" :kind="p.weather.kind" :t="p.weather.t" :size="15" />
                  </div>
                </div>
              </button>
              <div v-if="selectedId === p.placeId" class="place-actions">
<RouterLink class="btn2" :to="{ name: 'map', query: { place: p.placeId } }">지도에서 보기</RouterLink>
<RouterLink class="btn2" :to="{ name: 'place-detail', params: { placeId: p.placeId } }">장소 상세 보기</RouterLink>
<button type="button" class="btn2 danger" :aria-label="`${p.name} 찜 해제`" :disabled="deletingId != null" @click="unfavorite(p)">{{ deletingId === p.placeId ? '해제 중…' : '찜 해제' }}</button>
<p v-if="actionError" class="action-error" role="alert">{{ actionError }}</p>
</div>
            </article>
          </li>
        </ul>

        <!-- 목록 보기 (원본 .row 구조) -->
        <ul v-else class="rows">
          <li v-for="p in visibleItems" :key="p.placeId">
            <button class="row" :aria-label="`${p.name} 메뉴`" :aria-expanded="selectedId === p.placeId" :class="{ sel: p.placeId === selectedId }" @click="select(p.placeId)">
              <span v-if="p.crowdTier" class="rpin" :class="p.crowdTier" aria-hidden="true" />
              <span class="rinfo">
                <span class="rn">{{ p.name }}</span>
                <span class="rs">{{ p.category }} · {{ p.addr }}</span>
              </span>
              <CrowdBadge :value="p.crowd" />
            </button>
            <div v-if="selectedId === p.placeId" class="place-actions">
<RouterLink class="btn2" :to="{ name: 'map', query: { place: p.placeId } }">지도에서 보기</RouterLink>
<RouterLink class="btn2" :to="{ name: 'place-detail', params: { placeId: p.placeId } }">장소 상세 보기</RouterLink>
<button type="button" class="btn2 danger" :aria-label="`${p.name} 찜 해제`" :disabled="deletingId != null" @click="unfavorite(p)">{{ deletingId === p.placeId ? '해제 중…' : '찜 해제' }}</button>
<p v-if="actionError" class="action-error" role="alert">{{ actionError }}</p>
</div>
          </li>
        </ul>

        <ListPagination :page="page" :total-pages="totalPages" :busy="loading" label="찜 페이지" @change="page = $event" />
      </template>
    </template>
  </section>
</template>

<style scoped>
.bar-top { display: flex; align-items: center; gap: 12px; margin-bottom: 12px; flex-wrap: wrap; }
.bar-top .sect { margin: 0; flex: 1; }
.cnt { color: var(--tx3); font-weight: 700; }
.favorite-sort { display: flex; align-items: center; gap: 8px; color: var(--tx2); font-size: 12px; }
.favorite-sort select { min-height: 40px; max-width: 100%; padding: 8px 28px 8px 10px; border: 1px solid var(--line); border-radius: 8px; background: var(--surf2); color: var(--tx2); font: inherit; }
.favorite-sort select:focus-visible { outline: 2px solid var(--ac); outline-offset: 2px; }

.tools { display: flex; align-items: center; gap: 8px; margin-bottom: 12px; }
.seg.small { width: 150px; }
.tools .btn2 { padding: 8px 14px; }

.cards { display: grid; grid-template-columns: repeat(auto-fill, minmax(224px, 1fr)); gap: 10px; }
.cards .card { padding: 10px; position: relative; }
.cards .card.sel { border-color: var(--ac); }
.hit { display: block; width: 100%; text-align: left; }
.th { width: 100% !important; height: 96px !important; }
.cbody { padding: 10px 4px 2px; }
.cname { font-size: 14px; font-weight: 800; letter-spacing: -.02em; }
.addr { margin-top: 2px; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.crow { display: flex; align-items: center; gap: 5px; margin-top: 7px; flex-wrap: wrap; }
.score { font-size: 12px; font-weight: 800; }
.op { font-size: 10.5px; font-weight: 700; margin-left: auto; color: var(--tx3); }
.op.open { color: var(--calm); }
.op.closed { color: var(--busy); }
.cbadges { display: flex; gap: 5px; margin-top: 9px; flex-wrap: wrap; }

.rows { display: flex; flex-direction: column; gap: 6px; }
.rows li { display: flex; flex-direction: column; gap: 4px; padding: 8px; background: var(--surf); border: 1px solid var(--line); border-radius: 12px; }
.rows .row { width: 100%; }
.row {
  flex: 1; min-width: 0; display: flex; align-items: center; gap: 10px;
  padding: 9px 11px; border-radius: 12px;
  background: var(--surf); border: 1px solid var(--line); text-align: left;
  transition: box-shadow .12s, transform .12s;
}
.row:hover { transform: translateX(2px); box-shadow: var(--sh); }
.row.sel { border-color: var(--ac); }
.rinfo { flex: 1; min-width: 0; display: flex; flex-direction: column; }
.rn { font-size: 13.5px; font-weight: 700; white-space: nowrap; overflow: hidden; text-overflow: ellipsis; }
.rs { font-size: 11px; color: var(--tx3); white-space: nowrap; overflow: hidden; text-overflow: ellipsis; }
.place-actions { display: flex; flex-direction: column; gap: 8px; width: 100%; border-top: 1px solid var(--line); margin-top: 12px; padding-top: 12px; }
.place-actions .btn2 { width: 100%; min-height: 44px; text-align: center; }
.action-error { color: var(--busy); font-size: 12px; line-height: 1.6; }
.hit:focus-visible, .row:focus-visible { outline: 2px solid var(--ac); outline-offset: 2px; }
</style>
