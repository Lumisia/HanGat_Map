import { createSSRApp } from 'vue'
import { renderToString } from '@vue/server-renderer'
import { createPinia } from 'pinia'
import { createMemoryHistory, createRouter } from 'vue-router'
import { expect, it } from 'vitest'
import ProfileTab from './ProfileTab.vue'
import { useAuthStore } from '../../stores/auth.js'
import { normalizeUser } from '../../api/userAuth.js'

async function renderProfile (demoAccount) {
  const pinia = createPinia()
  const router = createRouter({ history: createMemoryHistory(), routes: [{ path: '/', component: {} }] })
  await router.push('/')
  const app = createSSRApp(ProfileTab).use(pinia).use(router)
  useAuthStore(pinia).user = normalizeUser({ userId: 1, nickname: '한갓지도 데모계정', demoAccount })
  return renderToString(app)
}
it('데모 계정은 비밀번호 변경 대신 제한 안내를 표시한다', async () => {
  const html = await renderProfile(true)
  expect(html).toContain('데모 계정의 비밀번호는 변경할 수 없어요.')
  expect(html).not.toMatch(/<button[^>]*>\s*비밀번호 변경\s*<\/button>/)
})
it('일반 회원은 비밀번호 변경 버튼을 유지한다', async () => {
  expect(await renderProfile(false)).toMatch(/<button[^>]*>\s*비밀번호 변경\s*<\/button>/)
})
