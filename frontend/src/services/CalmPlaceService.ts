/**
 * 메인 "오늘 한적한 곳" 포스터 캐러셀 (MAIN_001).
 *
 * 1순위: 백엔드 GET /main/calm-places (관광공사 집중률 실측 시드 기반)
 * 폴백: 백엔드가 죽어 있으면 기존 목업(data/data)으로 화면 유지 + live 플래그로 라벨 전환.
 *
 * 실데이터 카드는 장소 상세 페이지(/places/<placeId>)로 이어진다.
 * 목업 폴백(백엔드 미가동)은 그 페이지가 읽을 백엔드 id가 없으므로 링크를 걸지 않는다.
 */
import { apiGet } from './apiClient'
import { levelLabel, places } from '../data/data'

export type CongestionLevelName = 'QUIET' | 'NORMAL' | 'CROWDED'

export interface CalmPlaceCard {
  key: string
  /** 카드를 눌렀을 때 갈 곳. 목업 폴백은 열 상세가 없어 null */
  to: string | null
  name: string
  region: string
  level: CongestionLevelName
  levelLabel: string
  imageUrl: string | null
  reason: string
}

export interface CalmPlaces {
  /** true = 백엔드 실데이터, false = 목업 폴백 */
  live: boolean
  cards: CalmPlaceCard[]
}

/** 백엔드 CalmPlaceResponse (domain/main/model/CalmPlaceResponse.java 와 동일 모양) */
interface BackendCalmPlace {
  placeId: number
  name: string
  regionLabel: string
  categoryLabel: string
  imageUrl: string | null
  rate: number
  level: CongestionLevelName
  levelLabel: string
  reason: string
}

export const CalmPlaceService = {
  async getCalmPlaces (limit = 7): Promise<CalmPlaces> {
    try {
      const rows = await apiGet<BackendCalmPlace[]>(`/main/calm-places?limit=${limit}`)
      return {
        live: true,
        cards: rows.map(row => ({
          key: `live-${row.placeId}`,
          to: `/places/${row.placeId}`,
          name: row.name,
          region: row.regionLabel,
          level: row.level,
          levelLabel: row.levelLabel,
          imageUrl: row.imageUrl,
          reason: row.reason
        }))
      }
    } catch {
      return { live: false, cards: sampleCards(limit) }
    }
  }
}

/** 목업 폴백 - 기존 캐러셀과 동일 규칙 (관광지만, 혼잡 제외, 집중률 오름차순) */
function sampleCards (limit: number): CalmPlaceCard[] {
  return [...places]
    .filter(p => p.categoryCode !== 'RESTAURANT' && (p.level === 'QUIET' || p.level === 'NORMAL'))
    .sort((a, b) => a.score - b.score)
    .slice(0, limit)
    .map(p => ({
      key: p.id,
      to: null,
      name: p.name,
      region: p.region,
      level: p.level as CongestionLevelName,
      levelLabel: levelLabel[p.level],
      imageUrl: p.imageUrl ?? p.image ?? null,
      reason: p.level === 'QUIET' ? '이 날짜 혼잡 예보가 한산이에요' : '인기 명소보다 한산한 편이에요'
    }))
}

export default CalmPlaceService
