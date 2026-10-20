import { expect, it } from 'vitest'
import { stayDuration } from './stayDuration'
it('확정된 시작·종료 시간으로 체류 시간을 표시하고 미정·역전 시간은 숨긴다', () => {
  expect(stayDuration('10:30:00', '11:50:00')).toBe('1시간 20분')
  expect(stayDuration('10:30', '11:00')).toBe('30분')
  expect(stayDuration('10:30', null)).toBeNull()
  expect(stayDuration('11:30', '10:30')).toBeNull()
})
