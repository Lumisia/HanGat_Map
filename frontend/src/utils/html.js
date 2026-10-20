/**
 * innerHTML 에 문자열을 끼워 넣기 전 HTML 특수문자 5개(& < > " ')를 엔티티로 바꾼다 (최종점검 #32).
 * 지도 오버레이(MapCanvas)의 장소명·메뉴·읍면 이름은 공공데이터와 저장 코스(코스 만들 때 보낸 이름이 places 에 그대로 저장됨)에서
 * 오므로 마크업이 섞여 올 수 있다 - 글자로만 보이게 한다. 풀 핀 이름표는 textContent 라 이걸 거치지 않는다.
 * null·undefined 는 빈 문자열(이름표에 'undefined' 가 찍히지 않게), 숫자는 문자열로.
 */
const ENT = { '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' }

export const escapeHtml = s => String(s ?? '').replace(/[&<>"']/g, ch => ENT[ch])
