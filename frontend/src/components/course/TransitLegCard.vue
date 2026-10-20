<script setup lang="ts">
import { computed } from 'vue'
import { travelTime, metres, walkingTime, transitLegMessage, type TransitLeg } from '../../services/course/transitRoute'
const props = defineProps<{ leg?: TransitLeg; from: string; to: string; loading: boolean }>()
const buses = computed(() => [...new Set(props.leg?.steps.filter(s => s.type === 'BUS').flatMap(s => s.vehicles) ?? [])])
</script>
<template>
  <details class="transit-leg" :aria-busy="loading" :aria-label="`${from} → ${to} 대중교통 경로`">
    <summary>
      <span class="journey">{{ from }} → {{ to }}</span>
      <strong v-if="leg?.status === 'OK'">{{ travelTime(leg.duration_seconds) }}</strong>
      <span v-else>{{ transitLegMessage(leg?.status, loading) }}</span>
      <span v-if="leg?.status === 'OK'" class="metrics">도보 {{ travelTime(walkingTime(leg)) }} · 환승 {{ leg.transfers == null ? '정보 없음' : `${leg.transfers}회` }} · {{ metres(leg.distance_meters) }}</span>
      <span v-if="buses.length" class="buses">버스 {{ buses.join(' · ') }}</span>
      <span class="expand">이동 상세</span>
    </summary>
    <ol v-if="leg?.status === 'OK'" class="steps">
      <li v-for="(step,index) in leg.steps" :key="index">
        <span class="icon" aria-hidden="true">{{ step.type === 'WALKING' ? '🚶' : step.type === 'BUS' ? '🚌' : '🚇' }}</span>
        <div>
          <strong>{{ step.type === 'WALKING' ? '도보' : step.type === 'BUS' ? '버스' : '지하철' }} {{ [...new Set(step.vehicles)].join(' · ') }}</strong>
          <p v-if="step.type !== 'WALKING' && step.stops.length">승차 {{ step.stops[0] }} → 하차 {{ step.stops[step.stops.length - 1] }}</p>
          <p v-else-if="step.stops.length">{{ step.stops[0] }} → {{ step.stops[step.stops.length - 1] }}</p>
          <p class="metrics">{{ travelTime(step.duration_seconds) }} · {{ metres(step.distance_meters) }}</p>
        </div>
      </li>
    </ol>
    <p v-else class="unavailable">{{ transitLegMessage(leg?.status, loading) }} · 일정과 장소는 그대로 유지됩니다.</p>
    <a v-if="leg?.status === 'OK' && leg.landing_url" :href="leg.landing_url" target="_blank" rel="noopener noreferrer">카카오맵에서 보기</a>
  </details>
</template>
<style scoped>
.transit-leg { margin: 12px 0 20px; padding: 14px 18px; border: 1px solid var(--border-color, #d8e5dc); border-radius: 14px; background: var(--card-bg, #f6faf7); color: var(--text-primary, #263b30); }
summary { cursor: pointer; display: grid; gap: 7px; position: relative; padding-right: 24px; list-style: none; }
summary::-webkit-details-marker { display: none; }
summary::after { content: '+'; position: absolute; right: 0; top: 0; }
details[open] > summary::after { content: '−'; }
summary:focus-visible { outline: 2px solid #39785b; outline-offset: 5px; border-radius: 6px; }
.journey { font-size: .88rem; } summary > strong { font-size: 1.2rem; }
.metrics, .expand { font-size: .8rem; color: var(--text-secondary, #596762); }
.buses { color: #286546; font-weight: 600; font-size: .85rem; }
.steps { list-style: none; padding: 0; margin: 18px 0 0; }
.transit-leg .steps { display: flex; flex-direction: column; gap: 0; }
.transit-leg .steps li { width: 100%; box-sizing: border-box; flex: none; }
.transit-leg .metrics { display: block; width: auto; max-width: none; }
.transit-leg summary { align-items: start; grid-template-columns: 1fr; }
.transit-leg summary > * { margin: 0; width: auto; }
.steps li { position: relative; display: flex; gap: 14px; padding-bottom: 20px; }
.steps li:not(:last-child)::before { content: ''; position: absolute; left: 14px; top: 29px; bottom: 0; border-left: 2px solid #c9dbcf; }
.icon { width: 30px; height: 30px; display: grid; place-items: center; border-radius: 50%; background: #e3eee7; flex-shrink: 0; }
p { margin: 6px 0; } a, .unavailable { font-size: .85rem; }
</style>
