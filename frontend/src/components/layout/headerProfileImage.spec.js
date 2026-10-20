import { createSSRApp } from 'vue'
import { renderToString } from '@vue/server-renderer'
import { createPinia } from 'pinia'
import { createMemoryHistory, createRouter } from 'vue-router'
import { describe, expect, it } from 'vitest'
import AppHeader from './AppHeader.vue'
import { useAuthStore } from '../../stores/auth.js'
import { BACKEND_BASE_URL } from '../../api/backendClient.js'

const filename = '12345678-1234-1234-1234-123456789abc.png'
async function renderHeader (user) {
  const pinia = createPinia()
  const router = createRouter({ history: createMemoryHistory(), routes: [{ path: '/:pathMatch(.*)*', component: {} }] })
  await router.push('/')
  const app = createSSRApp(AppHeader).use(pinia).use(router)
  useAuthStore(pinia).user = user
  return renderToString(app)
}

describe('헤더 본인 프로필 사진', () => {
  it('내 정보의 본인 전용 경로를 공개 사진 경로로 연결한다', async () => {
    const html = await renderHeader({ userId: 7, nickname: '와플곰', profileImageUrl: `/users/me/profile-image/${filename}` })
    expect(html).toContain(`src="${BACKEND_BASE_URL}/users/7/profile-image/${filename}"`)
    expect(html).toContain('와플곰')
  })
  it.each([null, 'https://external.example/photo.png', `/users/8/profile-image/${filename}`])
  ('사진 미등록·외부 주소·다른 계정 사진은 기본 글자로 표시한다: %s', async profileImageUrl => {
    const html = await renderHeader({ userId: 7, nickname: '와플곰', profileImageUrl })
    expect(html).not.toContain('/profile-image/')
    expect(html).toMatch(/>와<\/span>/)
  })
})
