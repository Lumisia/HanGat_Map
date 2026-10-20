import { describe, expect, it } from 'vitest'
import { introOf, paragraphsOf, firstMenu } from './intro'

describe('introOf (MAP_008)', () => {
  it('returns the KTO overview text trimmed', () => {
    expect(introOf('  표선면 가시리는 제주도 내에서 …  ')).toBe('표선면 가시리는 제주도 내에서 …')
  })

  it('never shows a menu paragraph as an introduction', () => {
    expect(introOf('대표메뉴: 순대국밥 9,000원 · 해물순두부 9,000원')).toBeNull()
  })

  it('firstMenu - 메뉴 문단의 첫 메뉴와 가격, 메뉴 문단이 아니면 null', () => {
    expect(firstMenu('대표메뉴: 똣똣라면(오리지널) 6,500원 · 똣똣라면(순한맛) 6,500원')).toEqual({ n: '똣똣라면(오리지널)', p: '6,500원' })
    expect(firstMenu('대표메뉴: 성게비빔밥')).toEqual({ n: '성게비빔밥', p: '' })
    expect(firstMenu('제주교육박물관은 …')).toBeNull()
    expect(firstMenu(null)).toBeNull()
  })

  it('is null when there is nothing to say', () => {
    expect(introOf(null)).toBeNull()
    expect(introOf('   ')).toBeNull()
  })

  it('splits into paragraphs of three sentences without changing a single character', () => {
    const t = '첫째다. 둘째요. 셋째! 넷째? 다섯째다. 여섯째다. 일곱째다.'
    const p = paragraphsOf(t)
    expect(p).toEqual(['첫째다. 둘째요. 셋째!', '넷째? 다섯째다. 여섯째다.', '일곱째다.'])
    expect(p.join(' ')).toBe(t)
  })

  it('does not cut inside a word that merely ends with 다 or 요', () => {
    expect(paragraphsOf('아름다운 바다 위의 요트가 보인다. 끝.')).toEqual(['아름다운 바다 위의 요트가 보인다. 끝.'])
  })

  it('keeps the original line breaks as paragraph boundaries', () => {
    expect(paragraphsOf('축제는 3월에 열린다.\n둘째 줄이다. 셋째다. 넷째다.')).toEqual(['축제는 3월에 열린다.', '둘째 줄이다. 셋째다. 넷째다.'])
  })

  it('is one paragraph when there is no sentence end, and empty for nothing', () => {
    expect(paragraphsOf('문장 끝이 없는 글')).toEqual(['문장 끝이 없는 글'])
    expect(paragraphsOf('')).toEqual([])
    expect(paragraphsOf(null)).toEqual([])
  })
})
