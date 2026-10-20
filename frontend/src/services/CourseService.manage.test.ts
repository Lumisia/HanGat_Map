import { afterEach, describe, expect, it, vi } from 'vitest'
import { apiRequest } from '../api/backendClient'
import { ApiError } from '../api/errors.js'
import CourseService from './CourseService'

vi.mock('../api/backendClient', () => ({ apiRequest: vi.fn(), getBackendUserId: vi.fn(() => 7) }))

afterEach(() => vi.clearAllMocks())

describe('CourseService 저장 코스 관리 (MY_001, 담당 정동현)', () => {
  it('keeps estimated spending without a total budget', async () => {
    vi.mocked(apiRequest).mockResolvedValue({ content: [
      { id: 1, estimated_cost_min: null, estimated_cost_max: null, highlight_names: [] },
      { id: 2, estimated_cost_min: 16000, estimated_cost_max: 24000, highlight_names: [] },
    ], totalPages: 1, totalElements: 2 })
    const list = await CourseService.getSavedCourses()
    expect(list.cards.every(card => !('budgetTotal' in card))).toBe(true)
    vi.mocked(apiRequest).mockResolvedValue({ id: 2,
      budget_summary: { has_cost_data: true, estimated_max: 24000 }, days: [] })
    const detail = await CourseService.getCourseDetail('2')
    expect(detail).not.toHaveProperty('budgetTotal')
    expect(detail?.budgetSummary?.estimated_max).toBe(24000)
  })
  it('이름 변경은 본인 인증으로 PATCH 하고 서버가 확정한 제목을 돌려준다', async () => {
    vi.mocked(apiRequest).mockResolvedValue({ id: 10, title: '동부 느긋하게' })

    const title = await CourseService.renameCourse('10', '동부 느긋하게')

    expect(apiRequest).toHaveBeenCalledWith('/courses/10', { method: 'PATCH', body: { title: '동부 느긋하게' }, auth: true })
    expect(title).toBe('동부 느긋하게')
  })

  it('남의 코스 3307은 그대로 던진다 - 화면이 이유를 보여준다', async () => {
    vi.mocked(apiRequest).mockRejectedValue(new ApiError(403, 3307, '본인 코스만 수정할 수 있습니다.'))

    await expect(CourseService.renameCourse('10', 'x')).rejects.toMatchObject({ code: 3307 })
  })

  it('삭제는 본인 인증으로 DELETE 한다', async () => {
    vi.mocked(apiRequest).mockResolvedValue(null)

    await CourseService.deleteCourse('10')

    expect(apiRequest).toHaveBeenCalledWith('/courses/10', { method: 'DELETE', auth: true })
  })
})
