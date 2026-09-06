/**
 * 혼잡 예보 · 날씨 목(mock).
 *
 * 원본 index.html:503~528 의 hash / crowd / tier / wxOf 를 **수식 그대로** 옮겼다.
 * 원본은 기준일(D0=2026-07-20) 로부터의 오프셋 i 를 받지만,
 * 실제 계산은 iso(날짜) 문자열에만 의존하므로 날짜 객체를 직접 받도록만 바꿨다.
 * → 같은 날짜면 원본과 동일한 값이 나온다. (src/utils/crowd.spec.js 로 검증)
 *
 * ⚠️ 이 값은 실제 예보가 아니라 결정적(deterministic) 샘플이다.
 *    실서비스에서는 한국관광공사 집중률 예측 API(15128555)로 대체된다.
 */
import { iso, toDate } from './format.js'

/** 원본 :503 — FNV-1a 변형 */
export function hash (s) {
  let h = 2166136261
  for (let i = 0; i < s.length; i++) {
    h ^= s.charCodeAt(i)
    h = Math.imul(h, 16777619)
  }
  return Math.abs(h)
}

/**
 * 원본 :504~506
 * @param {{n:string,b:number}} place  장소 (n=이름, b=기본 집중률)
 * @param {Date|string} date
 * @returns {number} 6~99
 */
export function crowdOn (place, date) {
  const d = toDate(date)
  const w = d.getDay()
  const wk = w === 6 ? 26 : w === 0 ? 19 : w === 5 ? 11 : 0
  return Math.max(6, Math.min(99, Math.round(place.b + wk + (hash(place.n + iso(d)) % 19) - 9)))
}

/** 원본 :507 */
export const tier = c => (c == null ? 'none' : c < 40 ? 'calm' : c < 70 ? 'mid' : 'busy')

/** 원본 :511 */
export const tierKo = c => (c == null ? '정보 없음' : c < 40 ? '한산' : c < 70 ? '보통' : '혼잡')

/** 원본 :526~528 */
export function weatherOn (date) {
  const key = iso(date)
  const h = hash('wx' + key) % 100
  const t = 28 + (hash('t' + key) % 6)
  const w = h < 52 ? { k: '맑음', rain: 0 } : h < 80 ? { k: '구름', rain: 0 } : { k: '비', rain: 1 }
  const top = w.rain ? t - 3 : t
  return { ...w, t: top, tmin: top - 4 }
}

/** 원본 :529~531 — 하버사인 (km) */
export function dist (a, b) {
  const R = 6371
  const p = Math.PI / 180
  const dy = (b.y - a.y) * p
  const dx = (b.x - a.x) * p
  const q = Math.sin(dy / 2) ** 2 + Math.cos(a.y * p) * Math.cos(b.y * p) * Math.sin(dx / 2) ** 2
  return 2 * R * Math.asin(Math.sqrt(q))
}

/** 원본 :532 — 평균 38km/h 기준 이동 분 */
export const drive = (a, b) => Math.max(5, Math.round((dist(a, b) / 38) * 60))

/** 코스 전체의 평균 혼잡 (일자별 계산) */
export function courseAvgCrowd (course, placeIndex) {
  const vals = []
  ;(course.days || []).forEach(day => {
    day.stops.forEach(stop => {
      const p = placeIndex[stop.placeId]
      if (p) vals.push(crowdOn(p, day.date))
    })
  })
  if (!vals.length) return null
  return Math.round(vals.reduce((a, b) => a + b, 0) / vals.length)
}

/** 날씨 아이콘 SVG — 원본 :512~524 그대로 (문자 대신 SVG를 쓰는 이유는 원본 주석 참고) */
const CLOUD = 'M6.4 18.4h11.1a4.6 4.6 0 0 0 .3-9.2A6.6 6.6 0 0 0 5.5 10.3a4.1 4.1 0 0 0 .9 8.1Z'

