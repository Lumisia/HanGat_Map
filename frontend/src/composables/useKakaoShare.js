/**
 * 카카오톡 공유 (Kakao JS SDK v2) — 장소 카드를 카톡으로 보낸다.
 *
 * 지도와 같은 JavaScript 키(.env VITE_KAKAO_MAP_KEY)를 쓴다 - 카카오 개발자
 * 콘솔에 등록된 도메인에서만 동작한다. SDK는 t1.kakaocdn.net에서 내려오고
 * 공유창은 sharer.kakao.com으로 폼 전송이라, vite.config.js CSP의
 * script-src / form-action 에 두 출처가 열려 있어야 한다.
 *
 * ⚠️ sendDefault 는 팝업(window.open)을 여는데, 브라우저는 클릭 직후의 동기
 *    호출만 신뢰한다. SDK 로드를 await 한 뒤 열면 사용자 활성화가 소진돼
 *    빈 about:blank 팝업이 뜬다(실제로 겪은 버그). 그래서 시트를 열 때
 *    preloadKakao()로 미리 받아두고, 클릭 시점엔 동기로 sendDefault 를 부른다.
 */
const SDK_URL = 'https://t1.kakaocdn.net/kakao_js_sdk/2.7.4/kakao.min.js'
const KEY = import.meta.env.VITE_KAKAO_MAP_KEY

let pending = null
function loadSdk () {
  if (window.Kakao?.Share) return Promise.resolve(window.Kakao)
  if (pending) return pending
  pending = new Promise((resolve, reject) => {
    if (!KEY) return reject(new Error('VITE_KAKAO_MAP_KEY가 비어 있어요 (.env 확인)'))
    const el = document.createElement('script')
    el.src = SDK_URL
    el.onload = () => resolve(window.Kakao)
    el.onerror = () => { pending = null; reject(new Error('카카오 SDK 로드 실패')) }
    document.head.appendChild(el)
  })
  return pending
}

/** SDK가 준비돼 바로 공유 가능한지 (팝업을 동기로 열 수 있는지) */
export const kakaoReady = () => !!window.Kakao?.Share && window.Kakao.isInitialized()

/** 시트를 열 때 미리 호출 — 로드·초기화까지 끝내둔다. 실패해도 조용히 넘긴다(클릭 시 폴백) */
export async function preloadKakao () {
  try {
    const Kakao = await loadSdk()
    if (!Kakao.isInitialized()) Kakao.init(KEY)
    return true
  } catch { return false }
}

function payload ({ title, description, imageUrl, url }) {
  const link = { mobileWebUrl: url, webUrl: url }
  return imageUrl
    ? { objectType: 'feed', content: { title, description, imageUrl, link }, buttons: [{ title: '지도에서 보기', link }] }
    : { objectType: 'text', text: `${title}\n${description}`, link, buttonTitle: '지도에서 보기' }
}

/**
 * 공유창을 연다. 반드시 클릭 핸들러에서 **동기로** 부를 것 —
 * preloadKakao()가 끝나 있으면 팝업이 클릭 활성화 안에서 열려 정상 동작한다.
 * @returns {boolean} 준비 안 돼 못 열었으면 false (호출부가 안내)
 */
export function shareToKakao (opts) {
  if (!kakaoReady()) return false
  window.Kakao.Share.sendDefault(payload(opts))
  return true
}
