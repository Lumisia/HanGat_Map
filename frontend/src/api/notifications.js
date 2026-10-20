import { apiRequest, BACKEND_BASE_URL, getBackendAccessToken, getBackendSessionVersion, getBackendUserId, reissueAccessToken } from './backendClient.js'

// 백엔드 배포 전에는 새 API를 호출하지 않는다. Vite 빌드 시 활성화한다.
export const NOTIFICATIONS_ENABLED = import.meta.env.VITE_NOTIFICATIONS_ENABLED === 'true'
// Shared backend default enables member async generation/history in every app mode.
// Explicit false remains available for a coordinated feature shutdown.
export const ASYNC_COURSES_ENABLED = (import.meta.env.VITE_ASYNC_COURSES_ENABLED ?? 'true') === 'true'
export const TRIP_ALERTS_ENABLED = import.meta.env.VITE_TRIP_ALERTS_ENABLED === 'true'
// 여행 확정 API와 별개: 실제 비교·일정 배치 배포를 확인한 뒤 켠다.
export const TRIP_NOTIFICATIONS_ENABLED = import.meta.env.VITE_TRIP_NOTIFICATIONS_ENABLED === 'true'
export function isNotificationPreferenceAvailable (key) {
  if (key === 'aiCourse') return true
  return TRIP_NOTIFICATIONS_ENABLED && ['forecastChange', 'congestion', 'tripSummary', 'reviewRequest'].includes(key)
}

export function notificationTypeLabel (type) {
  const labels = {
    AI_COURSE_COMPLETED: 'AI 코스 완성', AI_COURSE_FAILED: 'AI 코스 생성 실패',
    FORECAST_CHANGE: '날씨 예보 변경', CONGESTION_WORSENED: '혼잡 예보 악화',
    TRIP_SUMMARY: '여행 일정', REVIEW_REQUEST: '여행 후 리뷰',
    SECURITY_LOGIN: '로그인 안내', SECURITY_PASSWORD_CHANGED: '비밀번호 변경', NOTICE: '중요 공지'
  }
  return Object.hasOwn(labels, type) ? labels[type] : '알림'
}

export function notificationActionLabel (item) {
  if (item.targetType === 'COURSE_GENERATION') return '코스 생성 결과 확인'
  if (item.targetType === 'COURSE') return item.type === 'REVIEW_REQUEST' ? '방문 장소 확인하고 리뷰 남기기' : '해당 코스 보기'
  if (item.targetType === 'PLACE') return '해당 장소 보기'
  if (item.targetType === 'SECURITY') return '계정 설정 확인'
  return ''
}
const authenticated = { auth: true, sessionBound: true, timeoutMs: 15000 }

export const listNotifications = (cursor = null) => apiRequest(`/users/me/notifications?size=30${cursor ? `&cursor=${encodeURIComponent(cursor)}` : ''}`, authenticated)
export const readNotification = id => apiRequest(`/users/me/notifications/${encodeURIComponent(id)}/read`, { ...authenticated, method: 'PUT' })
export const readAllNotifications = () => apiRequest('/users/me/notifications/read-all', { ...authenticated, method: 'PUT' })
export const deleteNotification = id => apiRequest(`/users/me/notifications/${encodeURIComponent(id)}`, { ...authenticated, method: 'DELETE' })
export const deleteAllNotifications = () => apiRequest('/users/me/notifications', { ...authenticated, method: 'DELETE' })
export const clearHeaderNotifications = () => apiRequest('/users/me/notifications/header', { ...authenticated, method: 'DELETE' })
export const listNotificationPage = (page = 0, category = 'ALL') => apiRequest(
  `/users/me/notifications/page?${new URLSearchParams({ page: String(page), category })}`, authenticated)

export const NOTIFICATION_FILTERS = Object.freeze([
  { value: 'ALL', label: '전체' }, { value: 'LOGIN', label: '로그인' },
  { value: 'COURSE', label: '코스 생성' }, { value: 'TRIP', label: '여행 일정' }, { value: 'OTHER', label: '기타' }
])

