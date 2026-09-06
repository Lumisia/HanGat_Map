<script setup lang="ts">
import { computed } from 'vue'
import type { AlternativePlace, CourseItem } from '../../assets/types/course'

/**
 * notice: 후보를 못 구했거나 교체가 실패한 '이유'(예: 그 날짜 예보 없음, 이미 담긴 장소). 목록 위에 보여준다.
 * busy: 교체 요청 중 - 선택 버튼을 막아 더블클릭 이중 스왑을 막는다.
 */
const props = defineProps<{ item: CourseItem; alternatives: AlternativePlace[]; loading: boolean; notice?: string; busy?: boolean }>()
defineEmits<{ close: []; select: [AlternativePlace] }>()
const crowded = computed(() => props.item.congestion_level === 'CROWDED')
</script>

<template>
  <div class="modal-backdrop" @click.self="$emit('close')">
    <section class="course-modal">
      <button class="modal-close" @click="$emit('close')">×</button>
      <span class="eyebrow">장소 대안</span>
      <h2>{{ crowded ? `${item.place_name} 대신 한산한 장소` : `${item.place_name} 대신 다른 장소` }}</h2>
      <!-- 서버 규칙 그대로 적는다(정직성) - 취향·동선 점수는 후보 선정에 쓰지 않는다 -->
      <p class="muted">같은 카테고리 중 이 날짜 혼잡 예보가 혼잡 미만인 곳을 10km 안에서 먼저, 부족하면 20km 안에서 집중률 낮은 순으로 찾았어요.</p>
      <p v-if="loading">{{ crowded ? '가까운 한산한 장소를 찾고 있어요…' : '일정에 어울리는 다른 장소를 찾고 있어요…' }}</p>
      <div v-else class="alt-list">
        <p v-if="alternatives.some(alt => alt.radius_km === 20)" class="course-notice">10km 후보를 먼저 표시하고, 부족한 경우 20km 안의 조금 더 먼 대안을 함께 보여드려요.</p>
        <article v-for="alt in alternatives" :key="alt.place_id">
          <div>
            <span v-if="alt.radius_km === 20" class="eyebrow">조금 더 먼 대안</span>
            <h3>{{ alt.place_name }}</h3>
            <p>{{ alt.category_name }} · {{ (alt.distance_m / 1000).toFixed(1) }}km · {{ alt.congestion_level === 'QUIET' ? '한산' : alt.congestion_level === 'CROWDED' ? '혼잡' : '보통' }}</p>
            <small>{{ alt.recommendation_reason }}</small>
          </div>
          <button class="btn primary select-alternative" :disabled="busy" @click="$emit('select', alt)">{{ busy ? '바꾸는 중…' : '이곳으로 변경' }}</button>
        </article>
        <p v-if="notice" class="course-notice">{{ notice }}</p>
        <p v-else-if="!alternatives.length">{{ crowded ? '가까운 한산한 대안을 찾지 못했어요.' : '조건에 맞는 다른 장소를 찾지 못했어요.' }}</p>
      </div>
    </section>
  </div>
</template>

<style scoped>
.select-alternative{white-space:nowrap}
</style>
