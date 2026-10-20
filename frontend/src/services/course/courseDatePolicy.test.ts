import { describe, expect, it } from 'vitest'
import { courseDateError, courseDateWindow } from './courseDatePolicy'

describe('AI course KST forecast date policy', () => {
  it('accepts KST today through the inclusive thirtieth calendar day', () => {
    expect(courseDateWindow('2026-09-11')).toEqual({ minimum: '2026-09-11', maximum: '2026-10-10' })
    expect(courseDateError('2026-09-11', '2026-10-10', '2026-09-11')).toBe('')
  })

  it('distinguishes invalid ranges from missing forecast data inside the window', () => {
    expect(courseDateError('2026-09-10', '2026-09-11', '2026-09-11')).toContain('30일')
    expect(courseDateError('2026-09-11', '2026-10-11', '2026-09-11')).toContain('30일')
    expect(courseDateError('2026-09-20', '2026-09-22', '2026-09-11')).toBe('')
  })
})
