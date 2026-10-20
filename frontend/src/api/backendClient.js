import { ApiError } from './errors.js'

/** 실제 Spring API 주소. 끝의 슬래시는 경로 중복을 막기 위해 제거한다. */
export const BACKEND_BASE_URL = (
  import.meta.env.VITE_API_BASE_URL ?? 'http://localhost:8080'
).replace(/\/$/, '')

const REFRESH_PATH = '/auth/reissue'
const JWT_ERROR_CODES = new Set([3001, 3002])

let accessToken = null
let accessExpiresAt = 0
let authenticatedUserId = null
let refreshPromise = null
let sessionEpoch = 0

function applyAccessToken (tokens) {
  if (!tokens || typeof tokens.accessToken !== 'string' || !tokens.accessToken) {
    throw new ApiError(500, 'INVALID_TOKEN_RESPONSE', '토큰 응답 형식을 확인해주세요.')
  }

  accessToken = tokens.accessToken
  accessExpiresAt = Date.now() + Math.max(0, Number(tokens.expiresIn) || 0)
  return tokens
}

async function readBaseResponse (response) {
  if (response.ok && response.status === 204) return null
  let body
  try {
    body = await response.json()
  } catch {
    throw new ApiError(
      response.status,
      'INVALID_RESPONSE',
      '서버 응답 형식을 확인해주세요.'
    )
  }

  if (!body || typeof body.success !== 'boolean') {
    throw new ApiError(
      response.status,
      'INVALID_RESPONSE',
      '서버 응답 형식을 확인해주세요.',
      body
    )
  }

  if (!response.ok || !body.success) {
    throw new ApiError(
      response.status,
      body.code ?? 'HTTP_ERROR',
      body.message || '요청을 처리하지 못했습니다.',
      body.result ?? null
    )
  }

  return body.result
}

async function rawRequest (path, { method = 'GET', body, token, responseType = 'json', timeoutMs = 0 } = {}) {
  const headers = { Accept: 'application/json' }
  const multipart = typeof FormData !== 'undefined' && body instanceof FormData
  // multipart 경계(boundary)는 브라우저가 생성하므로 Content-Type을 직접 지정하지 않는다.
  if (body !== undefined && !multipart) headers['Content-Type'] = 'application/json'
  if (responseType === 'blob') headers.Accept = 'image/jpeg,image/png,image/webp'
  if (token) headers.Authorization = `Bearer ${token}`
  const controller = timeoutMs > 0 ? new AbortController() : null
  const timer = controller ? setTimeout(() => controller.abort(), timeoutMs) : null

  let response
  try {
    response = await fetch(`${BACKEND_BASE_URL}${path}`, {
      method,
      headers,
      credentials: 'include',
      ...(controller ? { signal: controller.signal } : {}),
      ...(body !== undefined ? { body: multipart ? body : JSON.stringify(body) } : {})
    })
  } catch (error) {
    if (timer) clearTimeout(timer)
    if (controller?.signal.aborted) throw new ApiError(0, 'REQUEST_TIMEOUT', '응답 확인 시간이 초과됐어요. 다시 확인해 주세요.', error)
    throw new ApiError(0, 'NETWORK_ERROR', '서버에 연결할 수 없습니다.', error)
  }

  try {
    if (response.ok && responseType === 'blob') {
      const type = response.headers.get('Content-Type')?.split(';')[0].trim().toLowerCase()
      if (!['image/jpeg', 'image/png', 'image/webp'].includes(type)) {
        throw new ApiError(502, 'INVALID_IMAGE_RESPONSE', '사진 응답 형식을 확인해주세요.')
      }
      return await response.blob()
    }
    return await readBaseResponse(response)
  } finally {
    if (timer) clearTimeout(timer)
  }
}

function isJwtError (error) {
  return error instanceof ApiError && (
    error.status === 401 || JWT_ERROR_CODES.has(Number(error.code))
  )
}

/** 로그인 응답의 Access Token과 사용자 식별자만 메모리에 보관한다. */
export function acceptLoginResponse (loginResponse) {
  sessionEpoch += 1
  accessToken = null
  accessExpiresAt = 0
  authenticatedUserId = null
  applyAccessToken(loginResponse?.tokens)
  authenticatedUserId = loginResponse?.user?.userId ?? null
  return loginResponse
}

/** 재시작 복원 뒤 조회한 사용자 정보를 메모리 세션에 연결한다. */
export function syncAuthenticatedUser (user) {
  authenticatedUserId = user?.userId ?? null
  return user
}

