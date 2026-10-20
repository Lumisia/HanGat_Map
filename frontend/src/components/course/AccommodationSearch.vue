<script setup lang="ts">
import type { AccommodationInput, KakaoPlaceSearchResult } from '../../assets/types/course'
import KakaoPlaceSearch from './KakaoPlaceSearch.vue'
import AppIcon from '../common/AppIcon.vue'

const props = defineProps<{ selected?: AccommodationInput }>()
const emit = defineEmits<{ select: [value: AccommodationInput]; clear: [] }>()

function choose(item: KakaoPlaceSearchResult) {
  emit('select', { ...item })
}

function changeSelection() {
  emit('clear')
}

function clearSelection() {
  emit('clear')
}
</script>

<template>
  <div class="accommodation-search">
    <span class="accommodation-label"><AppIcon name="home" :size="15" />숙소</span>
    <template v-if="selected">
      <div class="selected-accommodation">
        <div><small>선택한 숙소</small><b>{{ selected.place_name }}</b><span>{{ selected.road_address || selected.address }}</span></div>
        <div><button type="button" @click="changeSelection">변경</button><button type="button" @click="clearSelection">선택 해제</button></div>
      </div>
    </template>
    <KakaoPlaceSearch v-else mode="ACCOMMODATION" placeholder="숙소명을 검색해 주세요" loading-text="숙소를 검색하고 있어요..." empty-text="제주에서 해당 숙소를 찾지 못했어요." @select="choose" />
  </div>
</template>

<style scoped>
.accommodation-label{display:flex;align-items:center;gap:7px}.accommodation-label :deep(svg){color:var(--course-accent)}
.accommodation-search{display:grid;gap:6px}.accommodation-label{color:var(--course-text);font-size:.75rem;font-weight:800}.selected-accommodation{display:flex;align-items:center;justify-content:space-between;gap:12px;padding:10px 12px;border:1px solid var(--course-line);border-radius:11px;background:var(--course-surface)}.selected-accommodation>div:first-child{display:grid;gap:2px;min-width:0}.selected-accommodation b{font-size:.78rem}.selected-accommodation span{overflow:hidden;color:var(--course-text-3);font-size:.66rem;text-overflow:ellipsis;white-space:nowrap}.selected-accommodation button{flex:0 0 auto;border:0;border-radius:9px;background:var(--course-accent-bg);padding:7px 10px;color:var(--course-accent-dark);font-size:.68rem;font-weight:800}.selected-accommodation small{color:var(--course-accent-dark);font-size:.63rem;font-weight:800}.selected-accommodation>div:last-child{display:flex;gap:5px}
</style>
