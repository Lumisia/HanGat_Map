import { beforeEach, describe, expect, it, vi } from 'vitest'
import { createPinia, setActivePinia } from 'pinia'
import { todayKst, addCalendarDays } from '../../utils/format.js'
import { useTravelStore } from './travel'

// setup 스토어라 날짜는 모듈 평가가 아니라 useTravelStore() 시점에 계산된다 - pinia만 새로 만들면 된다
beforeEach(() => setActivePinia(createPinia()))

describe('메인 퀵스타트 기본 일정 (담당 정동현)', () => {
  it('오늘부터 2박 3일이다 - 고정 날짜를 두면 지나간 일정이 뜬다', () => {
    const { condition } = useTravelStore()

    expect(condition.startDate).toBe(todayKst())
    expect(condition.endDate).toBe(addCalendarDays(todayKst(), 2))
  })

  it('한국 날짜를 쓴다 - 브라우저 시계가 UTC여도 하루 밀리지 않는다', () => {
    // 한국 2026-09-08 08:00 = UTC 2026-09-07 23:00
    vi.useFakeTimers()
    vi.setSystemTime(new Date('2026-09-07T23:00:00Z'))
    try {
      const { condition } = useTravelStore()

      expect(condition.startDate).toBe('2026-09-08')
      expect(condition.endDate).toBe('2026-09-10')
    } finally {
      vi.useRealTimers()
    }
  })

  it('자정을 넘겨 다시 열면 손대지 않은 기본 일정은 오늘 기준으로 되돌아온다', () => {
    vi.useFakeTimers()
    try {
      vi.setSystemTime(new Date('2026-09-08T14:55:00Z')) // KST 09-08 23:55
      const store = useTravelStore()
      expect(store.condition.startDate).toBe('2026-09-08')

      vi.setSystemTime(new Date('2026-09-08T15:05:00Z')) // KST 09-09 00:05
      store.refreshDefaultDates()

      expect(store.condition.startDate).toBe('2026-09-09')
      expect(store.condition.endDate).toBe('2026-09-11')
    } finally {
      vi.useRealTimers()
    }
  })

  it('사용자가 고른 날짜는 자정이 지나도 그대로 둔다', () => {
    vi.useFakeTimers()
    try {
      vi.setSystemTime(new Date('2026-09-08T14:55:00Z'))
      const store = useTravelStore()
      store.condition.startDate = '2026-10-01'
      store.condition.endDate = '2026-10-03'

      vi.setSystemTime(new Date('2026-09-08T15:05:00Z'))
      store.refreshDefaultDates()

      expect(store.condition.startDate).toBe('2026-10-01')
      expect(store.condition.endDate).toBe('2026-10-03')
    } finally {
      vi.useRealTimers()
    }
  })
})
