import { describe, expect, it, vi } from 'vitest'
import { useGenerationHistory } from './generationHistory.js'
const deferred = () => { let resolve; const promise = new Promise(r => { resolve = r }); return { promise, resolve } }
describe('member generation request pages', () => {
  it.each([0, 1, 5, 6, 27])('uses server pages of five for %i requests', async count => {
    const all = Array.from({ length: count }, (_, id) => ({ jobId: id }))
    const fetch = vi.fn(async (page, size) => ({ items: all.slice(page * size, (page + 1) * size), hasNext: count > (page + 1) * size }))
    const state = useGenerationHistory(fetch)
    await state.load(); expect(fetch).not.toHaveBeenCalled()
    await state.show(); expect(fetch).toHaveBeenLastCalledWith(0, 5)
    expect(state.items.value).toHaveLength(Math.min(count, 5))
    while (state.hasNext.value) await state.load(state.page.value + 1)
    expect(state.page.value).toBe(Math.max(0, Math.ceil(count / 5) - 1))
  })
  it('discards an old page arriving after a later page', async () => {
    const old = deferred(), next = deferred()
    const fetch = vi.fn().mockReturnValueOnce(old.promise).mockReturnValueOnce(next.promise)
    const state = useGenerationHistory(fetch)
    const first = state.show(), second = state.load(1)
    next.resolve({ items: [{ jobId: 'new' }], hasNext: false }); await second
    old.resolve({ items: [{ jobId: 'old' }], hasNext: true }); await first
    expect(state.items.value).toEqual([{ jobId: 'new' }]); expect(state.page.value).toBe(1)
  })
  it('close/account reset invalidates in-flight data and stops closed polling', async () => {
    const pending = deferred(); const fetch = vi.fn().mockReturnValue(pending.promise)
    const state = useGenerationHistory(fetch); const first = state.show()
    state.show(); expect(fetch).toHaveBeenCalledTimes(1)
    state.close(); pending.resolve({ items: [{ jobId: 'other-user' }], hasNext: true }); await first
    await state.load(); expect(fetch).toHaveBeenCalledTimes(1)
    expect(state.items.value).toEqual([]); expect(state.page.value).toBe(0); expect(state.busy.value).toBe(false)
  })
  it('distinguishes failure and supports explicit retry, rejecting invalid pages', async () => {
    const fetch = vi.fn().mockRejectedValueOnce(Error('offline')).mockResolvedValue({ items: [], hasNext: false })
    const state = useGenerationHistory(fetch); await state.show()
    expect(state.error.value).toContain('불러오지 못'); await state.load()
    expect(state.error.value).toBe(''); await state.load(-1); await state.load(10001)
    expect(fetch).toHaveBeenCalledTimes(2)
  })
})
