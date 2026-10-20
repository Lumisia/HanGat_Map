import { describe, expect, it, vi } from 'vitest'
import { useSavedCourseExplorer, sheetHeight } from './useSavedCourseExplorer'
import type { CourseDetail } from '../services/CourseService'

const detail = (id: string) => ({ id, days: [] } as unknown as CourseDetail)
describe('저장 코스 탐색', () => {
  it('느린 이전 응답이 새로 선택한 코스를 덮어쓰지 않는다', async () => {
    let finish!: (value: CourseDetail) => void
    const fetch = vi.fn((id: string) => id === '1'
      ? new Promise<CourseDetail>(resolve => { finish = resolve }) : Promise.resolve(detail(id)))
    const model = useSavedCourseExplorer(fetch)
    const first = model.select('1')
    await model.select('2')
    finish(detail('1')); await first
    expect(model.course.value?.id).toBe('2')
    expect(model.selectedId.value).toBe('2')
  })
  it('접었다 펴도 현재 일정과 지도는 유지하고 다시 조회하지 않는다', async () => {
    const fetch = vi.fn(async (id: string) => detail(id))
    const model = useSavedCourseExplorer(fetch)
    await model.select('1'); await model.select('1')
    expect(model.expanded.value).toBe(false)
    expect(model.course.value?.id).toBe('1')
    await model.select('1')
    expect(model.expanded.value).toBe(true)
    expect(fetch).toHaveBeenCalledTimes(1)
  })
  it('조회 실패를 보여주고 동일 코스를 재시도한다', async () => {
    const fetch = vi.fn().mockRejectedValueOnce(new Error('offline')).mockResolvedValue(detail('1'))
    const model = useSavedCourseExplorer(fetch)
    await model.select('1')
    expect(model.failed.value).toBe(true)
    expect(model.loading.value).toBe(false)
    await model.select('1')
    expect(model.course.value?.id).toBe('1')
    expect(model.failed.value).toBe(false)
  })
  it('페이지 이동 중 남은 응답은 비운 화면에 표시하지 않는다', async () => {
    let finish!: (value: CourseDetail) => void
    const model = useSavedCourseExplorer(() => new Promise(resolve => { finish = resolve }))
    const request = model.select('1'); model.reset(); finish(detail('1')); await request
    expect(model.course.value).toBeNull()
    expect(model.selectedId.value).toBe('')
  })
  it('손잡이를 위로 밀면 커지고 화면 밖으로 벗어나지 않는다', () => {
    expect(sheetHeight(55, -100, 500)).toBe(75)
    expect(sheetHeight(55, 9999, 500)).toBe(18)
    expect(sheetHeight(55, -9999, 500)).toBe(90)
    expect(sheetHeight(55, 20, 0)).toBe(55)
  })
})
