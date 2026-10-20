import type { KakaoPlaceSearchResult } from '../assets/types/course'
import { loadKakaoMap } from './map/KakaoMapLoader'
import { mapKakaoPlaceSearchResult, type KakaoPlaceSearchDocument } from './map/KakaoPlaceSearchMapper'

export type KakaoPlaceSearchMode = 'ACCOMMODATION' | 'GENERAL'

export interface KakaoPlaceSearchPage {
  items: KakaoPlaceSearchResult[]
  current_page: number
  last_page: number
  total_count: number
  source: 'KAKAO'
}

const PAGE_SIZE = 5
const JEJU_RECT = '126.08,33.10,126.98,33.61'
const accommodationKeyword = /(호텔|리조트|펜션|숙소|게스트하우스|게하|모텔|호스텔|스테이|콘도|민박|캠핑|야영장)/i
const normalize = (value: string) => value.normalize('NFKC').replace(/\s+/g, '').toLocaleLowerCase('ko-KR')
export const isSearchableKakaoQuery = (value: string) => normalize(value).length >= 2
const isJejuPlace = (result: KakaoPlaceSearchDocument) =>
  /제주특별자치도\s*(제주시|서귀포시)/.test(`${result.address_name ?? ''} ${result.road_address_name ?? ''}`)
const isAccommodation = (result: KakaoPlaceSearchDocument) =>
  result.category_group_code === 'AD5'
  || result.category_name?.includes('숙박')
  || accommodationKeyword.test(`${result.place_name} ${result.category_name ?? ''}`)


export function normalizeKakaoPlaceResults(results: KakaoPlaceSearchDocument[], query: string, mode: KakaoPlaceSearchMode) {
  const normalizedQuery = normalize(query)
  return results
    .map((result, accuracyIndex) => ({
      result,
      accuracyIndex,
      exactName: normalize(result.place_name) === normalizedQuery,
      lodgingCategory: result.category_group_code === 'AD5' || Boolean(result.category_name?.includes('숙박')),
    }))
    .filter(candidate => isJejuPlace(candidate.result))
    .filter(candidate => mode === 'GENERAL' || isAccommodation(candidate.result))
    .sort((first, second) => Number(second.exactName) - Number(first.exactName)
      || (mode === 'ACCOMMODATION' ? Number(second.lodgingCategory) - Number(first.lodgingCategory) : 0)
      || first.accuracyIndex - second.accuracyIndex)
    .map(candidate => mapKakaoPlaceSearchResult(candidate.result))
}

async function searchKakaoPlaces(query: string, mode: KakaoPlaceSearchMode, page: number): Promise<KakaoPlaceSearchPage> {
  const maps = await loadKakaoMap()
  const services = maps.services
  if (!services) throw new Error('Kakao Places services 라이브러리를 사용할 수 없습니다.')
  const places = new services.Places()
  return new Promise((resolve, reject) => {
    places.keywordSearch(query, (results, status, pagination) => {
      if (status === services.Status.ZERO_RESULT) {
        resolve({ items: [], current_page: 1, last_page: 1, total_count: 0, source: 'KAKAO' })
        return
      }
      if (status !== services.Status.OK) {
        reject(new Error('Kakao Places 검색에 실패했습니다.'))
        return
      }
      resolve({
        items: normalizeKakaoPlaceResults(results, query, mode),
        current_page: pagination.current || page,
        last_page: Math.max(1, pagination.last),
        total_count: pagination.totalCount,
        source: 'KAKAO',
      })
    }, { rect: JEJU_RECT, size: PAGE_SIZE, page, sort: services.SortBy.ACCURACY })
  })
}

export const kakaoPlaceSearchService = {
  async search(query: string, options: { mode: KakaoPlaceSearchMode; page?: number }): Promise<KakaoPlaceSearchPage> {
    const clean = query.trim()
    const page = options.page ?? 1
    if (!isSearchableKakaoQuery(clean)) return { items: [], current_page: 1, last_page: 1, total_count: 0, source: 'KAKAO' }
    return await searchKakaoPlaces(clean, options.mode, page)
  },
}
