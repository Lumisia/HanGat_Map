import { afterEach, expect, it, vi } from 'vitest'
import { apiRequest } from '../api/backendClient'
import CourseShareService from './CourseShareService'
vi.mock('../api/backendClient', () => ({ apiRequest: vi.fn() }))
afterEach(() => vi.clearAllMocks())
it('opening status never creates a link; mutations use owner authentication', async () => {
  vi.mocked(apiRequest).mockResolvedValue({ active: false, token: null })
  expect(await CourseShareService.status('12')).toEqual({ active: false, token: null })
  expect(apiRequest).toHaveBeenLastCalledWith('/courses/12/share', { method: 'GET', auth: true })
  vi.mocked(apiRequest).mockResolvedValue({ active: true, token: 'abc' })
  await CourseShareService.create('12')
  expect(apiRequest).toHaveBeenLastCalledWith('/courses/12/share', { method: 'POST', auth: true })
  vi.mocked(apiRequest).mockResolvedValue({ active: false, token: null })
  await CourseShareService.revoke('12')
  expect(apiRequest).toHaveBeenLastCalledWith('/courses/12/share', { method: 'DELETE', auth: true })
})
it('public requests are anonymous and preserve nullable forecast data', async () => {
  vi.mocked(apiRequest).mockResolvedValue({ title: '여행', start_date: '2026-09-20', end_date: '2026-09-20', transport: null, days: [{ day_no: 1, visit_date: '2026-09-20', items: [] }] })
  expect((await CourseShareService.getPublic('a/b')).days[0].items).toEqual([])
  expect(apiRequest).toHaveBeenLastCalledWith('/shared-courses/a%2Fb', { auth: false })
})
it('malformed payloads and unavailable links cannot become fake courses', async () => {
  vi.mocked(apiRequest).mockResolvedValue({ title: 'bad', days: [{ items: null }] })
  await expect(CourseShareService.getPublic('bad')).rejects.toThrow()
  vi.mocked(apiRequest).mockRejectedValue({ status: 404, code: 3310 })
  await expect(CourseShareService.getPublic('revoked')).rejects.toMatchObject({ code: 3310 })
})
