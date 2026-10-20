import { BACKEND_BASE_URL } from '../api/backendClient.js'

const PUBLIC_PROFILE_PATH = /^\/users\/[1-9][0-9]*\/profile-image\/[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}\.(jpg|png|webp)$/

/** 백엔드가 발급한 공개 프로필 경로만 허용한다. 외부 URL·MinIO 주소를 그대로 로딩하지 않는다. */
export function publicProfileImageUrl (path) {
  return typeof path === 'string' && PUBLIC_PROFILE_PATH.test(path) ? BACKEND_BASE_URL + path : null
}

/** 본인 전용 구형 주소도 헤더·설정이 같은 공개 UUID 주소를 사용하도록 정규화한다. */
export function currentProfileImagePath (user) {
  const id = String(user?.userId ?? '')
  const path = user?.profileImageUrl
  if (!/^[1-9][0-9]*$/.test(id) || typeof path !== 'string') return null
  const publicPath = path.replace(/^\/users\/me\/profile-image\//, `/users/${id}/profile-image/`)
  return publicPath.startsWith(`/users/${id}/profile-image/`) && publicProfileImageUrl(publicPath) ? publicPath : null
}
