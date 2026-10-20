import { beforeEach, describe, expect, it, vi } from 'vitest'
import { apiRequest } from './backendClient.js'
import { listGenerationJobs } from './courseGeneration.js'
vi.mock('./backendClient.js', () => ({ apiRequest: vi.fn(), getBackendUserId: vi.fn() }))
beforeEach(() => vi.clearAllMocks())
describe('own generation pages', () => {
  it('keeps legacy size 20 while explicitly paging at the server', async () => {
    await listGenerationJobs()
    expect(apiRequest).toHaveBeenCalledWith('/users/me/course-generation-jobs?page=0&size=20', expect.objectContaining({ auth: true, sessionBound: true }))
  })
  it('modal requests five from requested server page with no user-supplied owner', async () => {
    const page = { items: [{ jobId: 'test-job' }], page: 3, size: 5, hasNext: true }
    vi.mocked(apiRequest).mockResolvedValue(page)
    expect(await listGenerationJobs(3, 5)).toBe(page)
    expect(apiRequest).toHaveBeenCalledWith('/users/me/course-generation-jobs?page=3&size=5', { auth: true, sessionBound: true, timeoutMs: 15000 })
  })
})
