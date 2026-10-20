import { describe, expect, it } from 'vitest'
import { isSearchableKakaoQuery, normalizeKakaoPlaceResults } from './kakaoPlaceSearchService'
import { findPreferenceConflict } from './placePreferenceService'
import type { PlacePreference } from '../assets/types/course'

describe('kakaoPlaceSearchService', () => {
  it('공백을 제외한 완성 검색어가 두 글자일 때 검색을 시작한다', () => {
    expect(isSearchableKakaoQuery('제주')).toBe(true)
    expect(isSearchableKakaoQuery('제 주')).toBe(true)
    expect(isSearchableKakaoQuery('제')).toBe(false)
    expect(isSearchableKakaoQuery('  제  ')).toBe(false)
  })
  it('keeps only Jeju results in general mode without restricting categories', () => {
    const results = normalizeKakaoPlaceResults([
      { id: '1', place_name: '용머리해안', address_name: '제주특별자치도 서귀포시 안덕면', x: '126.31', y: '33.23', category_name: '관광지' },
      { id: '2', place_name: '용머리식당', road_address_name: '제주특별자치도 제주시 연동', x: '126.49', y: '33.49', category_name: '음식점' },
      { id: '3', place_name: '용머리공원', address_name: '부산광역시 남구', x: '129.1', y: '35.1', category_name: '공원' },
    ], '용머리', 'GENERAL')
    expect(results.map(item => item.source_place_id)).toEqual(['1', '2'])
    expect(results.every(item => item.source_code === 'KAKAO_LOCAL')).toBe(true)
  })

  it('detects duplicate and opposite preferences by sourcePlaceId rather than name', () => {
    const preferences: PlacePreference[] = [{
      source_code: 'KAKAO_LOCAL', source_place_id: '123456', place_name: '용머리해안',
      latitude: 33.23, longitude: 126.31, preference_type: 'WANT',
    }]
    expect(findPreferenceConflict(preferences, '123456', 'WANT')).toBe('DUPLICATE')
    expect(findPreferenceConflict(preferences, '123456', 'AVOID')).toBe('OPPOSITE')
    expect(findPreferenceConflict(preferences, '999999', 'AVOID')).toBeUndefined()
  })
})
