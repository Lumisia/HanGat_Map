import { afterEach, describe, expect, it, vi } from 'vitest'
import { createKakaoPlaceSearchInput } from './kakaoPlaceSearchInput'

afterEach(() => vi.useRealTimers())

describe('Kakao place search IME trigger', () => {
  it('두 글자 입력만으로 debounce 뒤 검색한다', () => {
    vi.useFakeTimers(); const search = vi.fn(); const input = createKakaoPlaceSearchInput(vi.fn(), search)
    input.input('제주'); vi.advanceTimersByTime(280)
    expect(search).toHaveBeenCalledWith('제주')
  })

  it('한글 두 글자가 조합 중이어도 멈추면 현재 입력값으로 검색한다', () => {
    vi.useFakeTimers(); const search = vi.fn(); const input = createKakaoPlaceSearchInput(vi.fn(), search)
    input.compositionStart(); input.input('제'); input.input('제주'); vi.advanceTimersByTime(280)
    expect(search).toHaveBeenCalledTimes(1); expect(search).toHaveBeenCalledWith('제주')
  })

  it('조합 완료 시 마지막 글자를 반영하고 이전 예약을 중복 실행하지 않는다', () => {
    vi.useFakeTimers(); const search = vi.fn(); const input = createKakaoPlaceSearchInput(vi.fn(), search)
    input.compositionStart(); input.input('제주'); input.compositionEnd('제주도'); vi.advanceTimersByTime(280)
    expect(search).toHaveBeenCalledTimes(1); expect(search).toHaveBeenCalledWith('제주도')
  })

  it('빠른 검색어 변경은 마지막 검색어만 실행한다', () => {
    vi.useFakeTimers(); const search = vi.fn(); const input = createKakaoPlaceSearchInput(vi.fn(), search)
    input.input('제주'); input.input('제주시'); vi.advanceTimersByTime(280)
    expect(search).toHaveBeenCalledTimes(1); expect(search).toHaveBeenCalledWith('제주시')
  })

  it('두 글자 미만으로 지우면 예약된 검색을 취소한다', () => {
    vi.useFakeTimers(); const search = vi.fn(); const input = createKakaoPlaceSearchInput(vi.fn(), search)
    input.input('제주'); input.input('제'); vi.advanceTimersByTime(280)
    expect(search).not.toHaveBeenCalled()
  })
})
