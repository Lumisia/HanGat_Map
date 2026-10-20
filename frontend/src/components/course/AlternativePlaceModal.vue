<script setup lang="ts">
import { computed, onMounted, onBeforeUnmount, ref } from 'vue'
import type { AlternativePlace, CourseItem } from '../../assets/types/course'
import AppIcon from '../common/AppIcon.vue'

const props = defineProps<{
  item: CourseItem; alternatives: AlternativePlace[]; loading: boolean; notice?: string; busy?: boolean
  forecastDate?: string; hasMore?: boolean; loadFailed?: boolean
}>()
const emit = defineEmits<{ close: []; select: [AlternativePlace]; more: []; retry: [] }>()
const crowded = computed(() => props.item.congestion_level === 'CROWDED')
const dialog = ref<HTMLDialogElement>()
const returnFocus = ref<HTMLElement | null>(null)
const failedImages = ref(new Set<number>())
function maybeLoadMore(event: Event) {
  const el = event.currentTarget as HTMLElement
  if (props.forecastDate && props.hasMore && !props.loading && !props.busy && !props.loadFailed
      && el.scrollTop + el.clientHeight >= el.scrollHeight - 64) emit('more')
}
onMounted(() => {
  returnFocus.value = document.activeElement as HTMLElement
  dialog.value?.showModal()
})
onBeforeUnmount(() => { dialog.value?.close(); returnFocus.value?.focus() })
</script>

<template>
  <dialog ref="dialog" class="course-modal alternative-modal" aria-labelledby="alternative-title"
    @cancel.prevent="!busy && emit('close')">
    <header class="alternative-header">
      <button type="button" class="modal-close" :disabled="busy" aria-label="대안 창 닫기" @click="emit('close')">×</button>
      <span class="eyebrow">장소 대안</span>
      <h2 id="alternative-title">{{ forecastDate ? `${item.place_name} 대신 다른 장소` : crowded ? `${item.place_name} 대신 한산한 장소` : `${item.place_name} 대신 다른 장소` }}</h2>
      <p v-if="forecastDate" class="muted">{{ forecastDate }} (한국 시간) 예보 기준 · 같은 카테고리에서 직선거리 20km 이내, 혼잡 미만인 장소를 가까운 순으로 추천해요. 코스 날짜는 바뀌지 않아요.</p>
      <p v-else class="muted">같은 카테고리 중 이 날짜 혼잡 예보가 혼잡 미만인 곳을 10km 안에서 먼저, 부족하면 20km 안에서 집중률 낮은 순으로 찾았어요.</p>
    </header>
    <div class="alternative-scroll" tabindex="0" aria-label="대안 장소 목록" @scroll="maybeLoadMore">
      <div class="alt-list" :aria-busy="loading">
        <article v-for="alt in alternatives" :key="alt.place_id">
          <div class="alternative-copy">
            <h3>{{ alt.place_name }}</h3>
            <p>{{ alt.category_name }} · 직선 {{ (alt.distance_m / 1000).toFixed(1) }}km · {{ alt.congestion_level === 'QUIET' ? '한산' : alt.congestion_level === 'CROWDED' ? '혼잡' : alt.congestion_level === 'NORMAL' ? '보통' : '예보 없음' }}</p>
            <p class="alternative-overview">{{ alt.overview || '등록된 장소 소개가 없습니다.' }}</p>
          </div>
          <div class="alternative-photo">
            <img v-if="alt.image_url && !failedImages.has(alt.place_id)" :src="alt.image_url" :alt="`${alt.place_name} 대표 사진`" loading="lazy" @error="failedImages.add(alt.place_id)">
            <span v-else><AppIcon name="album" :size="24" /><small>사진 준비 중</small></span>
          </div>
          <div class="alternative-actions">
            <RouterLink class="btn alternative-map" :to="{ path: '/map', query: { place: alt.place_id } }" target="_blank" rel="noopener noreferrer" :aria-label="`${alt.place_name} 지도에서 보기 (새 탭)`"><AppIcon name="map" :size="16" />지도에서 보기</RouterLink>
            <button type="button" class="btn primary select-alternative" :disabled="busy" @click="emit('select', alt)">{{ busy ? '바꾸는 중…' : '이곳으로 변경' }}</button>
          </div>
        </article>
      </div>
      <p v-if="loading" role="status" class="alternative-status">가까운 대안 장소를 찾고 있어요. 잠시만 기다려 주세요…</p>
      <p v-if="notice" role="alert" class="course-notice">{{ notice }}</p>
      <button v-if="loadFailed" type="button" class="btn" :disabled="busy || loading" @click="emit('retry')">다시 시도</button>
      <template v-if="forecastDate && !loading && !loadFailed">
        <button v-if="hasMore" type="button" class="btn alternative-more" :disabled="busy" @click="emit('more')">다음 대안 3곳 보기</button>
        <p v-else role="status" class="alternative-status">더 이상 가능한 장소 대안이 없습니다.</p>
      </template>
      <p v-else-if="!forecastDate && !loading && !notice && !alternatives.length" class="alternative-status">조건에 맞는 다른 장소를 찾지 못했어요.</p>
    </div>
  </dialog>
