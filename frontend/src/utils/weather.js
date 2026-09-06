import { at, iso } from './date'
import WeatherService from '../services/map/WeatherService'

/* MAP-05: 기상청 실데이터(7일). 로드는 loadPlaces()가 한다 - 범위 밖·실패면 null */
export function wxOf(i) {
  return WeatherService.byDate(iso(at(i)))
}

/* 날씨 아이콘 — 텍스트 글자(☀☁☂)는 브라우저마다 다르게 그려져서 SVG로 직접 그린다.
   구름엔 해를 살짝 겹쳐 '흐리지만 비는 아님'을, 비엔 또렷한 빗방울을 그린다 (2026-09-03 입체 리디자인) */
const CLOUD = 'M6.4 18.4h11.1a4.6 4.6 0 0 0 .3-9.2A6.6 6.6 0 0 0 5.5 10.3a4.1 4.1 0 0 0 .9 8.1Z'
/* 그라데이션 id 는 문서 전역 공유지만 정의가 전부 같아 어느 것이 잡혀도 결과가 같다 */
const DEFS = `<defs>
  <linearGradient id="wxg-sun" x1="0" y1="0" x2="0" y2="1"><stop offset="0" stop-color="#FFCE45"/><stop offset="1" stop-color="#FF9A1F"/></linearGradient>
  <linearGradient id="wxg-cloud" x1="0" y1="0" x2="0" y2="1"><stop offset="0" stop-color="#F7FAFD"/><stop offset="1" stop-color="#C3CFDB"/></linearGradient>
  <linearGradient id="wxg-dark" x1="0" y1="0" x2="0" y2="1"><stop offset="0" stop-color="#ADBDCB"/><stop offset="1" stop-color="#7E93A7"/></linearGradient>
</defs>`

export function wxIcon(k, z = 26) {
  const o = `<svg class="wxi" viewBox="0 0 24 24" width="${z}" height="${z}" aria-hidden="true">${DEFS}`
  if (k === '맑음') return o + `<g stroke="#FFB020" stroke-width="2.1" stroke-linecap="round">
    <path d="M12 1.7v2.4M12 19.9v2.4M1.7 12h2.4M19.9 12h2.4
    M4.7 4.7l1.7 1.7M17.6 17.6l1.7 1.7M4.7 19.3l1.7-1.7M17.6 6.4l1.7-1.7"/></g>
    <circle cx="12" cy="12" r="4.9" fill="url(#wxg-sun)"/></svg>`
  if (k === '구름') return o + `<circle cx="16.6" cy="7.4" r="3.4" fill="url(#wxg-sun)"/>
    <g stroke="#FFB020" stroke-width="1.5" stroke-linecap="round">
    <path d="M16.6 2.2v1.4M21.2 7.4h1.4M19.9 4.1l1-1"/></g>
    <path d="${CLOUD}" fill="url(#wxg-cloud)" stroke="#B9C6D3" stroke-width=".5"/></svg>`
  return o + `<g transform="translate(0,-2.6)"><path d="${CLOUD}" fill="url(#wxg-dark)"/></g>
    <g fill="#2F93E0">
    <path d="M8 16.4c.9 1.3 1.3 2.1 1.3 2.7a1.3 1.3 0 1 1-2.6 0c0-.6.4-1.4 1.3-2.7Z"/>
    <path d="M12 17.4c.9 1.3 1.3 2.1 1.3 2.7a1.3 1.3 0 1 1-2.6 0c0-.6.4-1.4 1.3-2.7Z"/>
    <path d="M16 16.4c.9 1.3 1.3 2.1 1.3 2.7a1.3 1.3 0 1 1-2.6 0c0-.6.4-1.4 1.3-2.7Z"/></g></svg>`
}
