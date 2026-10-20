import { beforeEach, describe, expect, it, vi } from 'vitest'
import { createPinia, setActivePinia } from 'pinia'
import * as client from './backendClient.js'
import * as api from './userAuth.js'
import { useAuthStore } from '../stores/auth.js'
import { safeLoginReturnTo } from '../utils/loginReturn.js'

vi.mock('./backendClient.js', () => ({
  BACKEND_BASE_URL: '/api', apiRequest: vi.fn(), acceptLoginResponse: vi.fn(),
  clearBackendSession: vi.fn(), reissueAccessToken: vi.fn(), syncAuthenticatedUser: vi.fn(),
  getBackendSessionVersion: () => 1, accessTokenRemainSeconds: () => 0
}))

beforeEach(() => { vi.clearAllMocks(); setActivePinia(createPinia()) })

describe('withdrawal authentication boundary', () => {
  it('does not accept a recovery response as a normal login', async () => {
    client.apiRequest.mockResolvedValue({ recoveryRequired: true })
    const auth = useAuthStore()
    auth.user = { userId: 2 }
    expect(await auth.login({ email: 'member@example.com', password: 'test' })).toEqual({ recoveryRequired: true })
    expect(auth.user).toBeNull()
    expect(client.acceptLoginResponse).not.toHaveBeenCalled()
    expect(client.clearBackendSession).toHaveBeenCalled()
  })

  it('withdraws through the authenticated session and clears it only after success', async () => {
    client.apiRequest.mockResolvedValue({ deleteAt: '2026-10-19T04:00:00Z' })
    const auth = useAuthStore()
    auth.user = { userId: 2 }
    await auth.withdraw('member@example.com')
    expect(client.apiRequest).toHaveBeenCalledWith('/users/me/withdrawal', expect.objectContaining({
      method: 'POST', auth: true, sessionBound: true, retryAuth: false, body: { email: 'member@example.com' }
    }))
    expect(auth.user).toBeNull()
    expect(client.clearBackendSession).toHaveBeenCalled()
  })

  it('keeps the current account when the server rejects withdrawal', async () => {
    client.apiRequest.mockRejectedValue(new Error('email mismatch'))
    const auth = useAuthStore()
    auth.user = { userId: 2 }
    await expect(auth.withdraw('wrong@example.com')).rejects.toThrow('email mismatch')
    expect(auth.user.userId).toBe(2)
    expect(client.clearBackendSession).not.toHaveBeenCalled()
  })

  it('retrieves recovery context and decisions without email or token in URL/body', async () => {
    client.apiRequest.mockResolvedValue({ email: 'member@example.com', deleteAt: '2026-10-19T04:00:00Z' })
    await api.getWithdrawalContext()
    await api.cancelWithdrawal()
    await api.declineWithdrawalRecovery()
    expect(client.apiRequest.mock.calls).toEqual([
      ['/auth/withdrawal'],
      ['/auth/withdrawal/cancel', { method: 'POST' }],
      ['/auth/withdrawal/decline', { method: 'POST' }]
    ])
  })

  it('never returns to the recovery screen after a later successful login', () => {
    expect(safeLoginReturnTo('/auth/withdrawal')).toBe('/')
  })
})
