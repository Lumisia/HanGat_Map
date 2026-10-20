import { createPinia, setActivePinia } from 'pinia'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { useAuthStore } from './auth.js'

vi.stubGlobal('localStorage', {
  getItem: () => null,
  setItem: () => {},
  removeItem: () => {},
})

/* 백엔드 찜 API 는 목으로 - 여기서 보는 건 로그인 게이트·낙관적 갱신·실패 되돌리기다 */
const favApi = { ids: vi.fn(), add: vi.fn(), remove: vi.fn() }
vi.mock('../services/map/FavoriteApiService', () => ({ default: favApi, FavoriteApiService: favApi }))

const mapStore = await import('./mapStore.js')

const GEUM = { id: 7, n: '금오름' }

beforeEach(() => {
  setActivePinia(createPinia())
  mapStore.state.favIds = []
  mapStore.state.toast = ''
  favApi.ids.mockReset()
  favApi.add.mockReset()
  favApi.remove.mockReset()
})

describe('map favorites (MAP_009)', () => {
  it('lets a logged-in member favorite a place and reflects the server state', async () => {
    const auth = useAuthStore()
    auth.user = { userId: 1, nickname: '한갓이', email: 'test@hangat.kr' }
    favApi.add.mockResolvedValue({ placeId: 7, favorited: true })

    await expect(mapStore.toggleFav(GEUM)).resolves.toBe(true)

    expect(favApi.add).toHaveBeenCalledWith(7)
    expect(mapStore.state.favIds).toEqual([7])
    expect(mapStore.isFav(GEUM)).toBe(true)
    expect(mapStore.state.toast).toContain('마이페이지')
    expect(mapStore.state).not.toHaveProperty('user')
  })

  it('removes an existing favorite through the API', async () => {
    useAuthStore().user = { userId: 1 }
    mapStore.state.favIds = [7]
    favApi.remove.mockResolvedValue({ placeId: 7, favorited: false })

    await expect(mapStore.toggleFav(GEUM)).resolves.toBe(true)

    expect(favApi.remove).toHaveBeenCalledWith(7)
    expect(mapStore.state.favIds).toEqual([])
  })

  it('blocks guests with a toast only - no API call, no redirect', async () => {
    useAuthStore().user = null

    await expect(mapStore.toggleFav(GEUM)).resolves.toBe(false)

    expect(favApi.add).not.toHaveBeenCalled()
    expect(mapStore.state.favIds).toEqual([])
    expect(mapStore.state.toast).toBe('찜은 로그인이 필요해요')
  })

  it('rolls the heart back when the server call fails', async () => {
    useAuthStore().user = { userId: 1 }
    favApi.add.mockRejectedValue(new Error('boom'))

    await expect(mapStore.toggleFav(GEUM)).resolves.toBe(false)

    expect(mapStore.state.favIds).toEqual([])
    expect(mapStore.state.toast).toContain('저장하지 못했어요')
  })

  it('loads the member favorites on login and clears them on logout', async () => {
    favApi.ids.mockResolvedValue([3, 4])

    await mapStore.loadFavorites(1)
    expect(mapStore.state.favIds).toEqual([3, 4])
    expect(mapStore.isFav({ id: 3 })).toBe(true)

    await mapStore.loadFavorites(null)
    expect(mapStore.state.favIds).toEqual([])
    expect(favApi.ids).toHaveBeenCalledTimes(1)
  })

  it('keeps the map usable when the favorites request fails', async () => {
    favApi.ids.mockRejectedValue(new Error('down'))
    const spy = vi.spyOn(console, 'error').mockImplementation(() => {})

    await expect(mapStore.loadFavorites(1)).resolves.toBeUndefined()
    expect(mapStore.state.favIds).toEqual([])
    spy.mockRestore()
  })
})
