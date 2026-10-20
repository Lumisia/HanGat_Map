/**
 * 지도가 제주를 벗어나지 못하게 하는 경계 (MAP_001).
 *
 * 카카오 지도에는 Mapbox 의 maxBounds 같은 옵션이 없어서, 드래그가 끝날 때 중심을 이 상자 안으로 되돌린다.
 * 상자는 지도 '중심'이 갈 수 있는 범위다 - 본섬(위도 33.19~33.57, 경도 126.15~126.95)보다 조금 넓게 잡아
 * 우도·가파도·마라도를 중앙 가까이 둘 수 있되, 경계에 닿아도 섬이 화면 절반 이상 남는다(2026-09-11 실측으로 조정).
 * 추자도(33.95)는 권역 밖(장소 적재에서도 제외)이라 일부러 안 넣었다.
 */
export const JEJU_BOUNDS = Object.freeze({ south: 33.10, north: 33.62, west: 126.10, east: 127.00 })

/** 가장 축소했을 때도 제주가 화면의 주인공이어야 한다 - 10이 섬 전체, 11은 한 단계 여유. 12부터 남해안이 들어온다 */
export const JEJU_MAX_LEVEL = 11

/** 중심 좌표를 상자 안으로 자른다. 안에 있으면 같은 값을 돌려준다 */
export function clampToJeju (lat, lng, b = JEJU_BOUNDS) {
  return {
    lat: Math.min(b.north, Math.max(b.south, lat)),
    lng: Math.min(b.east, Math.max(b.west, lng))
  }
}
