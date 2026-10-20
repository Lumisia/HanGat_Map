<script setup lang="ts">
import { computed } from 'vue'
import type { CourseItem } from '../../assets/types/course'
import { dayWeatherBadges } from '../../services/course/dailyWeather'
const props = defineProps<{ items: CourseItem[] }>()
const forecasts = computed(() => dayWeatherBadges(props.items))
const paths: Record<string, string> = {
  sun: 'M12 3v2m0 14v2M3 12h2m14 0h2M5.6 5.6 7 7m10 10 1.4 1.4M5.6 18.4 7 17M17 7l1.4-1.4M16 12a4 4 0 1 1-8 0 4 4 0 0 1 8 0',
  cloud: 'M6 18a4 4 0 0 1-1-7.9 6 6 0 0 1 11.6-1.6A4.8 4.8 0 1 1 18 18Z',
  rain: 'M5 15a4 4 0 0 1 0-8 6 6 0 0 1 11-1 4.5 4.5 0 0 1 2 9M8 17l-1 3m6-3-1 3m6-3-1 3',
  snow: 'M12 2v20M3.3 7l17.4 10M3.3 17 20.7 7M9 4l3 3 3-3M9 20l3-3 3 3',
}
</script>
<template>
  <div class="weather-badges">
    <div v-for="forecast in forecasts" :key="forecast.key" class="weather-group">
      <small v-if="forecasts.length > 1 || forecast.scope === '제주 전역'">{{ forecast.scope }}</small>
      <span class="weather-state" :aria-label="forecast.state"><svg viewBox="0 0 24 24" aria-hidden="true"><path :d="paths[forecast.icon]" /></svg>{{ forecast.state }}</span>
      <span v-if="forecast.temperature" class="temperature" :aria-label="`기온 ${forecast.temperature}`"><svg viewBox="0 0 24 24" aria-hidden="true"><path d="M9 14.5V5a3 3 0 0 1 6 0v9.5a5 5 0 1 1-6 0ZM12 8v10" /></svg>{{ forecast.temperature }}</span>
      <span v-if="forecast.rain" class="rain" :aria-label="`강수확률 ${forecast.rain}`"><svg viewBox="0 0 24 24" aria-hidden="true"><path d="M12 3S5 11 5 15a7 7 0 0 0 14 0c0-4-7-12-7-12Z" /></svg>{{ forecast.rain }}</span>
      <details><summary aria-label="날씨 예보 출처와 발표 시각">ⓘ</summary><p>{{ forecast.detail }}</p></details>
    </div>
  </div>
</template>
<style scoped>
.weather-badges,.weather-group{display:flex;align-items:center;flex-wrap:wrap;gap:6px}.weather-group{position:relative;font-size:11px}.weather-group>span{display:inline-flex;align-items:center;gap:4px;padding:4px 7px;border-radius:20px;background:var(--course-accent-bg);color:var(--course-accent-dark);white-space:nowrap}.weather-group .temperature{background:var(--mid-bg);color:var(--mid)}.weather-group .rain{background:var(--ai-bg);color:var(--ai-tx)}svg{width:14px;height:14px;fill:none;stroke:currentColor;stroke-width:1.6;stroke-linecap:round;stroke-linejoin:round}summary{cursor:pointer;color:var(--course-text-3);list-style:none;padding:4px}details p{position:absolute;right:0;top:100%;z-index:3;width:min(280px,70vw);padding:12px;background:var(--course-surface);border:1px solid var(--course-line);border-radius:10px;box-shadow:0 4px 12px #0001;line-height:1.7}small{color:var(--course-text-3)}
</style>
