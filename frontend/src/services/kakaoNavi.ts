type Destination = { placeName: string; latitude: number | null; longitude: number | null }
export interface NaviSdk {
  isInitialized(): boolean
  Navi?: { start(options: { name: string; x: number; y: number; coordType: 'wgs84' }): void }
}

export function isNaviMobile(userAgent: string, maxTouchPoints = 0) {
  return /Android|iPhone|iPad|iPod/i.test(userAgent)
    || (/Macintosh/i.test(userAgent) && maxTouchPoints > 1)
}

export function hasNaviCoordinates(place: Destination) {
  return place.latitude != null && place.longitude != null
    && Number.isFinite(place.latitude) && Number.isFinite(place.longitude)
    && Math.abs(place.latitude) <= 90 && Math.abs(place.longitude) <= 180
}

/** 사용자 클릭 안에서 동기 호출해야 앱 전환이 차단되지 않는다. */
export function startNavi(place: Destination, mobile: boolean, sdk?: NaviSdk) {
  if (!mobile || !hasNaviCoordinates(place) || !sdk?.isInitialized() || !sdk.Navi) return false
  try {
    sdk.Navi.start({ name: place.placeName, x: place.longitude!, y: place.latitude!, coordType: 'wgs84' })
    return true
  } catch { return false }
}
