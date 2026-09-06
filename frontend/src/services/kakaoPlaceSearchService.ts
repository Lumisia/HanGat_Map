import type { KakaoPlaceSearchResult } from '../assets/types/course'
import { loadKakaoMap } from './map/KakaoMapLoader'
import { mapKakaoPlaceSearchResult, type KakaoPlaceSearchDocument } from './map/KakaoPlaceSearchMapper'

export type KakaoPlaceSearchMode = 'ACCOMMODATION' | 'GENERAL'

export interface KakaoPlaceSearchPage {
  items: KakaoPlaceSearchResult[]
  current_page: number
  last_page: number
  total_count: number
  source: 'KAKAO' | 'MOCK'
}

const PAGE_SIZE = 5
const JEJU_RECT = '126.08,33.10,126.98,33.61'
const accommodationKeyword = /(호텔|리조트|펜션|숙소|게스트하우스|게하|모텔|호스텔|스테이|콘도|민박|캠핑|야영장)/i
const normalize = (value: string) => value.normalize('NFKC').replace(/\s+/g, '').toLocaleLowerCase('ko-KR')
const isJejuPlace = (result: KakaoPlaceSearchDocument) =>
  /제주특별자치도\s*(제주시|서귀포시)/.test(`${result.address_name ?? ''} ${result.road_address_name ?? ''}`)
const isAccommodation = (result: KakaoPlaceSearchDocument) =>
  result.category_group_code === 'AD5'
  || result.category_name?.includes('숙박')
  || accommodationKeyword.test(`${result.place_name} ${result.category_name ?? ''}`)

const mockGeneralPlaces: KakaoPlaceSearchResult[] = [
  { source_code: 'KAKAO_LOCAL', source_place_id: 'MOCK_KAKAO_7001', place_name: '용머리해안', address: '제주특별자치도 서귀포시 안덕면 사계리 112-3', category_name: '여행 > 관광,명소 > 해변', latitude: 33.2317, longitude: 126.3146 },
  { source_code: 'KAKAO_LOCAL', source_place_id: 'MOCK_KAKAO_7002', place_name: '용머리공원', address: '제주특별자치도 서귀포시 안덕면 사계리', category_name: '여행 > 관광,명소 > 공원', latitude: 33.2332, longitude: 126.3153 },
  { source_code: 'KAKAO_LOCAL', source_place_id: 'MOCK_KAKAO_7003', place_name: '성산일출봉', address: '제주특별자치도 서귀포시 성산읍 성산리 1', category_name: '여행 > 관광,명소', latitude: 33.4581, longitude: 126.9425 },
  { source_code: 'KAKAO_LOCAL', source_place_id: 'MOCK_KAKAO_7004', place_name: '성산항', address: '제주특별자치도 서귀포시 성산읍 성산등용로 112-7', category_name: '교통 > 항구', latitude: 33.4720, longitude: 126.9335 },
  { source_code: 'KAKAO_LOCAL', source_place_id: 'MOCK_KAKAO_7005', place_name: '성산포항 종합여객터미널', address: '제주특별자치도 서귀포시 성산읍 성산등용로 112-7', category_name: '교통 > 여객선터미널', latitude: 33.4715, longitude: 126.9328 },
  { source_code: 'KAKAO_LOCAL', source_place_id: 'MOCK_KAKAO_7006', place_name: '성산포 해녀물질공연장', address: '제주특별자치도 서귀포시 성산읍 성산리', category_name: '문화,예술 > 공연장', latitude: 33.4620, longitude: 126.9360 },
]

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

function searchMockPlaces(query: string, page: number): KakaoPlaceSearchPage {
  const source = mockGeneralPlaces
  const normalizedQuery = normalize(query)
  const matches = source.filter(item => normalize(`${item.place_name}${item.address ?? ''}`).includes(normalizedQuery))
  const lastPage = Math.max(1, Math.ceil(matches.length / PAGE_SIZE))
  const safePage = Math.min(Math.max(1, page), lastPage)
  const offset = (safePage - 1) * PAGE_SIZE
  return { items: matches.slice(offset, offset + PAGE_SIZE), current_page: safePage, last_page: lastPage, total_count: matches.length, source: 'MOCK' }
}

export const kakaoPlaceSearchService = {
  async search(query: string, options: { mode: KakaoPlaceSearchMode; page?: number }): Promise<KakaoPlaceSearchPage> {
    const clean = query.trim()
    const page = options.page ?? 1
    if (clean.length < 2) return { items: [], current_page: 1, last_page: 1, total_count: 0, source: 'KAKAO' }
    try {
      return await searchKakaoPlaces(clean, options.mode, page)
    } catch (error) {
      if (options.mode === 'ACCOMMODATION') throw error
      return searchMockPlaces(clean, page)
    }
  },
}
