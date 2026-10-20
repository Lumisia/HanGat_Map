import { describe, expect, it } from 'vitest'
import { levelOf, congestionLabel } from './congestion'
import { bestDay, rank30 } from './crowd'

describe('shared congestion contract', () => {
  it.each([[0,'QUIET'],[33.3,'QUIET'],[39.9,'QUIET'],[40,'NORMAL'],[69.9,'NORMAL'],[70,'CROWDED'],[100,'CROWDED']])(
    '%s is %s', (rate, expected) => expect(levelOf(Number(rate))).toBe(expected))
  it('does not turn missing forecasts into quiet', () => {
    expect(levelOf(null)).toBeNull()
    expect(levelOf(undefined)).toBeNull()
    expect(congestionLabel(null)).toBe('정보 없음')
    expect(congestionLabel(33.3)).toBe('한산')
  })
  it('excludes missing days without rescaling absolute rates', () => {
    const place = { series: [null, 33.3, 70, null] }
    expect(bestDay(place).c).toBe(33.3)
    expect(rank30(place, 0)).toBeNull()
    expect(rank30(place, 1)).toBe(1)
    expect(bestDay({ series: [null, null] }).c).toBeNull()
    expect(levelOf(place.series[1])).toBe('QUIET')
  })
})
