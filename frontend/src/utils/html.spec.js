import { describe, expect, it } from 'vitest'
import { escapeHtml } from './html'

describe('escapeHtml (최종점검 #32 - 지도 오버레이 장소명·메뉴)', () => {
  it('태그·속성 문자 5종을 엔티티로 바꿔 마크업이 글자로만 보이게 한다', () => {
    expect(escapeHtml('<img src=x onerror="a">')).toBe('&lt;img src=x onerror=&quot;a&quot;&gt;')
    expect(escapeHtml("'")).toBe('&#39;')
  })
  it('& 를 먼저 바꿔 실제 장소명(바램목장&카페)이 그대로 읽힌다', () => {
    expect(escapeHtml('바램목장&카페')).toBe('바램목장&amp;카페')
  })
  it('특수문자 없는 한글 이름은 그대로', () => {
    expect(escapeHtml('한경면')).toBe('한경면')
  })
  it('없는 값은 빈 문자열, 숫자는 문자열로 - 이름표에 undefined 가 찍히지 않게', () => {
    expect(escapeHtml(null)).toBe('')
    expect(escapeHtml(undefined)).toBe('')
    expect(escapeHtml(12)).toBe('12')
  })
})
