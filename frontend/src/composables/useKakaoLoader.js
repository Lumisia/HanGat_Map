/* 카카오맵 SDK 로더.
   index.html에 <script>를 박지 않고 필요할 때 한 번만 불러온다 —
   지도 페이지에 들어가지 않는 사용자는 SDK를 받지 않는다.

   ★ 두 가지가 모두 갖춰져야 로드된다
     1) 콘솔 > 앱 > 플랫폼 키 > JavaScript 키 > SDK 도메인에 현재 주소(origin)가 등록돼 있을 것
     2) 콘솔 > 제품 설정 > 카카오맵이 ON일 것 (없으면 disabled OPEN_MAP_AND_LOCAL service 오류)
   file:// 로 열면 origin이 없어 항상 실패한다 */

const KEY = import.meta.env.VITE_KAKAO_MAP_KEY
/** 이 시간 안에 SDK 가 준비되지 않으면 실패로 본다 - 코스용 로더(KakaoMapLoader.ts)와 같은 값 */
export const TIMEOUT_MS = 8000
let loading = null

export function loadKakaoMap() {
  if (window.kakao?.maps?.Map) return Promise.resolve(window.kakao)
  if (loading) return loading

  loading = new Promise((resolve, reject) => {
    if (!KEY) return reject(new Error('VITE_KAKAO_MAP_KEY가 비어 있어요 (.env 확인)'))
    const el = document.createElement('script')
    // libraries=services 는 지도 자체엔 필요 없지만 AI코스의 장소·숙소 검색(kakaoPlaceSearchService)이 쓴다.
    // SDK 는 한 페이지에 한 번만 붙고 코스용 로더(KakaoMapLoader.ts)는 window.kakao.maps 가 있으면 다시 안 부르므로,
    // 지도를 먼저 연 사용자가 AI코스로 가면 services 없는 SDK 만 남아 검색이 세션 내내 실패했다(2026-09-13, 최종점검 #14)
    el.src = `https://dapi.kakao.com/v2/maps/sdk.js?appkey=${KEY}&autoload=false&libraries=services`
    el.async = true
    // 서버가 성공도 실패도 답하지 않고 멈추면(느린 회선) onload·onerror 어느 쪽도 오지 않는다 -
    // 안내 없이 빈 회색 지도로 영원히 남지 않게 시간을 끊는다(최종점검 #50)
    const timer = setTimeout(() => { el.remove(); reject(new Error('카카오맵 서버가 응답하지 않아요')) }, TIMEOUT_MS)
    el.onload = () => window.kakao.maps.load(() => { clearTimeout(timer); resolve(window.kakao) })
    el.onerror = () => { clearTimeout(timer); el.remove(); reject(new Error('카카오맵 SDK를 불러오지 못했어요')) }
    document.head.appendChild(el)
  })
  // 실패한 약속은 기억하지 않는다 - 그대로 두면 신호가 돌아와도 다음 진입에 다시 시도조차 안 하고
  // 새로고침 전까지 실패 화면만 보였다(최종점검 #50). 성공은 window.kakao.maps.Map 이 있으니 기억할 필요가 없다
  loading.catch(() => { loading = null })
  return loading
}
