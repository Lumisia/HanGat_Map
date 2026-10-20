import { createSSRApp } from 'vue'
import { renderToString } from '@vue/server-renderer'
import { createPinia } from 'pinia'
import { createMemoryHistory, createRouter } from 'vue-router'
import { describe, expect, it, vi } from 'vitest'
import ProfileImageEditor from './ProfileImageEditor.vue'
import { useAuthStore } from '../../stores/auth.js'
import { BACKEND_BASE_URL } from '../../api/backendClient.js'

const filename = '12345678-1234-1234-1234-123456789abc.png'
async function renderEditor (user) {
  const pinia = createPinia()
  const router = createRouter({ history: createMemoryHistory(), routes: [{ path: '/', component: {} }] })
  await router.push('/')
  const app = createSSRApp(ProfileImageEditor).use(pinia).use(router)
  useAuthStore(pinia).user = user
  return renderToString(app)
}

describe('current profile image browser cache', () => {
  it('demo account renders its fixed photo without upload controls', async () => {
    const html = await renderEditor({ userId: 7, nickname: '한갓지도 데모계정', demoAccount: true, profileImageUrl: `/users/7/profile-image/${filename}` })
    expect(html).toContain(`src="${BACKEND_BASE_URL}/users/7/profile-image/${filename}"`)
    expect(html).toContain('데모 계정의 프로필 사진은 변경할 수 없어요.')
    expect(html).not.toContain('type="file"')
    expect(html).not.toMatch(/<button[^>]*>사진 변경<\/button>/)
  })
  it.each([`/users/me/profile-image/${filename}`, `/users/7/profile-image/${filename}`])
  ('renders the shared public image URL directly on each visit: %s', async profileImageUrl => {
    const html = await renderEditor({ userId: 7, nickname: '사진', profileImageUrl })
    expect(html).toContain(`src="${BACKEND_BASE_URL}/users/7/profile-image/${filename}"`)
    expect(html).not.toContain('blob:')
  })
  it.each([null, 'https://external.example/photo.png', `/users/8/profile-image/${filename}`])
  ('falls back to the initial for missing or foreign pictures: %s', async profileImageUrl => {
    const html = await renderEditor({ userId: 7, nickname: '사진', profileImageUrl })
    expect(html).not.toContain('src=')
    expect(html).toContain('기본 프로필')
  })
  it('reuses the URL on later visits and switches to the new UUID after photo replacement without a Blob request', async () => {
    const fetch = vi.spyOn(globalThis, 'fetch')
    try {
      const user = { userId: 7, nickname: '사진', profileImageUrl: `/users/me/profile-image/${filename}` }
      const first = await renderEditor(user)
      const nextVisit = await renderEditor(user)
      expect(nextVisit).toBe(first)
      const replacement = await renderEditor({ ...user, profileImageUrl: '/users/me/profile-image/aaaaaaaa-1234-1234-1234-123456789abc.png' })
      expect(replacement).toContain(`src="${BACKEND_BASE_URL}/users/7/profile-image/aaaaaaaa-1234-1234-1234-123456789abc.png"`)
      expect(replacement).not.toContain(filename)
      expect(fetch).not.toHaveBeenCalled()
    } finally {
      fetch.mockRestore()
    }
  })
})
