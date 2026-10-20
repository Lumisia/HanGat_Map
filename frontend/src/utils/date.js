import { shallowRef } from 'vue'

/* 예보 시작일 = 오늘 0시. 페이지를 연 뒤 자정을 넘기면 refreshToday() 가 오늘로 옮긴다(최종점검 #16) -
   상수(D0)로 두면 다음 날 지도에 다시 들어왔을 때 글자·달력은 어제, 핀 색·순위는 오늘이 됐다.
   반응형(shallowRef)이라 today()/at() 을 읽는 computed 는 날짜가 옮겨지면 다시 계산된다 */
const todayStart = () => { const d = new Date(); d.setHours(0, 0, 0, 0); return d }
const d0 = shallowRef(todayStart())

/** 오늘 0시(Date) - 예보 오프셋 0 의 기준. 옛 이름 D0 */
export const today = () => d0.value

/** 날짜가 바뀌었으면 오늘로 옮기고 true. 지도 진입(loadPlaces) 때 부른다 */
export function refreshToday () {
  const t = todayStart()
  if (t.getTime() === d0.value.getTime()) return false
  d0.value = t
  return true
}

/** 오늘로부터 i일 뒤 */
export const at = i => { const d = new Date(d0.value); d.setDate(d.getDate() + i); return d }

export const iso = d =>
  `${d.getFullYear()}-${String(d.getMonth() + 1).padStart(2, '0')}-${String(d.getDate()).padStart(2, '0')}`

/** 8/17 (월) */
export const fmt = d => `${d.getMonth() + 1}/${d.getDate()} (${'일월화수목금토'[d.getDay()]})`

/** 8월 17일 */
export const fmtK = d => `${d.getMonth() + 1}월 ${d.getDate()}일`

/** n일 전 날짜 (샘플 후기의 방문일 표기용) */
export const ago = n => { const d = new Date(d0.value); d.setDate(d.getDate() - n); return `${d.getMonth() + 1}/${d.getDate()}` }

/** 연·월을 하나의 정수로 — 달력의 이동 가능 범위 비교용 */
export const monthKey = d => d.getFullYear() * 12 + d.getMonth()

/** 예보 범위: 오늘(0) ~ 29일 뒤 */
export const FORECAST_DAYS = 30
