import { afterEach, beforeEach, expect, it, vi } from 'vitest'
import { acceptLoginResponse, apiRequest, clearBackendSession } from './backendClient.js'
import { normalizeUser, readProfileImage, updateProfileImage } from './userAuth.js'
import { createPinia, setActivePinia } from 'pinia'
import { useAuthStore } from '../stores/auth.js'

const ok = result => new Response(JSON.stringify({ success: true, code: 1000, result }), { status: 200 })
const token = (userId, version = 'old') => `test.${btoa(JSON.stringify({ sub: String(userId) }))}.${version}`
beforeEach(() => acceptLoginResponse({ user: { userId: 1 }, tokens: { accessToken: token(1), expiresIn: 60000 } }))
afterEach(() => { clearBackendSession(); vi.unstubAllGlobals() })

it('프로필 파일은 JSON 변환 없이 multipart 본문과 인증을 보낸다', async () => {
  const file = new FormData()
  file.append('file', new Blob(['image'], { type: 'image/png' }), 'photo.png')
  vi.stubGlobal('fetch', vi.fn(async (_url, options) => {
    expect(options.body).toBe(file)
    expect(options.headers['Content-Type']).toBeUndefined()
    expect(options.headers.Authorization).toBe(`Bearer ${token(1)}`)
    return ok({ userId: 1 })
  }))
  await apiRequest('/users/me/profile-image', { method: 'PUT', body: file, auth: true, sessionBound: true })
})

it('인증된 이미지 응답은 JSON이 아닌 Blob으로 읽는다', async () => {
  vi.stubGlobal('fetch', vi.fn(async () => new Response('image bytes', { headers: { 'Content-Type': 'image/png' } })))
  const image = await apiRequest('/users/me/profile-image/test.png', { auth: true, responseType: 'blob', sessionBound: true })
  expect(await image.text()).toBe('image bytes')
})

it('사진 응답이 도착하기 전 로그아웃하면 이전 세션 결과를 버린다', async () => {
  vi.stubGlobal('fetch', vi.fn(async () => { clearBackendSession(); return ok({ userId: 1 }) }))
  await expect(apiRequest('/users/me/profile-image', { auth: true, sessionBound: true })).rejects.toMatchObject({ code: 'SESSION_CHANGED' })
})

it('사용자 정보에 프로필 사진 주소를 유지한다', () => {
  expect(normalizeUser({ userId: 1, profileImageUrl: '/users/me/profile-image/test.png' }).profileImageUrl)
    .toBe('/users/me/profile-image/test.png')
})

it('만료 토큰은 재발급 후 같은 파일을 한 번 다시 전송한다', async () => {
  const bodies = []
  const requests = []
  vi.stubGlobal('fetch', vi.fn(async (url, options) => {
    requests.push(url)
    if (url.endsWith('/auth/reissue')) return ok({ accessToken: token(1, 'new'), expiresIn: 60000 })
    bodies.push(options.body)
    if (options.headers.Authorization === `Bearer ${token(1)}`) {
      return new Response(JSON.stringify({ success: false, code: 3001, message: 'expired' }), { status: 400 })
    }
    expect(options.headers.Authorization).toBe(`Bearer ${token(1, 'new')}`)
    return ok({ userId: 1, profileImageUrl: '/users/me/profile-image/example.png' })
  }))
  const updated = await updateProfileImage(new Blob(['png'], { type: 'image/png' }))
  expect(updated.profileImageUrl).toBe('/users/me/profile-image/example.png')
  expect(requests).toHaveLength(3)
  expect(bodies[0]).toBeInstanceOf(FormData)
  expect(bodies[0]).toBe(bodies[1])
  expect(await bodies[0].get('file').text()).toBe('png')
})

it('외부 사진 주소에는 인증 토큰을 보내지 않는다', () => {
  const fetch = vi.fn()
  vi.stubGlobal('fetch', fetch)
  expect(() => readProfileImage('https://untrusted.example/photo.png')).toThrow()
  expect(fetch).not.toHaveBeenCalled()
})

it('공개 프로필 사진은 로그인 없이 토큰을 붙이지 않고 읽는다', async () => {
  clearBackendSession()
  const path = '/users/7/profile-image/12345678-1234-1234-1234-123456789abc.png'
  const requests = []
  vi.stubGlobal('fetch', vi.fn(async (url, options) => {
    requests.push(url)
    expect(options.headers.Authorization).toBeUndefined()
    return new Response('public image', { headers: { 'Content-Type': 'image/png' } })
  }))
  const image = await readProfileImage(path)
  expect(await image.text()).toBe('public image')
  expect(requests).toHaveLength(1)
  expect(requests[0]).toContain(path)
})

it('다른 탭에서 계정이 바뀌어도 새 계정의 토큰으로 사진을 재전송하지 않는다', async () => {
  const uploads = []
  vi.stubGlobal('fetch', vi.fn(async (url, options) => {
    if (url.endsWith('/auth/reissue')) return ok({ accessToken: token(2), expiresIn: 60000 })
    uploads.push(options.headers.Authorization)
    if (uploads.length === 1) return new Response(JSON.stringify({ success: false, code: 3001 }), { status: 400 })
    return ok({ userId: 2 })
  }))
  await expect(updateProfileImage(new Blob(['png']))).rejects.toMatchObject({ code: 'SESSION_CHANGED' })
  expect(uploads).toEqual([`Bearer ${token(1)}`])
})

it.each(['updateNickname', 'updateBirthDate'])('늦게 도착한 %s 응답이 새 사진을 덮어쓰지 않는다', async action => {
  setActivePinia(createPinia())
  const auth = useAuthStore()
  auth.user = normalizeUser({ userId: 1, nickname: 'before', profileImageUrl: 'old-photo' })
  let resolveEdit
  vi.stubGlobal('fetch', vi.fn(async url => {
    if (url.endsWith('/profile-image')) return ok({ userId: 1, profileImageUrl: 'new-photo' })
    return new Promise(resolve => { resolveEdit = resolve })
  }))
  const edit = auth[action](action === 'updateNickname' ? 'after' : '2000-01-01')
  await auth.updateProfileImage(new Blob(['png']))
  resolveEdit(ok({ userId: 1, nickname: 'after', birthDate: '2000-01-01', profileImageUrl: 'old-photo' }))
  await edit
  expect(auth.user.profileImageUrl).toBe('new-photo')
})
