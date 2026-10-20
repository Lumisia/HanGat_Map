<script setup lang="ts">
import { computed } from 'vue'
import { transitTotal, transitState, type TransitDay, type TransitEdge } from '../../services/course/transitRoute'
const props = defineProps<{ day?: TransitDay; expectedEdges?: TransitEdge[]; loading: boolean; error: string }>()
const state = computed(() => transitState(props.day, props.loading, props.error))
</script>
<template>
  <section class="transit-summary" aria-label="일일 대중교통 요약" aria-live="polite">
    <p>조회 시점 기준 예상 경로 · 여행 당일 확정 시간표가 아니에요.</p>
    <p v-if="state === 'LOADING'">대중교통 경로를 조회하고 있어요.</p>
    <p v-else-if="state === 'DISABLED'">대중교통 경로 조회가 아직 활성화되지 않았어요.</p>
    <p v-else-if="state === 'FAILED'">대중교통 경로 정보를 불러오지 못했어요.</p>
    <p v-else-if="state === 'UNQUERIED'">아직 조회된 경로 정보가 없어요.</p>
    <p v-else-if="day">총 이동 {{ transitTotal(day, expectedEdges) }}</p>
  </section>
</template>
<style scoped>
.transit-summary { color: var(--text-secondary, #596762); font-size: .85rem; margin: 12px 0; }
p { margin: 5px 0; }
</style>
