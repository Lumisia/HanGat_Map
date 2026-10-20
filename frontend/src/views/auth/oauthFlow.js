import { safeLoginReturnTo } from '../../utils/loginReturn.js'

const STEP_SCREENS = Object.freeze({
  PROFILE_REQUIRED: 'profile',
  LINK_CONFIRMATION: 'link-confirmation',
  CODE_REQUIRED: 'code',
  VERIFIED_LINK_CONFIRMATION: 'verified-link-confirmation',
  COMPLETED: 'completed',
  CANCELLED: 'cancelled'
})

export function oauthScreenForStep (step) {
  return STEP_SCREENS[step] || 'failure'
}

/** 백엔드 생성기와 같은 문자 집합만 남기고 사용자가 보기 쉽게 대문자로 맞춘다. */
export function normalizeOAuthCode (value) {
  return String(value || '')
    .replace(/[^2-9A-HJ-NP-Za-hj-np-z]/g, '')
    .toUpperCase()
    .slice(0, 6)
}

/** OAuth 이전 화면은 외부 URL이 아닌 앱 내부 절대 경로만 허용한다. */
export function safeOAuthReturnTo (value) {
  return safeLoginReturnTo(value)
}
