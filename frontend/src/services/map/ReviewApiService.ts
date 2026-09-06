/**
 * 방문 후기 (MAP-09).
 *
 * 목록은 공개 API 라 지도 공용 apiGet 을 쓰고,
 * 작성·삭제는 마이페이지팀 backendClient.apiRequest(auth) 에 위임한다 -
 * 토큰 첨부·만료 시 자동 재발급·재시도가 이미 거기 있다.
 * 사진 업로드만 multipart 라 직접 fetch 한다 (apiRequest 는 JSON 전용).
 */
import { apiGet } from '../apiClient'
import {
  apiRequest as rawApiRequest, getBackendAccessToken, getBackendUserId,
  reissueAccessToken, BACKEND_BASE_URL
} from '../../api/backendClient.js'

/** backendClient(JS)의 추론 타입에 body 가 빠져 있어 여기서 시그니처를 못 박는다 */
const apiRequest = rawApiRequest as (
  path: string,
  opts?: { method?: string, body?: unknown, auth?: boolean, retryAuth?: boolean }
) => Promise<never>

/** 후기 한 건 - 백엔드 ReviewResponse 와 동일 모양 */
export interface ReviewItem {
  id: number
  userId: number
  /** 작성자 닉네임. 탈퇴 등으로 유저가 없으면 null - 화면이 '여행자N'으로 대체한다 */
  nickname: string | null
  /** null = 별점 없이 혼잡 제보만 한 후기 */
  rating: number | null
  congestionReport: 'QUIET' | 'NORMAL' | 'CROWDED' | null
  content: string | null
  imageUrls: string[]
  createdAt: string
}

export interface ReviewPage {
  content: ReviewItem[]
  number: number
  totalPages: number
  totalElements: number
}

export interface ReviewCreateInput {
  rating?: number | null
  congestionReport?: string | null
  content?: string | null
  imageUrls?: string[]
}

/** 서버 레벨 → 화면 키(calm/mid/busy). 배지 색·라벨이 이 키를 쓴다 */
export const LEVEL_TO_KEY: Record<string, 'calm' | 'mid' | 'busy'> = {
  QUIET: 'calm', NORMAL: 'mid', CROWDED: 'busy'
}

/** 로컬 저장 사진은 상대경로로 온다 - 백엔드 주소를 붙여야 그림이 뜬다 */
export const absUrl = (u: string): string =>
  u && u.startsWith('/') ? BACKEND_BASE_URL + u : u

export const ReviewApiService = {
  /** 장소별 후기 목록 - 비로그인 허용, 6개씩 */
  getReviews (placeId: number, page = 0): Promise<ReviewPage> {
    return apiGet<ReviewPage>(`/places/${placeId}/reviews?page=${page}&size=6`)
  },

  /** 작성 - 회원 전용. 별점 또는 혼잡 제보 중 1개 필수(서버 검증) */
  create (placeId: number, input: ReviewCreateInput): Promise<ReviewItem> {
    return apiRequest(`/places/${placeId}/reviews`, { method: 'POST', body: input, auth: true })
  },

  /** 삭제 - 작성자 본인만 */
  remove (reviewId: number): Promise<void> {
    return apiRequest(`/reviews/${reviewId}`, { method: 'DELETE', auth: true })
  },

  /** 사진 업로드 → URL 배열. 작성 요청의 imageUrls 로 넘긴다 */
  async uploadPhotos (files: File[]): Promise<string[]> {
    const send = async (): Promise<Response> => {
      const form = new FormData()
      files.forEach(f => form.append('files', f))
      return fetch(`${BACKEND_BASE_URL}/reviews/photos`, {
        method: 'POST',
        headers: { Authorization: `Bearer ${getBackendAccessToken() ?? ''}` },
        credentials: 'include',
        body: form
      })
    }

    let res = await send()
    // 토큰이 그새 만료됐으면 한 번만 재발급 후 재시도
    if (res.status === 401) {
      await reissueAccessToken()
      res = await send()
    }
    const body = await res.json()
    if (!body.success) {
      throw new Error(body.message ?? '사진 업로드에 실패했습니다.')
    }
    return body.result as string[]
  },

  /** 로그인한 내 userId. 비로그인이면 null - 본인 후기 삭제 버튼 판단용 */
  myUserId (): number | null {
    return getBackendUserId() ?? null
  }
}

export default ReviewApiService
