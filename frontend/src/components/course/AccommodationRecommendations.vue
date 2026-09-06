<script setup lang="ts">
import type { AccommodationRecommendation } from '../../assets/types/course'

defineProps<{ items: AccommodationRecommendation[]; loading: boolean; error?: string }>()
defineEmits<{ select: [value: AccommodationRecommendation] }>()
const regionLabel = { EAST: '동부', WEST: '서부', SOUTH: '남부', NORTH: '북부' }
</script>

<template>
  <section class="accommodation-recommendations">
    <span class="summary-kicker">STAY NEARBY</span>
    <h3>추천 숙소</h3>
    <p>현재 코스 동선을 기준으로 이동하기 편한 숙소를 골라봤어요.</p>
    <div v-if="loading" class="recommendation-loading">숙소를 찾고 있어요…</div>
    <div v-else-if="error" class="recommendation-loading">{{ error }}</div>
    <div v-else-if="items.length === 0" class="recommendation-loading">현재 코스 주변에서 추천할 숙소를 찾지 못했어요.</div>
    <article v-for="item in items" v-else :key="`${item.source_code}:${item.source_place_id}`">
      <img :src="item.image_url || '/images/placeholder.svg'" :alt="item.place_name">
      <div><b>{{ item.place_name }}</b><span>{{ item.address }}</span><small>{{ item.region ? regionLabel[item.region] : '' }} · {{ item.recommendation_reason }}</small></div>
      <button type="button" @click="$emit('select', item)">이 숙소 선택</button>
    </article>
  </section>
</template>

<style scoped>
.accommodation-recommendations{padding:22px;border:1px solid rgba(214,220,225,.7);border-radius:18px;background:var(--course-surface)}.accommodation-recommendations h3{margin-bottom:6px;font-size:1rem}.accommodation-recommendations>p{margin-bottom:14px;color:var(--course-text-2);font-size:.72rem;line-height:1.5}.accommodation-recommendations article{display:grid;grid-template-columns:48px minmax(0,1fr);gap:9px;padding:11px 0;border-top:1px solid var(--course-line)}.accommodation-recommendations img{width:48px;height:48px;border-radius:10px;object-fit:cover}.accommodation-recommendations article>div{display:grid;gap:2px;min-width:0}.accommodation-recommendations b{font-size:.76rem}.accommodation-recommendations span{overflow:hidden;color:var(--course-text-3);font-size:.63rem;text-overflow:ellipsis;white-space:nowrap}.accommodation-recommendations small{color:var(--course-text-2);font-size:.64rem;line-height:1.4}.accommodation-recommendations button{grid-column:1/-1;border:0;border-radius:9px;background:var(--course-accent-bg);padding:7px;color:var(--course-accent-dark);font-size:.68rem;font-weight:800}.recommendation-loading{color:var(--course-text-2);font-size:.72rem}
</style>
