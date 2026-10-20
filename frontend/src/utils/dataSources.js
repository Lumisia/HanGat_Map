/**
 * 지도 화면 하단 출처 표기 (MAP_001) - 공공데이터 이용조건이자 심사 확인 항목.
 *
 * 켜진 레이어 기준으로 그 화면에 실제로 보이는 원천만, 기관명만 적는다(괄호 설명은 줄이 길어져 뺌 - 2026-09-11).
 * 관광공사는 공모전 공식 형식 그대로 - `출처: ⓒ한국관광공사`(O) / `TourAPI` 단독(X), 로고 금지·텍스트만(2026 공지 FAQ).
 * 관광지·식당·숙소 핀과 혼잡 색은 항상 있으니 관광공사·기상청은 고정,
 * 착한가격(행안부)·카페/편의점/마트(소상공인시장진흥공단)는 그 레이어가 켜졌을 때만.
 */
const KTO = 'ⓒ한국관광공사'
const KMA = '기상청'
const MOIS = '행정안전부'
const SBIZ = '소상공인시장진흥공단'

/** @param {Record<string, number|boolean>} layers state.L 모양 */
export function sourcesFor (layers) {
  const on = k => !!layers?.[k]
  const parts = [KTO]
  if (on('food')) parts.push(MOIS)
  if (on('cafe') || on('cvs') || on('mart')) parts.push(SBIZ)
  parts.push(KMA)
  return parts
}

export function sourceLine (layers) {
  return `출처: ${sourcesFor(layers).join(' · ')}`
}

/**
 * 착한가격 가격표 밑에 붙는 출처·기준일. 가격은 행안부 CSV 발행일(분기 갱신) 기준이라 날짜 없이는 현재가처럼 읽힌다.
 * 기준일이 응답에 없으면 날짜 부분만 뺀다.
 */
export function goodPriceSourceLine (baseDate) {
  return baseDate ? `출처: 행정안전부 착한가격업소 · ${baseDate} 기준` : '출처: 행정안전부 착한가격업소'
}