/** 서버가 보낸 임의 URL로 이동하지 않고, 앱에서 허용한 목적지만 해석한다. */
export function notificationDestination (item) {
  const id = String(item.targetId ?? '')
  if (item.targetType === 'COURSE_GENERATION' && /^[a-f\d]{8}(-[a-f\d]{4}){3}-[a-f\d]{12}$/i.test(id)) {
    return { name: 'course-generation-job', params: { jobId: id } }
  }
  if (item.targetType === 'COURSE' && /^[1-9]\d{0,14}$/.test(id)) return { name: 'course-detail', params: { courseId: id } }
  if (item.targetType === 'PLACE' && /^[1-9]\d{0,14}$/.test(id)) return { name: 'place-detail', params: { placeId: id } }
  if (item.targetType === 'SECURITY') return { name: 'my-profile' }
  return { name: 'my-alerts' }
}

/** UTF-8 디코딩 뒤 전달된 조각을 SSE 줄/이벤트로 조립한다. heartbeat는 무시한다. */
export function createSseParser (onEvent) {
  let buffer = ''; let data = []; let event = 'message'; let length = 0
  return chunk => {
    buffer += chunk
    if (buffer.length + length > 65536) throw new Error('알림 이벤트 크기가 너무 큽니다.')
    for (;;) {
      const match = /\r\n|\n|\r(?!$)/.exec(buffer)
      if (!match) break
      const line = buffer.slice(0, match.index)
      buffer = buffer.slice(match.index + match[0].length)
      if (!line) {
        if (data.length) onEvent({ event, data: data.join('\n') })
        data = []; event = 'message'; length = 0
      } else if (!line.startsWith(':')) {
        const colon = line.indexOf(':')
        const key = colon < 0 ? line : line.slice(0, colon)
        const value = colon < 0 ? '' : line.slice(colon + 1).replace(/^ /, '')
        if (key === 'data') { data.push(value); length += value.length }
        if (key === 'event') event = value
      }
    }
  }
}

/** 한 번의 연결만 담당한다. 재연결과 화면 생명주기는 공유 store가 관리한다. */
export async function streamNotifications ({ signal, onEvent, onOpen }) {
  const epoch = getBackendSessionVersion()
  const userId = getBackendUserId()
  if (!getBackendAccessToken()) await reissueAccessToken()
  const connect = () => {
    if (signal.aborted || epoch !== getBackendSessionVersion()) throw new Error('SESSION_CHANGED')
    const token = getBackendAccessToken()
    let subject
    try { subject = JSON.parse(atob(token.split('.')[1].replace(/-/g, '+').replace(/_/g, '/'))).sub } catch { /* 아래에서 거부 */ }
    if (userId == null || String(subject) !== String(userId)) throw new Error('SESSION_CHANGED')
    return fetch(`${BACKEND_BASE_URL}/users/me/notifications/stream`, {
      headers: { Accept: 'text/event-stream', Authorization: `Bearer ${token}` },
      credentials: 'include', cache: 'no-store', signal
    })
  }
  let response = await connect()
  if (response.status === 401) {
    await response.body?.cancel()
    await reissueAccessToken()
    response = await connect()
  }
  if (!response.ok || !response.headers.get('Content-Type')?.startsWith('text/event-stream') || !response.body) {
    await response.body?.cancel()
    throw new Error('알림 실시간 연결을 시작하지 못했어요.')
  }
  onOpen()
  const reader = response.body.getReader()
  const decoder = new TextDecoder()
  const feed = createSseParser(value => {
    if (!signal.aborted && epoch === getBackendSessionVersion()) onEvent(value)
  })
  try {
    while (!signal.aborted) {
      const { value, done } = await reader.read()
      if (done) break
      feed(decoder.decode(value, { stream: true }))
    }
  } finally {
    await reader.cancel().catch(() => {})
    reader.releaseLock()
  }
}
