import { apiRequest, BACKEND_BASE_URL, getBackendSessionVersion, getBackendUserId } from './backendClient.js'

/** 내 리뷰 API. 소유자는 서버의 인증 정보로 결정하고 사용자 ID를 보내지 않는다. */
const authenticated = { auth: true, sessionBound: true }

/**
 * 새 계정의 오류는 건드리지 않는다. 재발급 실패로 토큰만 비워진 경우에는
 * 세션 번호가 달라져도 원래 화면에 오류/재로그인 안내를 전달해야 한다.
 */
export function canHandleActivityError(error, epoch, userId, currentUserId) {
  if (userId !== currentUserId) return false
  return epoch === getBackendSessionVersion()
    || (getBackendUserId() == null && error?.code !== 'SESSION_CHANGED')
}

export const REVIEW_SORTS = [
  { key: 'created_desc', label: '최신순' },
  { key: 'rating_desc', label: '별점 높은순' },
  { key: 'rating_asc', label: '별점 낮은순' },
]

function resourceId(value) {
  const id = String(value ?? '')
  if (!/^[1-9]\d*$/.test(id)) throw new Error('올바른 리뷰 ID가 필요합니다.')
  return id
}

export function listMyReviews({ page = 0, size = 10, sort = 'created_desc' } = {}) {
  const query = new URLSearchParams({ page, size, sort })
  return apiRequest(`/users/me/reviews?${query}`, authenticated)
}

export function deleteMyReview(reviewId) {
  return apiRequest(`/reviews/${resourceId(reviewId)}`, { ...authenticated, method: 'DELETE' })
}

/** MinIO 프록시 상대 경로와 장소 사진 URL을 표시용 주소로 바꾼다. 인증 토큰은 붙이지 않는다. */
export function mediaUrl(value) {
  if (typeof value !== 'string') return ''
  const path = value.trim()
  if (!path || path.includes('\\') || path.startsWith('//')) return ''
  if (path.startsWith('/')) return `${BACKEND_BASE_URL}${path}`
  try {
    const url = new URL(path)
    return ['http:', 'https:'].includes(url.protocol) ? url.href : ''
  } catch { return '' }
}
