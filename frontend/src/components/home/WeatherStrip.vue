<script setup lang="ts">
/**
 * 메인 날씨 띠 - 회색 상자 7개 대신 한 줄(7일 칸). 제목·출처·오늘 요약은 부모의 구간 제목이 맡는다(2026-09-18 사용자 요청: 다른 구간과 같은 제목 모양).
 * 아이콘은 지도가 쓰는 선 아이콘(weatherIconSvg)으로 통일한다(이모지 금지 - 2026-09-18 메인 시안).
 * 값은 WeatherService 가 준 그대로만 보여준다. 없으면 '-'.
 */
import type { DailyWeather } from '../../services/WeatherService'
import { weatherIconSvg } from '../../utils/crowd.js'

defineProps<{ days: DailyWeather[] }>()

/** 하늘 문구 → 아이콘 종류. 중기예보는 "흐리고 비" 같은 자유 문장이라 포함 여부로 본다 */
function kindOf (d: DailyWeather): string {
  const s = d.sky ?? ''
  if (s.includes('눈')) return '눈'
  if (s.includes('비') || s.includes('소나기') || (d.rainProb ?? 0) >= 60) return '비'
  if (s.includes('맑음')) return '맑음'
  return '구름'
}
const icon = (d: DailyWeather) => weatherIconSvg(kindOf(d), 36)   // 24 → 36 (2026-09-19 사용자: 날씨 구간이 작다)
const temp = (d: DailyWeather) => (d.temperature === null ? '-' : `${d.temperature}°`)
const note = (d: DailyWeather) => [d.sky, d.rainProb ? `${d.rainProb}%` : null].filter(Boolean).join(' ')
</script>

<template>
  <div class="wx" role="group" aria-label="제주 일주일 날씨">
    <div class="wx-days">
      <div v-for="d in days" :key="d.day" class="wx-day">
        <small>{{ d.day }}</small>
        <!-- 우리 코드가 만든 SVG 문자열 - 사용자 입력이 아니다 -->
        <span class="wx-ic" v-html="icon(d)" />
        <strong>{{ temp(d) }}</strong>
        <small>{{ note(d) || '-' }}</small>
      </div>
    </div>
  </div>
</template>

<style scoped>
/* 크기 - 2026-09-19 사용자 요청으로 한 단계 키움: 칸 여백 16 → 26px, 날짜 11.5 → 13.5px, 아이콘 24 → 36px, 기온 17 → 26px, 하늘 문구 11.5 → 13px */
.wx{padding:26px 12px;border:1px solid var(--border);border-radius:18px;background:var(--surface)}
.wx-days{display:grid;grid-template-columns:repeat(7,minmax(0,1fr))}
.wx-day{display:flex;flex-direction:column;align-items:center;gap:6px;text-align:center}
.wx-day small{font-size:13px;color:var(--sub);font-weight:600;white-space:nowrap}
.wx-day small:first-child{font-size:13.5px;font-weight:700}
.wx-day strong{font-size:26px;font-weight:800;letter-spacing:-.02em;line-height:1.1}
.wx-ic{line-height:0}
@media (max-width:767px){
  .wx{padding:16px 12px;border-radius:16px}
  .wx-days{display:flex;gap:6px;overflow-x:auto;scrollbar-width:none;padding-bottom:2px}
  .wx-days::-webkit-scrollbar{display:none}
  .wx-day{flex:0 0 88px;gap:4px}
  .wx-day small{font-size:12px}
  .wx-day small:first-child{font-size:12.5px}
  .wx-day strong{font-size:22px}
}
</style>
