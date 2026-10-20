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
  opts?: { method?: string, body?: unknown, auth?: boolean, retryAuth?: boolean, sessionBound?: boolean }
) => Promise<never>

/** 후기 목록 한 번에 받는 개수 - "더 보기" 단위. 백엔드 상한 20 안이어야 한다(넘으면 400). 2026-09-07 6 → 10 */
export const PAGE_SIZE = 10

/** 후기 한 건 - 백엔드 ReviewResponse 와 동일 모양 */
export interface ReviewItem {
  id: number
  userId: number
  /** 작성자 닉네임. 탈퇴 등으로 유저가 없으면 null - 화면이 '여행자N'으로 대체한다 */
  nickname: string | null
  /** 작성자의 현재 공개 프로필 사진. 미등록·비활성 계정은 null이다. */
  profileImageUrl: string | null
  /** null = 별점 없이 혼잡 제보만 한 후기 */
  rating: number | null
  congestionReport: 'QUIET' | 'NORMAL' | 'CROWDED' | null
  content: string | null
  imageUrls: string[]
  createdAt: string
  editedAt?: string | null
  editableUntil?: string | null
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

/** 사진 업로드 실패 응답을 사용자에게 보여줄 한 문장으로 바꾼다.
    서버 검증 실패는 { success:false, message } JSON 이지만, 5MB 를 넘긴 413 은 스프링 기본 오류 본문(message 없음)이고
    프록시 오류는 HTML 이라 - 그대로 json() 하면 "Unexpected end of JSON input" 이 토스트에 떴다 */
export async function uploadFailMessage (res: Response): Promise<string> {
  if (res.status === 401) return LOGIN_EXPIRED_TEXT   // 재발급까지 실패한 뒤 - 서버 봉투는 "JWT 토큰 유효하지 않음"이라 사람 말로
  try {
    const body = await res.json()
    if (body?.message) return body.message as string
  } catch { /* JSON 이 아닌 본문 */ }
  if (res.status === 413) return '사진이 너무 커서 올리지 못했어요 · 5MB 이하로 줄여 주세요'
  return '사진을 올리지 못했어요 · 잠시 후 다시 시도해 주세요'
}

export const LOGIN_EXPIRED_TEXT = '로그인이 만료됐어요 · 다시 로그인한 뒤 시도해 주세요'

/** 백엔드 BaseResponseStatus 3000번대 = 업무 오류. 문구가 사용자용 문장이라 그대로 보여준다(팀 규칙 - 회원·코스·공유 코드도 같은 방식) */
const isBusinessCode = (code: unknown): code is number => typeof code === 'number' && code >= 3000 && code < 4000

/** 후기 등록·삭제 실패를 사용자 문장으로 바꾼다(2026-09-19).
    규칙: 업무 오류(3xxx)는 서버 문구를 그대로 - 문구의 주인은 백엔드 한 곳이다. 인증(401·3001·3002)은 코드로
    판단해 로그인 만료 문구(서버 문구 "JWT 토큰 유효하지 않음"은 로그용). 서버 장애(5xxx)·연결 실패·응답 형식 오류는
    내부 사정을 내보내지 않고 무엇을 못 했는지(base) + 할 일로 통일한다. 숫자·영어는 화면에 내보내지 않는다 */
export function failText (err: unknown, base: string): string {
  const e = err as { status?: number, code?: number | string, message?: string } | null
  if (e && e.code !== undefined) {   // ApiError(backendClient) - 서버 코드가 있다
    if (e.code === 'SESSION_CHANGED' && e.message) return e.message   // 계정 전환 안내는 이미 사람 말
    if (e.status === 401 || e.code === 3001 || e.code === 3002) return LOGIN_EXPIRED_TEXT
    if (isBusinessCode(e.code) && e.message) return e.message
    if (e.code === 'NETWORK_ERROR' || e.code === 'REQUEST_TIMEOUT') return `${base} · 인터넷 연결을 확인해 주세요`
    return `${base} · 잠시 후 다시 시도해 주세요`
  }
  // 사진 업로드(uploadPhotos)가 만든 우리 문구는 한글이라 그대로. 브라우저 예외 같은 영어 원문은 숨긴다
  if (e?.message && /[가-힣]/.test(e.message)) return e.message
  return `${base} · 잠시 후 다시 시도해 주세요`
}

export const ReviewApiService = {
  /** 장소별 후기 목록 - 비로그인 허용, 6개씩 */
  getReviews (placeId: number, page = 0): Promise<ReviewPage> {
    return apiGet<ReviewPage>(`/places/${placeId}/reviews?page=${page}&size=${PAGE_SIZE}`)
  },

  /** 작성 - 회원 전용. 별점 또는 혼잡 제보 중 1개 필수(서버 검증) */
  create (placeId: number, input: ReviewCreateInput): Promise<ReviewItem> {
    return apiRequest(`/places/${placeId}/reviews`, { method: 'POST', body: input, auth: true })
  },

  /** 수정 - 최초 작성 후 7일 이내, 계정 전환 시 재전송하지 않는다. */
  update (reviewId: number, input: ReviewCreateInput): Promise<ReviewItem> {
    return apiRequest(`/reviews/${reviewId}`, { method: 'PUT', body: input, auth: true, sessionBound: true })
  },

  /** 삭제 - 작성자 본인만 */
  remove (reviewId: number): Promise<void> {
    return apiRequest(`/reviews/${reviewId}`, { method: 'DELETE', auth: true })
  },

  /** 사진 업로드 → URL 배열. 작성 요청의 imageUrls 로 넘긴다 */
  async uploadPhotos (files: File[], { sessionBound = false } = {}): Promise<string[]> {
    // 편집 중 계정이 바뀌면 다른 계정으로 사진을 재전송하지 않는다.
    if (sessionBound) {
      const form = new FormData()
      files.forEach(f => form.append('files', f))
      return apiRequest('/reviews/photos', { method: 'POST', body: form, auth: true, sessionBound: true })
    }
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

    // 서버가 큰 요청을 받다 연결을 끊거나 네트워크가 없으면 fetch 자체가 영어("Failed to fetch")로 던진다 - 우리 문구로 바꾼다
    const sendOrExplain = (): Promise<Response> =>
      send().catch(() => { throw new Error('사진을 올리지 못했어요 · 인터넷 연결을 확인해 주세요') })

    let res = await sendOrExplain()
    // 토큰이 그새 만료됐으면 한 번만 재발급 후 재시도
    if (res.status === 401) {
      await reissueAccessToken()
      res = await sendOrExplain()
    }
    if (!res.ok) throw new Error(await uploadFailMessage(res))
    const body = await res.json()
    if (!body.success) {
      throw new Error(body.message ?? '사진을 올리지 못했어요')
    }
    return body.result as string[]
  },

  /** 로그인한 내 userId. 비로그인이면 null - 본인 후기 삭제 버튼 판단용 */
  myUserId (): number | null {
    return getBackendUserId() ?? null
  }
}

export default ReviewApiService