/** 로그아웃 시 프론트 메모리에 남은 인증 정보도 즉시 제거한다. */
export function clearBackendSession () {
  sessionEpoch += 1
  accessToken = null
  accessExpiresAt = 0
  authenticatedUserId = null
}

export function getBackendAccessToken () {
  return accessToken
}

export function getBackendUserId () {
  return authenticatedUserId
}

/** 파일 요청 중 계정이 바뀌었는지 확인하는 메모리 세션 번호다. 인증 토큰은 아니다. */
export function getBackendSessionVersion () {
  return sessionEpoch
}

/** 기존 목업 콘텐츠의 소유자 키와 실제 회원 ID가 충돌하지 않게 구분한다. */
export function getBackendDemoUserId () {
  return authenticatedUserId == null ? null : `backend-user-${authenticatedUserId}`
}

export function accessTokenRemainSeconds () {
  if (!accessToken) return 0
  return Math.max(0, Math.ceil((accessExpiresAt - Date.now()) / 1000))
}

/** Refresh Token 회전이 겹치지 않도록 진행 중인 한 요청을 모든 호출자가 공유한다. */
export function reissueAccessToken () {
  if (refreshPromise) return refreshPromise

  const requestedEpoch = sessionEpoch
  refreshPromise = rawRequest(REFRESH_PATH, { method: 'POST' })
    .then(tokens => {
      if (requestedEpoch === sessionEpoch) applyAccessToken(tokens)
      return tokens
    })
    .catch(error => {
      if (requestedEpoch === sessionEpoch) clearBackendSession()
      throw error
    })
    .finally(() => {
      refreshPromise = null
    })

  return refreshPromise
}

/**
 * 인증 API는 만료 시 한 번만 재발급하고 원 요청도 한 번만 다시 보낸다.
 *
 * @param {string} path
 * @param {{ method?: string, body?: unknown, auth?: boolean, optionalAuth?: boolean, retryAuth?: boolean, responseType?: 'json'|'blob', sessionBound?: boolean, timeoutMs?: number }} [options]
 */
export async function apiRequest (path, {
  method = 'GET',
  body,
  auth = false,
  optionalAuth = false,
  retryAuth = true,
  responseType = 'json',
  sessionBound = false,
  timeoutMs = 0
} = {}) {
  // 회원이 생성한 READY 코스도 본인 확인이 필요하다. 비회원의 기존 공개 흐름은 유지한다.
  if (optionalAuth && authenticatedUserId != null) { auth = true; sessionBound = true }
  const requestedEpoch = sessionEpoch
  const requestedUserId = authenticatedUserId
  const checkSession = () => {
    if (sessionBound && requestedEpoch !== sessionEpoch) {
      throw new ApiError(401, 'SESSION_CHANGED', '로그인 계정이 변경됐어요. 다시 시도해 주세요.')
    }
  }
  // 사진 전송·조회는 다른 계정의 새 토큰으로 재시도하거나 이전 결과를 반영하지 않는다.
  const send = async token => {
    checkSession()
    if (sessionBound) {
      let subject = null
      try {
        // 다른 탭에서 refresh 쿠키의 계정이 바뀔 수 있다. 서명 검증은 서버가 담당하며,
        // 여기서는 재전송 전에 JWT의 사용자 식별자가 최초 화면의 계정과 같은지만 확인한다.
        subject = JSON.parse(atob(token.split('.')[1].replace(/-/g, '+').replace(/_/g, '/'))).sub
      } catch { /* 읽을 수 없는 토큰으로 개인 파일을 전송하지 않는다. */ }
      if (requestedUserId == null || String(subject) !== String(requestedUserId)) {
        throw new ApiError(401, 'SESSION_CHANGED', '로그인 계정이 변경됐어요. 새로고침 후 다시 시도해 주세요.')
      }
    }
    const result = await rawRequest(path, { method, body, token, responseType, timeoutMs })
    checkSession()
    return result
  }
  if (auth && !accessToken) await reissueAccessToken()
  const attemptedToken = auth ? accessToken : null

  try {
    return await send(attemptedToken)
  } catch (error) {
    checkSession()
    if (!auth || !retryAuth || path === REFRESH_PATH || error?.code === 'SESSION_CHANGED' || !isJwtError(error)) {
      throw error
    }

    // 다른 요청이 이미 회전을 마쳤다면 refresh를 또 쓰지 않고 새 토큰으로만 재시도한다.
    if (accessToken && accessToken !== attemptedToken) {
      return send(accessToken)
    }

    await reissueAccessToken()
    return send(accessToken)
  }
}
