/**
 * 마이페이지 찜 탭(MY_006·007) 데이터 - 백엔드 `/favorites` 실데이터.
 *
 * 목업(`api/mypage.js`의 listFavorites)과 같은 필드명으로 바꿔 돌려주므로 FavoritesTab 이 거의 그대로 돈다.
 * 목업은 지우지 않았다 - 다른 탭·스펙이 아직 쓴다. 이 파일은 지도 담당(이후경)이 만들었고,
 * 찜 토글은 지도 쪽 `services/map/FavoriteApiService.ts` 와 같은 엔드포인트를 쓴다.
 *
 * 실데이터와 목업의 차이(탭이 알아야 하는 것):
 *  - 입장료는 숫자가 아니라 원문(feeText)+무료 여부(free)
 *  - 운영시간은 자유 텍스트(hoursText). "09:00~18:00" 꼴만 hours{open,close} 로 풀어 운영 중/종료 판정에 쓴다
 *  - 날씨는 장소별이 아니라 제주 오늘 날씨 하나. 못 받으면 null (배지를 숨긴다)
 *  - 혼잡은 오늘 집중률(crowd). 예보 없는 장소는 null → '정보 없음'
 */
import { apiRequest } from './backendClient.js'
import { apiGet } from '../services/apiClient'
import { skyToKind } from '../services/map/MapWeatherService'
import { tier } from '../utils/crowd.js'
import { iso } from '../utils/date.js'

export const FAVORITE_SORTS = [
  { key: 'recent', label: '최근 찜한 순' },
  { key: 'name', label: '장소명순' },
  { key: 'category', label: '카테고리순' }
]

/** 개수 조회에는 날씨 등의 부가 요청이 필요하지 않다. */
export async function countFavorites () {
  const rows = await apiRequest('/favorites', { auth: true, sessionBound: true })
  return rows.length
}

/** 찜 목록. 정렬은 목록이 작아(수십 건) 여기서 한다 */
export async function listFavorites ({ sort = 'recent' } = {}) {
  const [rows, weather] = await Promise.all([
    apiRequest('/favorites', { auth: true }),
    todayWeather()
  ])
  const items = rows.map(r => toItem(r, weather))
  sortItems(items, sort)
  return { items, total: items.length }
}

/** 찜 해제 - 멱등. 이미 없어도 성공 */
export function removeFavorite (placeId) {
  return apiRequest(`/favorites/${placeId}`, { method: 'DELETE', auth: true })
}

/** 찜 - 멱등. 이미 있어도 성공 */
export function addFavorite (placeId) {
  return apiRequest(`/favorites/${placeId}`, { method: 'PUT', auth: true })
}

/** 백엔드 FavoriteResponse → 탭이 쓰는 한 줄 */
export function toItem (r, weather = null) {
  const crowd = r.crowdRate == null ? null : Math.round(Number(r.crowdRate))
  return {
    placeId: r.placeId,
    createdAt: r.favoritedAt,
    name: r.name,
    category: r.tagName ?? r.categoryName ?? '정보 없음',
    region: r.regionName,
    addr: r.roadAddress ?? r.lotAddress ?? '주소 정보 없음',
    x: r.longitude,
    y: r.latitude,
    park: r.parkingAvailable,
    toilet: r.toiletAvailable,
    /** 실내 여부는 수집 항목이 아니다 - 배지를 그리지 않게 false */
    indoor: false,
    hours: parseHours(r.operatingHoursText),
    hoursText: r.operatingHoursText ?? null,
    feeText: r.useFeeText ?? null,
    free: !!r.free,
    /** 목업 호환 - 실데이터엔 숫자 요금이 없다 */
    fee: null,
    crowd,
    /** 상세 패널과 같은 규칙 - 예보가 있거나 관광지면 점(없으면 회색), 예보 없는 식당·카페·숙소는 점 자체를 생략(null) */
    crowdTier: crowd != null || r.categoryCode === 'TOURIST' ? tier(crowd) : null,
    weather,
    rating: r.ratingAvg == null ? null : Number(r.ratingAvg),
    reviewCount: r.reviewCount ?? 0,
    businessStatus: r.businessStatus,
    /** 폐업(CLOSED) - 목록·검색에선 빠지지만 찜은 남기고 '폐업'으로 표시한다 */
    closed: r.businessStatus === 'CLOSED',
    imageUrl: r.imageUrl ?? null
  }
}

/**
 * "09:00~18:00" / "09:00 - 18:00" 꼴만 {open, close} 로. 계절별·괄호 설명이 붙은 원문(관광지의 83%)은 null -
 * 탭이 원문을 그대로 보여준다. 억지로 첫 시각만 뽑으면 "하절기 09:00" 을 연중 운영시간처럼 말하게 된다.
 */
export function parseHours (text) {
  const m = /^(\d{2}:\d{2})\s*[~\-–]\s*(\d{2}:\d{2})$/.exec((text ?? '').trim())
  return m ? { open: m[1], close: m[2] } : null
}

/** recent = 찜한 시각 내림차순(백엔드 순서), name / category = 한국어 사전순 */
export function sortItems (items, sort) {
  const ko = (a, b) => a.localeCompare(b, 'ko')
  if (sort === 'name') items.sort((a, b) => ko(a.name, b.name))
  else if (sort === 'category') items.sort((a, b) => ko(a.category, b.category) || ko(a.name, b.name))
  else items.sort((a, b) => String(b.createdAt ?? '').localeCompare(String(a.createdAt ?? '')))
  return items
}

/** 제주 오늘 날씨 {kind, t}. 공개 API 라 토큰 없이. 느리거나 실패하면 null - 목록을 막지 않는다(4초 상한) */
async function todayWeather () {
  try {
    const rows = await apiGet('/main/weather', 4000)
    const today = rows.find(r => r.date === iso(new Date()))
    const kind = skyToKind(today?.sky ?? null)
    return kind && today?.maxTemp != null ? { kind, t: today.maxTemp } : null
  } catch {
    return null
  }
}
