/**
 * copyText — 복사 성공 여부를 정직하게 돌려주는지.
 * 원 버그: Clipboard API가 거부돼도 성공 토스트가 떴다.
 * (테스트 환경이 node라 navigator/document 는 스텁으로 만든다)
 */
import { describe, it, expect, vi, afterEach } from 'vitest'
import { copyText } from './clipboard'

function stubDom ({ writeText, execResult }) {
  vi.stubGlobal('navigator', writeText ? { clipboard: { writeText } } : {})
  const ta = { style: {}, value: '', setAttribute: vi.fn(), select: vi.fn(), remove: vi.fn() }
  const execCommand = vi.fn().mockReturnValue(execResult)
  vi.stubGlobal('document', {
    createElement: () => ta,
    body: { appendChild: vi.fn() },
    execCommand,
  })
  return { execCommand }
}

afterEach(() => vi.unstubAllGlobals())

describe('copyText', () => {
  it('Clipboard API가 성공하면 true', async () => {
    stubDom({ writeText: vi.fn().mockResolvedValue(), execResult: false })
    expect(await copyText('제주시 애월읍')).toBe(true)
  })

  it('Clipboard API가 거부되면 execCommand 폴백으로 복사한다', async () => {
    const { execCommand } = stubDom({ writeText: vi.fn().mockRejectedValue(new Error('denied')), execResult: true })
    expect(await copyText('제주시 애월읍')).toBe(true)
    expect(execCommand).toHaveBeenCalledWith('copy')
  })

  it('폴백까지 실패하면 false — 성공으로 위장하지 않는다', async () => {
    stubDom({ writeText: vi.fn().mockRejectedValue(new Error('denied')), execResult: false })
    expect(await copyText('제주시 애월읍')).toBe(false)
  })
})