export function weatherIconSvg (kind, size = 26) {
  const o = `<svg viewBox="0 0 24 24" width="${size}" height="${size}" aria-hidden="true" style="vertical-align:-4px">`
  if (kind === '맑음') {
    return o + `<circle cx="12" cy="12" r="4.9" fill="#FFAE1A"/>
    <g stroke="#FFAE1A" stroke-width="2.1" stroke-linecap="round">
    <path d="M12 1.7v2.4M12 19.9v2.4M1.7 12h2.4M19.9 12h2.4
    M4.7 4.7l1.7 1.7M17.6 17.6l1.7 1.7M4.7 19.3l1.7-1.7M17.6 6.4l1.7-1.7"/></g></svg>`
  }
  if (kind === '구름') return o + `<path d="${CLOUD}" fill="#AFC0D0"/></svg>`
  return o + `<g transform="translate(0,-2.2)"><path d="${CLOUD}" fill="#93A8BC"/></g>
    <g stroke="#3FA0E4" stroke-width="2.2" stroke-linecap="round">
    <path d="M8.2 18.6l-1 3M12 18.6l-1 3M15.8 18.6l-1 3"/></g></svg>`
}

/* ── 지도 화면(D)에서 쓰던 API ─────────────────────────────────────────
   통합(2026-08-17). 이후경님 `utils/crowd.js` 의 함수들을 여기로 합쳤다.
   D 는 "오늘로부터 i일 뒤" 오프셋으로, A 는 Date 로 예보를 조회한다.
   수식이 같으므로(둘 다 원본 index.html:504~506 포팅) crowdOn 위에 얇게 얹는다. */
import { at } from './date.js'

/**
 * 장소 s 의 i일 뒤 집중률.
 *
 * 값의 출처가 둘이고 실데이터가 우선이다.
 * 1. `s.series` — 관광공사 집중률 실측 (백엔드 `/crowd/forecast`). 실서비스 경로
 * 2. `s.b` — 하드코딩 샘플에서 해시로 만든 값. 백엔드가 죽었을 때 화면을 유지하는 폴백 전용
 *
 * @param {{n:string, b:number|null, series:(number|null)[]|null}} s
 * @param {number} i  오늘(0)로부터의 일 오프셋
 * @returns {number|null} 집중률, 예보 미제공이면 null
 */
export function crowd (s, i) {
  // 실측 예보 일수(21~22일)가 화면 30일보다 짧다 — 범위를 넘으면 undefined라 null로 떨어진다
  if (s.series) return s.series[i] ?? null
  // 예보 미제공 장소는 한산으로 취급하지 않는다 — tier() 가 'none'(회색)을 낸다 (MAP_004)
  if (s.b == null) return null
  return crowdOn(s, at(i))
}

/** 혼잡 단계의 한국어 라벨. 리뷰의 혼잡 제보 선택지에 쓰인다 (MAP_008) */
export const CROWD_KO = { calm: '한산', mid: '보통', busy: '혼잡' }

/** 그 장소의 30일 예보 중 i일이 몇 번째로 한산한가 (1 = 가장 한산). 예보 없는 날은 순위에서 뺀다 */
export function rank30 (s, i) {
  const c = crowd(s, i)
  if (c == null) return null
  let r = 1
  for (let k = 0; k < 30; k++) {
    const v = crowd(s, k)
    if (v != null && v < c) r++
  }
  return r
}

/**
 * [from, from+span) 구간에서 가장 한산한 날. 30일을 넘지 않는다.
 * 예보 없는 날(null)은 후보에서 뺀다 - JS 는 null < 숫자를 0 < 숫자로 계산해서,
 * 안 거르면 예보 창 밖(끝자락) 날짜가 "가장 한산한 날"로 뽑혀 회색 화면으로 안내한다.
 * 구간 전체가 null 이면 c: null - 호출부가 팁을 숨기는 근거
 */
export function bestDay (s, from = 0, span = 30) {
  let best = { k: from, c: null }
  for (let k = from; k < Math.min(30, from + span); k++) {
    const c = crowd(s, k)
    if (c != null && (best.c == null || c < best.c)) best = { k, c }
  }
  return best
}
