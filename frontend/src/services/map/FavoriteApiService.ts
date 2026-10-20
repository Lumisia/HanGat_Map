/**
 * 찜 (MAP_009) - 백엔드 `/favorites` 가 지도 하트와 마이페이지 찜 목록의 단일 저장소다.
 *
 * 전부 회원 전용이라 마이페이지팀 backendClient.apiRequest(auth) 에 위임한다 -
 * 토큰 첨부·만료 시 자동 재발급·재시도가 이미 거기 있다. 비로그인이면 호출하지 않는다(호출부가 막는다).
 * PUT/DELETE 는 멱등이라 응답의 favorited 가 곧 하트 상태다.
 */
import { apiRequest as rawApiRequest } from '../../api/backendClient.js'

/** backendClient(JS)의 추론 타입에 body 가 빠져 있어 여기서 시그니처를 못 박는다 */
const apiRequest = rawApiRequest as (
  path: string,
  opts?: { method?: string, body?: unknown, auth?: boolean, retryAuth?: boolean }
) => Promise<never>

/** 백엔드 FavoriteStateResponse 와 동일 모양 */
export interface FavoriteState {
  placeId: number
  favorited: boolean
}

export const FavoriteApiService = {
  /** 내가 찜한 장소 ID 전부 - 지도 진입·로그인 직후 하트 상태를 맞춘다 */
  ids (): Promise<number[]> {
    return apiRequest('/favorites/ids', { auth: true })
  },

  add (placeId: number): Promise<FavoriteState> {
    return apiRequest(`/favorites/${placeId}`, { method: 'PUT', auth: true })
  },

  remove (placeId: number): Promise<FavoriteState> {
    return apiRequest(`/favorites/${placeId}`, { method: 'DELETE', auth: true })
  }
}

export default FavoriteApiService
