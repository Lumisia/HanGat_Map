const AUTH_PATH = /^\/(login|signup|verify|find-password|oauth|auth)(\/|$)/i

export function isAuthPath (path) {
  return typeof path === 'string' && AUTH_PATH.test(path.split(/[?#]/)[0])
}

/** 로그인 뒤에는 앱 내부의 일반 화면만 열고, 누락·외부 주소·인증 화면은 메인으로 보낸다. */
export function safeLoginReturnTo (value) {
  if (typeof value !== 'string' || !value.startsWith('/') || value.startsWith('//') || /[\\\u0000-\u0020\u007f]/.test(value)) return '/'
  try {
    const path = decodeURIComponent(value.split(/[?#]/)[0])
    if (path.startsWith('//') || /[\\\u0000-\u0020\u007f]/.test(path)) return '/'
    const normalized = new URL(path, 'https://hangat.invalid')
    if (normalized.origin !== 'https://hangat.invalid' || isAuthPath(normalized.pathname)) return '/'
    return value
  } catch {
    return '/'
  }
}
