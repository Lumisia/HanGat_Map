import { afterEach, expect, it, vi } from 'vitest'
import { shareToKakao } from './useKakaoShare.js'
afterEach(() => vi.unstubAllGlobals())
it('course payload uses course button label synchronously and keeps place default', () => {
  const sendDefault = vi.fn()
  vi.stubGlobal('window', { Kakao: { isInitialized: () => true, Share: { sendDefault } } })
  shareToKakao({ title: '코스', description: '여행', url: 'https://example.com/share/abc', buttonTitle: '코스 보기' })
  expect(sendDefault).toHaveBeenLastCalledWith(expect.objectContaining({ buttonTitle: '코스 보기', link: { webUrl: 'https://example.com/share/abc', mobileWebUrl: 'https://example.com/share/abc' } }))
  shareToKakao({ title: '장소', description: '', url: 'https://example.com/place', imageUrl: 'https://example.com/a.png' })
  expect(sendDefault).toHaveBeenLastCalledWith(expect.objectContaining({ buttons: [expect.objectContaining({ title: '지도에서 보기' })] }))
})
