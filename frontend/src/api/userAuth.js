import {
  BACKEND_BASE_URL,
  acceptLoginResponse,
  apiRequest,
  clearBackendSession,
  reissueAccessToken,
  syncAuthenticatedUser
} from './backendClient.js'
import { ApiError } from './errors.js'
import { publicProfileImageUrl } from '../utils/profileImage.js'

/** 백엔드 UserResponse를 기존 화면이 읽는 최소 사용자 모델로 맞춘다. */
export function normalizeUser (user) {
  if (!user) return null
  return {
    userId: user.userId,
    email: user.email,
    nickname: user.nickname,
    birthDate: user.birthDate ?? null,
    status: user.status,
    statusCode: user.status,
    emailVerified: !!user.emailVerified,
    demoAccount: user.demoAccount === true,
    lastLoginAt: user.lastLoginAt ?? null,
    createdAt: user.createdAt ?? null,
    profileImageUrl: user.profileImageUrl ?? null
  }
}

function normalizeLogin (loginResponse) {
  if (!loginResponse) return null
  if (loginResponse.recoveryRequired === true) {
    clearBackendSession()
    return { recoveryRequired: true }
  }
  const normalized = {
    ...loginResponse,
    user: normalizeUser(loginResponse.user)
  }
  acceptLoginResponse(normalized)
  return normalized
}

// ────────────────────────── 가입·로그인 ──────────────────────────

export async function signup ({ email, password, passwordConfirm, nickname }) {
  const user = await apiRequest('/auth/signup', {
    method: 'POST',
    body: { email, password, passwordConfirm, nickname }
  })
  return normalizeUser(user)
}

export async function login ({ email, password }) {
  const result = await apiRequest('/auth/login', {
    method: 'POST',
    body: { email, password }
  })
  return normalizeLogin(result)
}

export async function loginDemo () {
  return normalizeLogin(await apiRequest('/auth/demo-login', { method: 'POST' }))
}

export async function restoreSession () {
  await reissueAccessToken()
  const user = normalizeUser(await apiRequest('/users/me', {
    auth: true,
    retryAuth: false
  }))
  syncAuthenticatedUser(user)
  return user
}

export async function logout () {
  try {
    await apiRequest('/auth/logout', { method: 'POST' })
  } finally {
    clearBackendSession()
  }
}

export async function withdrawAccount (email) {
  const result = await apiRequest('/users/me/withdrawal', {
    method: 'POST', body: { email }, auth: true, sessionBound: true, retryAuth: false
  })
  clearBackendSession()
  return result
}

// 복구 확인은 HttpOnly 쿠키로만 인증한다. 이메일/토큰을 URL이나 로컬 저장소에 두지 않는다.
export function getWithdrawalContext () {
  return apiRequest('/auth/withdrawal')
}

export function cancelWithdrawal () {
  return apiRequest('/auth/withdrawal/cancel', { method: 'POST' })
}

export function declineWithdrawalRecovery () {
  return apiRequest('/auth/withdrawal/decline', { method: 'POST' })
}

// ────────────────────────── 이메일 인증 ──────────────────────────

export function verifyEmail (token) {
  return apiRequest(`/auth/verify?token=${encodeURIComponent(token)}`)
}

export function resendVerification (email) {
  return apiRequest('/auth/resend-verification', {
    method: 'POST',
    body: { email }
  })
}

// ────────────────────────── 비밀번호 재설정 ──────────────────────────

export async function sendResetCode (email) {
  const result = await apiRequest('/auth/password/code', {
    method: 'POST',
    body: { email }
  })
  return { ...result, expiresInMs: result.expiresIn }
}

export async function verifyResetCode ({ code, requestId }) {
  const result = await apiRequest('/auth/password/verify', {
    method: 'POST',
    body: { code, requestId }
  })
  return { ...result, expiresInMs: result.expiresIn }
}

export async function resetPassword ({ ticket, password, passwordConfirm }) {
  const result = await apiRequest('/auth/password/reset', {
    method: 'POST',
    body: { ticket, password, passwordConfirm }
  })
  // 백엔드가 refresh 쿠키와 서버 세션을 폐기하므로 남은 Access Token도 즉시 버린다.
  clearBackendSession()
  return result
}

// ────────────────────────── 내 정보 ──────────────────────────

/** 파일 원문만 백엔드에 전달한다. MinIO 자격 증명은 프론트에 두지 않는다. */
export async function updateProfileImage (file) {
  const body = new FormData()
  body.append('file', file)
  return normalizeUser(await apiRequest('/users/me/profile-image', {
    method: 'PUT', body, auth: true, sessionBound: true
  }))
}

/** 공개 사진에는 인증을 붙이지 않는다. 이전 본인 전용 경로는 인증된 Blob 조회로 호환한다. */
export function readProfileImage (path) {
  if (publicProfileImageUrl(path)) return apiRequest(path, { responseType: 'blob' })
  if (!/^\/users\/me\/profile-image\/[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}\.(jpg|png|webp)$/.test(path)) {
    throw new ApiError(400, 'INVALID_IMAGE_PATH', '사진 주소를 확인해주세요.')
  }
  return apiRequest(path, { auth: true, responseType: 'blob', sessionBound: true })
}

export async function me () {
  const user = normalizeUser(await apiRequest('/users/me', { auth: true }))
  syncAuthenticatedUser(user)
  return user
}

export async function checkNicknameAvailable (nickname) {
  const query = new URLSearchParams({ nickname: String(nickname || '').trim() })
  return apiRequest(`/users/check-nickname?${query}`)
}

export async function updateNickname (nickname) {
  const user = normalizeUser(await apiRequest('/users/me/nickname', {
    method: 'PATCH',
    auth: true,
    sessionBound: true,
    body: { nickname }
  }))
  syncAuthenticatedUser(user)
  return user
}

export async function updateBirthDate (birthDate) {
  const user = normalizeUser(await apiRequest('/users/me/birth-date', {
    method: 'PATCH',
    auth: true,
    sessionBound: true,
    body: { birthDate: birthDate || null }
  }))
  syncAuthenticatedUser(user)
  return user
}

// ────────────────────────── 소셜 로그인 ──────────────────────────

export function oauthAuthorizationUrl (provider) {
  const normalized = String(provider || '').toLowerCase()
  if (!['google', 'kakao'].includes(normalized)) {
    throw new TypeError('지원하지 않는 소셜 로그인 공급자입니다.')
  }
  return `${BACKEND_BASE_URL}/oauth2/authorization/${normalized}`
}

export function getOAuthFlow () {
  return apiRequest('/auth/oauth/flow')
}

export function sendOAuthSignupCode ({ email, nickname }) {
  return apiRequest('/auth/oauth/signup/code', {
    method: 'POST',
    body: { email: email || null, nickname }
  })
}

export function sendOAuthLinkCode () {
  return apiRequest('/auth/oauth/link/code', { method: 'POST' })
}

export async function verifyOAuthCode (code) {
  const result = await apiRequest('/auth/oauth/code/verify', {
    method: 'POST',
    body: { code }
  })
  if (!result.login) return result
  return { ...result, login: normalizeLogin(result.login) }
}

export async function completeOAuthLink () {
  return normalizeLogin(await apiRequest('/auth/oauth/link/complete', {
    method: 'POST'
  }))
}

export function cancelOAuthFlow () {
  return apiRequest('/auth/oauth/flow', { method: 'DELETE' })
}
