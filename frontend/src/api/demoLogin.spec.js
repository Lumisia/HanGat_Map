import { afterEach, expect, it, vi } from 'vitest'
import { createPinia, setActivePinia } from 'pinia'
import { useAuthStore } from '../stores/auth.js'
import { clearBackendSession } from './backendClient.js'
afterEach(() => { clearBackendSession(); vi.unstubAllGlobals() })
it('데모 로그인은 비밀번호 없이 전용 API를 호출하고 인증 상태를 저장한다', async () => {
  setActivePinia(createPinia())
  const fetch = vi.fn(async (url, options) => {
    expect(url).toMatch(/\/auth\/demo-login$/)
    expect(options.method).toBe('POST')
    expect(options.body).toBeUndefined()
    return new Response(JSON.stringify({ success:true, result:{ user:{userId:7,email:'demo@hangatjeju.com',demoAccount:true},tokens:{accessToken:'test-token',expiresIn:60000} } }))
  })
  vi.stubGlobal('fetch', fetch)
  const auth=useAuthStore()
  await auth.loginDemo()
  expect(auth.user.demoAccount).toBe(true)
  expect(auth.loading).toBe(false)
})
