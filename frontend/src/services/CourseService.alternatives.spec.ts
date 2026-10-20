import { afterEach, expect, it, vi } from 'vitest'
import CourseService from './CourseService'

afterEach(() => vi.unstubAllGlobals())

it('requests the straight-distance snapshot without an itinerary date or a candidate limit', async () => {
  const snapshot = { forecast_date: '2026-09-18', distance_basis: 'STRAIGHT_LINE', places: [] }
  const fetch = vi.fn().mockResolvedValue(new Response(JSON.stringify({ success: true, code: 2000, result: snapshot }), {
    status: 200, headers: { 'Content-Type': 'application/json' },
  }))
  vi.stubGlobal('fetch', fetch)
  expect(await CourseService.getStraightAlternatives(1159, [197, 63])).toEqual(snapshot)
  const url = new URL(fetch.mock.calls[0][0])
  expect(url.pathname).toBe('/places/1159/straight-alternatives')
  expect([...url.searchParams.entries()]).toEqual([['exclude', '197,63']])
  expect(fetch).toHaveBeenCalledTimes(1)
})
