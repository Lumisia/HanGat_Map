import { beforeEach, describe, expect, it, vi } from 'vitest'

const apiRequest = vi.fn()
const apiGet = vi.fn()
vi.mock('./backendClient.js', () => ({ apiRequest }))
vi.mock('../services/apiClient', () => ({ apiGet }))

const { listFavorites, removeFavorite, parseHours, sortItems, toItem } = await import('./favorites.js')

const ROW = {
  placeId: 5, name: '금오름', regionCode: 'WEST', regionName: '서부', categoryCode: 'TOURIST', categoryName: '관광지',
  tagName: '오름', roadAddress: '제주 제주시 한림읍 금악리', lotAddress: null, latitude: 33.35, longitude: 126.31,
  phone: null, operatingHoursText: '상시 개방', useFeeText: null, free: false, parkingAvailable: true, toiletAvailable: null,
  businessStatus: 'UNKNOWN', ratingAvg: null, reviewCount: 0, imageUrl: 'https://img/thumb.jpg', crowdRate: 37.5,
  favoritedAt: '2026-09-07T10:00:00'
}

beforeEach(() => {
  apiRequest.mockReset()
  apiGet.mockReset()
})

describe('parseHours', () => {
  it('reads only a plain HH:MM~HH:MM range', () => {
    expect(parseHours('09:00~18:00')).toEqual({ open: '09:00', close: '18:00' })
    expect(parseHours('10:00 - 19:30')).toEqual({ open: '10:00', close: '19:30' })
  })

  it('refuses seasonal or annotated text instead of guessing', () => {
    expect(parseHours('- 6월~8월 09:00~18:30 (입장 마감 18:00)')).toBeNull()
    expect(parseHours('상시 개방')).toBeNull()
    expect(parseHours(null)).toBeNull()
  })
})

describe('toItem', () => {
  it('maps the backend row to the tab shape without inventing data', () => {
    const item = toItem(ROW, { kind: '맑음', t: 29 })
    expect(item).toMatchObject({
      placeId: 5, name: '금오름', category: '오름', region: '서부', addr: '제주 제주시 한림읍 금악리',
      x: 126.31, y: 33.35, park: true, toilet: null, indoor: false,
      hours: null, hoursText: '상시 개방', feeText: null, free: false, fee: null,
      crowd: 38, crowdTier: 'calm', weather: { kind: '맑음', t: 29 }, rating: null, reviewCount: 0
    })
  })

  it('leaves crowd null and category fallback when the backend has none', () => {
    const item = toItem({ ...ROW, crowdRate: null, tagName: null }, null)
    expect(item.crowd).toBeNull()
    expect(item.crowdTier).toBe('none')
    expect(item.category).toBe('관광지')
    expect(item.weather).toBeNull()
  })
})

describe('sortItems', () => {
  const rows = [
    { name: '협재해수욕장', category: '해수욕장', createdAt: '2026-09-01T00:00:00' },
    { name: '금오름', category: '오름', createdAt: '2026-09-03T00:00:00' },
    { name: '새별오름', category: '오름', createdAt: '2026-09-02T00:00:00' }
  ]
  it('sorts recent first by default, then by name, then by category+name', () => {
    expect(sortItems([...rows], 'recent').map(r => r.name)).toEqual(['금오름', '새별오름', '협재해수욕장'])
    expect(sortItems([...rows], 'name').map(r => r.name)).toEqual(['금오름', '새별오름', '협재해수욕장'])
    expect(sortItems([...rows], 'category').map(r => r.name)).toEqual(['금오름', '새별오름', '협재해수욕장'])
  })
})

describe('listFavorites / removeFavorite', () => {
  it('calls the authenticated endpoint and survives a weather failure', async () => {
    apiRequest.mockResolvedValue([ROW])
    apiGet.mockRejectedValue(new Error('slow'))

    const res = await listFavorites({ sort: 'name' })

    expect(apiRequest).toHaveBeenCalledWith('/favorites', { auth: true })
    expect(res.total).toBe(1)
    expect(res.items[0].weather).toBeNull()
  })

  it('attaches today\'s weather when the public forecast has it', async () => {
    apiRequest.mockResolvedValue([ROW])
    const today = new Date()
    const iso = `${today.getFullYear()}-${String(today.getMonth() + 1).padStart(2, '0')}-${String(today.getDate()).padStart(2, '0')}`
    apiGet.mockResolvedValue([{ date: iso, sky: '구름많음', maxTemp: 27, minTemp: 22, rainProb: 20 }])

    const res = await listFavorites()

    expect(res.items[0].weather).toEqual({ kind: '구름', t: 27 })
  })

  it('removes through DELETE /favorites/{id} with auth', async () => {
    apiRequest.mockResolvedValue({ placeId: 5, favorited: false })
    await expect(removeFavorite(5)).resolves.toEqual({ placeId: 5, favorited: false })
    expect(apiRequest).toHaveBeenCalledWith('/favorites/5', { method: 'DELETE', auth: true })
  })
})

describe('toItem closed flag', () => {
  it('marks CLOSED places so the tab can label them 폐업 instead of hiding them', () => {
    expect(toItem(ROW, null).closed).toBe(false)
    expect(toItem({ ...ROW, businessStatus: 'CLOSED' }, null).closed).toBe(true)
  })
})

describe('toItem crowd dot', () => {
  it('keeps the gray dot for a tourist spot without forecast but drops it for a restaurant', () => {
    expect(toItem({ ...ROW, crowdRate: null }, null).crowdTier).toBe('none')
    expect(toItem({ ...ROW, crowdRate: null, categoryCode: 'FOOD' }, null).crowdTier).toBeNull()
    expect(toItem({ ...ROW, crowdRate: 55, categoryCode: 'FOOD' }, null).crowdTier).toBe('mid')
  })
})