</template>

<style scoped>
.alternative-modal{width:min(680px,calc(100vw - 28px));max-height:min(85dvh,820px);margin:auto;padding:0;border:1px solid var(--line);border-radius:20px;background:var(--surface);color:var(--text);overflow:hidden}
.alternative-modal[open]{display:flex;flex-direction:column}.alternative-modal::backdrop{background:#102b3488}
.alternative-header{position:relative;padding:24px 24px 16px;flex-shrink:0}.alternative-header h2{font-size:24px;line-height:1.4;padding-right:25px;margin:8px 0}.alternative-header .muted{font-size:13px;line-height:1.6;color:var(--tx2);margin:0}.modal-close{color:var(--text);cursor:pointer}.eyebrow{color:var(--primary);font-size:11px;letter-spacing:.15em}
.alternative-scroll{padding:0 24px 24px;overflow-y:auto;min-height:0;overscroll-behavior:contain;scrollbar-gutter:stable}
.alt-list{display:grid;gap:12px}.alt-list article{display:grid;grid-template-columns:minmax(0,1fr) 96px;align-items:start;gap:12px;padding:16px;border:1px solid var(--line);border-radius:14px;background:var(--surf2)}
.alternative-copy{min-width:0}.alternative-copy h3{font-size:17px;color:var(--text);margin:0 0 6px;overflow-wrap:anywhere}.alternative-copy p{font-size:12px;color:var(--tx2);line-height:1.6;margin:0 0 6px}.alternative-copy .alternative-overview{display:-webkit-box;-webkit-line-clamp:3;-webkit-box-orient:vertical;overflow:hidden;white-space:pre-line}
.alternative-photo{width:96px;height:96px;border-radius:10px;overflow:hidden;background:var(--surface);color:var(--tx3)}.alternative-photo img{width:100%;height:100%;object-fit:cover}.alternative-photo span{display:flex;height:100%;flex-direction:column;align-items:center;justify-content:center;gap:6px}.alternative-photo small{font-size:10px}
.alternative-actions{grid-column:1/-1;display:flex;justify-content:flex-end;gap:8px}.alternative-actions .btn{display:inline-flex;align-items:center;justify-content:center;gap:5px;white-space:nowrap;padding:10px 12px;font-size:12px;min-height:40px}.alternative-map{background:var(--surface);color:var(--primary);border:1px solid var(--line);text-decoration:none}.select-alternative{background:var(--primary);color:var(--on-ac);border:0}
.alternative-status{text-align:center;color:var(--tx2);font-size:13px;line-height:1.6;padding:16px 0;margin:0}.alternative-more{width:100%;margin-top:12px}.btn:focus-visible,.alternative-scroll:focus-visible{outline:2px solid var(--primary);outline-offset:2px}
@media(max-width:760px){.alternative-header{padding:20px 16px 12px}.alternative-header h2{font-size:20px}.alternative-header .muted{font-size:12px}.alternative-scroll{padding:0 14px 18px}.alt-list article{grid-template-columns:minmax(0,1fr) 76px;padding:12px;gap:10px}.alternative-photo{width:76px;height:82px}.alternative-copy h3{font-size:15px}.alternative-actions .btn{font-size:11px;padding:10px}.alternative-modal{max-height:90dvh}}
</style>
