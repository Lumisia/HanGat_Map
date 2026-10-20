import { at, iso } from './date'
import WeatherService from '../services/map/MapWeatherService'

/* MAP-05: 기상청 실데이터(7일). 로드는 loadPlaces()가 한다 - 범위 밖·실패면 null.
   region 은 장소의 권역 표시명(동부/서부/남부/북부) - 안 주면 북부(기존 동작) */
export function wxOf(i, region) {
  return WeatherService.byDate(iso(at(i)), region)
}

/** 기상청 발표 시각(KST ISO) - i 를 주면 그 날 예보의 것, 없으면 그 권역 주간 최근값. 라벨용 - 모르면 null */
export const wxIssuedAt = (region, i) => WeatherService.issuedAt(region, i == null ? undefined : iso(at(i)))

/* 날씨 아이콘 — 텍스트 글자(☀☁☂)는 브라우저마다 다르게 그려져서 SVG로 직접 그린다.
   구름엔 해를 살짝 겹쳐 '흐리지만 비는 아님'을, 비엔 또렷한 빗방울을 그린다 (2026-09-03 입체 리디자인) */
const CLOUD = 'M6.4 18.4h11.1a4.6 4.6 0 0 0 .3-9.2A6.6 6.6 0 0 0 5.5 10.3a4.1 4.1 0 0 0 .9 8.1Z'
/* 그라데이션 id 는 문서 전역이라 아이콘마다 고유 번호를 붙인다 - 같은 이름을 쓰면 브라우저가 문서 맨 앞의 정의 하나만 보는데,
   그게 상세 카드 안에 있어 후기 화면(정보 화면 display:none)으로 넘어가면 코스 패널 아이콘까지 색을 잃었다(최종점검 #39) */
let seq = 0
const defs = id => `<defs>
  <linearGradient id="${id}-sun" x1="0" y1="0" x2="0" y2="1"><stop offset="0" stop-color="#FFCE45"/><stop offset="1" stop-color="#FF9A1F"/></linearGradient>
  <linearGradient id="${id}-cloud" x1="0" y1="0" x2="0" y2="1"><stop offset="0" stop-color="#F7FAFD"/><stop offset="1" stop-color="#C3CFDB"/></linearGradient>
  <linearGradient id="${id}-dark" x1="0" y1="0" x2="0" y2="1"><stop offset="0" stop-color="#ADBDCB"/><stop offset="1" stop-color="#7E93A7"/></linearGradient>
</defs>`

export function wxIcon(k, z = 26) {
  const id = `wxg${++seq}`
  const cloudDark = `<g transform="translate(0,-2.6)"><path d="${CLOUD}" fill="url(#${id}-dark)"/></g>`
  const o = `<svg class="wxi" viewBox="0 0 24 24" width="${z}" height="${z}" aria-hidden="true">${defs(id)}`
  if (k === '맑음') return o + `<g stroke="#FFB020" stroke-width="2.1" stroke-linecap="round">
    <path d="M12 1.7v2.4M12 19.9v2.4M1.7 12h2.4M19.9 12h2.4
    M4.7 4.7l1.7 1.7M17.6 17.6l1.7 1.7M4.7 19.3l1.7-1.7M17.6 6.4l1.7-1.7"/></g>
    <circle cx="12" cy="12" r="4.9" fill="url(#${id}-sun)"/></svg>`
  if (k === '구름') return o + `<circle cx="16.6" cy="7.4" r="3.4" fill="url(#${id}-sun)"/>
    <g stroke="#FFB020" stroke-width="1.5" stroke-linecap="round">
    <path d="M16.6 2.2v1.4M21.2 7.4h1.4M19.9 4.1l1-1"/></g>
    <path d="${CLOUD}" fill="url(#${id}-cloud)" stroke="#B9C6D3" stroke-width=".5"/></svg>`
  // 눈: 구름 없이 눈 결정 하나(팔 여섯 + 잔가지). 전엔 눈도 빗방울이었다(최종점검 #41, 2026-09-14 후경 - 구름 빼고 결정 하나로)
  if (k === '눈') return o + `<g stroke="#5FA8E8" stroke-width="1.6" stroke-linecap="round" stroke-linejoin="round">
    <path d="M12 20L12 4M12 6.7L9.9 5.5M12 6.7L14.1 5.5M12 17.3L14.1 18.5M12 17.3L9.9 18.5M5.1 16L18.9 8M16.6 9.4L16.6 7M16.6 9.4L18.7 10.6M7.4 14.6L7.4 17M7.4 14.6L5.3 13.4M18.9 16L5.1 8M7.4 9.4L5.3 10.6M7.4 9.4L7.4 7M16.6 14.6L18.7 13.4M16.6 14.6L16.6 17"/></g></svg>`
  return o + cloudDark + `
    <g fill="#2F93E0">
    <path d="M8 16.4c.9 1.3 1.3 2.1 1.3 2.7a1.3 1.3 0 1 1-2.6 0c0-.6.4-1.4 1.3-2.7Z"/>
    <path d="M12 17.4c.9 1.3 1.3 2.1 1.3 2.7a1.3 1.3 0 1 1-2.6 0c0-.6.4-1.4 1.3-2.7Z"/>
    <path d="M16 16.4c.9 1.3 1.3 2.1 1.3 2.7a1.3 1.3 0 1 1-2.6 0c0-.6.4-1.4 1.3-2.7Z"/></g></svg>`
}
