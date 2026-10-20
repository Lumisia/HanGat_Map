import { describe, it, expect, vi, afterEach } from 'vitest'
import { toMapCourse, stashAiCourse, popAiCourse, type AiCourseResult } from './CourseBridge'
import fixture from './__fixtures__/aiCourse.json'

/** 픽스처는 2026-09-01 Gemini 실응답이다 - 서부·자연·1박2일 */
const real = fixture as unknown as AiCourseResult

afterEach(() => vi.unstubAllGlobals())

describe('AI 코스 → 지도 코스 변환', () => {
  it('실응답 6곳이 일차·순서대로 변환된다', () => {
    const course = toMapCourse(real)

    expect(course.days).toBe(2)
    expect(course.stops).toHaveLength(6)
    expect(course.stops.map(s => s.d)).toEqual([1, 1, 1, 2, 2, 2])
    expect(course.stops[0].o.n).toBe('고내포구')
    expect(course.stops[0].t).toBe('10:00')
  })

  it('혼잡도는 있는 값만 옮기고 평균도 그 값들로만 낸다', () => {
    const course = toMapCourse(real)

    // 실응답: 6곳 중 2곳은 혼잡 미제공(이름 매칭 실패 케이스)
    const rated = course.stops.filter(s => s.c != null)
    expect(rated).toHaveLength(4)
    expect(course.stops[1].c).toBe(76)          // 한담해변 76.15 → 반올림
    expect(course.avg).toBe(Math.round((76 + 60 + 37 + 30) / 4))
    // 비교값(pav)은 응답에 없다 - null 이어야 화면이 비교 문구를 숨긴다
    expect(course.pav).toBeNull()
  })

  it('적재 장소와 매칭되면 그 객체를 핀으로 쓴다 - 상세 열기·시리즈가 살아난다', () => {
    const 적재장소 = { id: 999, n: '한담해변', x: 126.31, y: 33.46, series: [10, 20] }
    const course = toMapCourse(real, (id, name) => (name === '한담해변' ? 적재장소 as never : null))

    const stop = course.stops.find(s => s.o.n === '한담해변')
    expect(stop?.o).toBe(적재장소)
  })

  it('좌표 없는 아이템은 지도에 못 그리므로 뺀다', () => {
    const broken: AiCourseResult = {
      ...real,
      days: [{
        day_no: 1,
        visit_date: '2026-09-01',
        items: [{ ...real.days[0].items[0], latitude: null }]
      }]
    }

    expect(toMapCourse(broken).stops).toHaveLength(0)
  })

  it('경비는 아이템 비용 합, 이동은 분 합이다', () => {
    const course = toMapCourse(real)

    expect(course).not.toHaveProperty('bud')
    expect(course.spent).toBe(course.stops.reduce((a, b) => a + b.cost, 0))
    expect(course.move).toBe(course.stops.reduce((a, b) => a + b.mv, 0))
  })
})

describe('페이지 간 전달 (sessionStorage)', () => {
  it('담은 것을 그대로 꺼낸다', () => {
    const store = new Map<string, string>()
    vi.stubGlobal('sessionStorage', {
      setItem: (k: string, v: string) => store.set(k, v),
      getItem: (k: string) => store.get(k) ?? null
    })

    expect(stashAiCourse(real)).toBe(true)
    expect(popAiCourse()?.days).toHaveLength(2)
  })

  it('저장 실패·없음이면 조용히 null - 지도가 평소대로 뜬다', () => {
    vi.stubGlobal('sessionStorage', {
      setItem: () => { throw new Error('quota') },
      getItem: () => null
    })

    expect(stashAiCourse(real)).toBe(false)
    expect(popAiCourse()).toBeNull()
  })
})
