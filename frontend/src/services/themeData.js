/**
 * 테마 페이지 둘(목록 /themes · 상세 /themes/:key)이 같은 네 레이어(관광지·식당·착한가격·숙소)를 쓴다.
 * 세션 안에서 한 번만 받아 두고 나눠 쓴다 - 목록에서 상세로 갈 때마다 45KB 관광지 목록을 다시 받지 않게.
 * 카페·편의점·마트(소상공인 상가 5천여 곳)는 소개글·사진이 없어 테마에 넣지 않는다.
 * 레이어 하나라도 못 받았으면 기억하지 않아 다음 호출이 다시 시도한다.
 */
import MapPlaceService from './map/MapPlaceService'

export const THEME_LAYERS = ['spot', 'dine', 'food', 'stay']
const NAMES = { spot: '관광지', dine: '식당', food: '착한가격', stay: '숙소' }

let cache = null

/** @returns {Promise<{ layers: Record<string, object[]>, failed: string[] }>} failed = 못 받은 레이어의 한글 이름 */
export async function loadThemeLayers () {
  if (cache) return cache
  const results = await Promise.allSettled(THEME_LAYERS.map(k => MapPlaceService.getLayer(k)))
  const layers = {}
  const failed = []
  results.forEach((r, i) => {
    if (r.status === 'fulfilled' && r.value) layers[THEME_LAYERS[i]] = r.value
    else failed.push(NAMES[THEME_LAYERS[i]])
  })
  const out = { layers, failed }
  if (!failed.length) cache = out
  return out
}

/** 테스트·재시도용 */
export function resetThemeLayers () { cache = null }
