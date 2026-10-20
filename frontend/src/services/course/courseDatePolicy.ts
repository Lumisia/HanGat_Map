import { addCalendarDays, todayKst } from '../../utils/format.js'

export const COURSE_FORECAST_WINDOW_DAYS = 30

export function courseDateWindow(today = todayKst()) {
  return { minimum: today, maximum: addCalendarDays(today, COURSE_FORECAST_WINDOW_DAYS - 1) }
}

export function courseDateError(start: string, end: string, today = todayKst()): string {
  if (!start || !end) return '여행 시작일과 종료일을 모두 입력해 주세요.'
  if (start > end) return '여행 종료일은 시작일보다 빠를 수 없어요.'
  const { minimum, maximum } = courseDateWindow(today)
  if (start < minimum || end > maximum) return '여행 날짜는 오늘부터 30일 이내로 선택해 주세요.'
  return ''
}
