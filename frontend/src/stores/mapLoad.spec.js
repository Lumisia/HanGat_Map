import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'

/* 서비스는 전부 흉내낸다 - 여기서 보는 건 "언제 다시 부르는가"뿐 */
const placeApi = { getAll: vi.fn(), getLayer: vi.fn(), search: vi.fn(), getById: vi.fn(), getDetail: vi.fn() }
const crowdApi = { getForecast: vi.fn() }
const weatherApi = { load: vi.fn(), reset: vi.fn(), byDate: vi.fn(), issuedAt: vi.fn() }
const favApi = { ids: vi.fn(), add: vi.fn(), remove: vi.fn() }
vi.mock('../services/map/MapPlaceService', () => ({ default: placeApi, MapPlaceService: placeApi, LAZY_LAYERS: ['food', 'dine', 'stay', 'cafe', 'cvs', 'mart'] }))
vi.mock('../services/map/CrowdService', () => ({ default: crowdApi, CrowdService: crowdApi, attachSeries: vi.fn() }))
vi.mock('../services/map/MapWeatherService', () => ({ default: weatherApi, WeatherService: weatherApi }))
vi.mock('../services/map/FavoriteApiService', () => ({ default: favApi, FavoriteApiService: favApi }))

const layers = () => ({ spot: [{ id: 1, n: '성산일출봉', x: 126.94, y: 33.46 }], food: [], dine: [], cafe: [], cvs: [], stay: [], mart: [] })
const ok = (failed = []) => ({ live: true, layers: layers(), fetched: ['spot'], failed })
const forecast = (live = true) => ({ live, from: '2026-09-12', days: live ? 3 : 0, values: {} })

/** Storage 흉내 - 탭 하나의 sessionStorage/localStorage */
function storage () {
  const m = new Map()
  return { getItem: k => (m.has(k) ? m.get(k) : null), setItem: (k, v) => m.set(k, String(v)), removeItem: k => m.delete(k) }
}

/* mapStore 는 "마지막으로 받은 시각"을 모듈 변수로 들고 있어 케이스마다 모듈을 새로 읽는다 */
async function freshStore () {
  vi.resetModules()
  vi.stubGlobal('sessionStorage', storage())
  vi.stubGlobal('localStorage', storage())
  return import('./mapStore.js')
}

beforeEach(() => {
  vi.useFakeTimers()
  // iso()는 실행 환경의 현지 날짜를 사용한다. 테스트도 같은 기준으로 시각을 만든다.
  // +09:00 문자열로 고정하면 UTC인 CI에서 자정 전후가 같은 날짜로 해석된다.
  vi.setSystemTime(new Date(2026, 8, 12, 10, 0))
  placeApi.getAll.mockReset().mockResolvedValue(ok())
  placeApi.getLayer.mockReset()
  placeApi.getById.mockReset()
  crowdApi.getForecast.mockReset().mockResolvedValue(forecast())
  weatherApi.load.mockReset().mockResolvedValue(true)
})
afterEach(() => { vi.useRealTimers(); vi.unstubAllGlobals() })

describe('findPlaceById - 공유 링크 복원 (최종점검 #49)', () => {
  const cafe = { id: 501, n: '카페', x: 126.5, y: 33.4, cat: 'CAFE', good: false }

  it('없는 장소는 상세 한 건만 물어보고 바로 "없음" - 지연 레이어를 받지 않는다', async () => {
    const s = await freshStore()
    await s.loadPlaces()
    placeApi.getById.mockResolvedValue({ place: null, missing: true })
    expect(await s.findPlaceById(99999999)).toEqual({ place: null, error: false })
    expect(placeApi.getLayer).not.toHaveBeenCalled()
  })

  it('카페 링크는 상세로 업종을 알아낸 뒤 카페 레이어 하나만 받고, 그 칩을 켠다', async () => {
    const s = await freshStore()
    await s.loadPlaces()
    placeApi.getById.mockResolvedValue({ place: cafe, missing: false })
    placeApi.getLayer.mockResolvedValue([cafe])
    const r = await s.findPlaceById(501)
    expect(placeApi.getLayer).toHaveBeenCalledTimes(1)
    expect(placeApi.getLayer).toHaveBeenCalledWith('cafe')
    expect(r.place.id).toBe(501)
    expect(s.state.layers.cafe).toHaveLength(1)   // 카페 레이어가 채워졌다
    expect(s.state.L.cafe).toBe(1)
  })

  it('레이어 목록에 없는 장소(폐업)는 상세 객체로 연다', async () => {
    const s = await freshStore()
    await s.loadPlaces()
    placeApi.getById.mockResolvedValue({ place: { ...cafe, id: 502, closed: true }, missing: false })
    placeApi.getLayer.mockResolvedValue([cafe])
    const r = await s.findPlaceById(502)
    expect(r).toMatchObject({ place: { id: 502 }, error: false })
  })

  it('상세를 못 받으면(연결 끊김) "못 불러왔어요" 쪽 - error', async () => {
    const s = await freshStore()
    await s.loadPlaces()
    placeApi.getById.mockResolvedValue({ place: null, missing: false })
    expect(await s.findPlaceById(777)).toEqual({ place: null, error: true })   // 777: 어느 레이어에도 없는 id
    expect(placeApi.getLayer).not.toHaveBeenCalled()
  })

  it('layerOf - 관광지 spot, 착한가격 food, 식당 dine, 쇼핑은 없음', async () => {
    const s = await freshStore()
    expect(s.layerOf({ cat: 'TOURIST' })).toBe('spot')
    expect(s.layerOf({ cat: 'FOOD', good: true })).toBe('food')
    expect(s.layerOf({ cat: 'FOOD', good: false })).toBe('dine')
    expect(s.layerOf({ cat: 'SHOPPING' })).toBeNull()
  })
})

describe('placeKey - 장소 식별자는 이름이 아니라 id', () => {
  it('id 가 있으면 id, 없으면 이름 - 같은 이름의 다른 장소는 키가 다르고 목업(id 없음)은 이름으로 구분한다', async () => {
    const s = await freshStore()
    const starbucksA = { id: 6723, n: '스타벅스', r: '북부' }
    const starbucksB = { id: 6725, n: '스타벅스', r: '북부' }
    expect(s.placeKey(starbucksA)).toBe(6723)
    expect(s.placeKey(starbucksA)).not.toBe(s.placeKey(starbucksB))   // 이름으로 감시하면 여기서 상세가 안 바뀌었다
    expect(s.placeKey({ n: '목업 장소' })).toBe('목업 장소')
    expect(s.placeKey(null)).toBeUndefined()
  })
})

describe('forecastUntilOf - 오늘 기준 예보가 있는 마지막 날', () => {
  it('관광지 전체에서 값이 있는 가장 뒤 칸을 고르고, 장소마다 빠진 날이 달라도 가장 긴 쪽을 따른다', async () => {
    const s = await freshStore()
    expect(s.forecastUntilOf([{ series: [10, 20, null, null] }, { series: [5, null, 7] }])).toBe(2)   // 두 번째 장소가 D+2 까지
    expect(s.forecastUntilOf([{ series: [null, null] }, { n: 'series 없음' }])).toBe(-1)
    expect(s.forecastUntilOf([])).toBe(-1)
  })
})

describe('loadPlaces 재진입 재사용 (성능 B 커밋 1)', () => {
  it('첫 진입은 장소·예보·날씨를 한 번씩 받는다', async () => {
    const s = await freshStore()
    expect(s.canReuse()).toBe(false)
    await s.loadPlaces()
    expect(placeApi.getAll).toHaveBeenCalledTimes(1)
    expect(crowdApi.getForecast).toHaveBeenCalledTimes(1)
    expect(weatherApi.load).toHaveBeenCalledTimes(1)
    expect(s.state.layers.spot).toHaveLength(1)
    expect(s.state.forecastVersion).toBe(1)
    expect(s.state.loading).toBe(false)
    expect(s.canReuse()).toBe(true)
  })

  it('같은 날 12시간 안 재진입은 아무것도 다시 받지 않고 상태도 건드리지 않는다', async () => {
    const s = await freshStore()
    await s.loadPlaces()
    const before = s.state.layers
    vi.setSystemTime(new Date(2026, 8, 12, 21, 59))   // 현지 시각으로 11시간 59분 뒤
    await s.loadPlaces()
    expect(placeApi.getAll).toHaveBeenCalledTimes(1)
    expect(crowdApi.getForecast).toHaveBeenCalledTimes(1)
    expect(weatherApi.load).toHaveBeenCalledTimes(1)
    expect(s.state.layers).toBe(before)          // 같은 객체 - 핀이 회색으로 바뀌었다 돌아오는 일이 없다
    expect(s.state.loading).toBe(false)          // 목록이 "불러오는 중"으로 비지 않는다
    expect(s.state.forecastVersion).toBe(1)
  })

  it('12시간이 지나면 다시 받는다', async () => {
    const s = await freshStore()
    await s.loadPlaces()
    vi.setSystemTime(new Date(2026, 8, 12, 22, 1))   // 12시간 1분 뒤, 같은 날
    await s.loadPlaces()
    expect(placeApi.getAll).toHaveBeenCalledTimes(2)
    expect(crowdApi.getForecast).toHaveBeenCalledTimes(2)
  })

  it('날짜가 바뀌면 12시간 안이라도 다시 받는다 - 예보가 오늘 기준으로 붙어 있어서', async () => {
    vi.setSystemTime(new Date(2026, 8, 12, 23, 50))
    const s = await freshStore()
    await s.loadPlaces()
    expect(s.canReuse()).toBe(true)
    vi.setSystemTime(new Date(2026, 8, 13, 0, 10))   // 20분 뒤, 현지 날짜가 바뀜
    expect(s.canReuse()).toBe(false)
    await s.loadPlaces()
    expect(placeApi.getAll).toHaveBeenCalledTimes(2)
    expect(crowdApi.getForecast).toHaveBeenCalledTimes(2)
    expect(weatherApi.load).toHaveBeenCalledTimes(2)
  })

  it('날짜가 바뀌어 다시 받을 때 칩으로 받아 둔 지연 레이어(카페)는 지우지 않는다 (최종점검 #28)', async () => {
    const s = await freshStore()
    placeApi.getAll.mockImplementation(async () => ok())   // 호출마다 새 배열 - 같은 객체를 두 번 주면 교체 여부를 못 본다
    await s.loadPlaces()
    // 사용자가 카페 칩을 켜서 받아 둔 상태
    s.state.L.cafe = 1
    s.state.layers.cafe = [{ id: 9, n: '카페', x: 126.5, y: 33.4 }]
    const spotBefore = s.state.layers.spot
    vi.setSystemTime(new Date(2026, 8, 13, 10, 0))
    await s.loadPlaces()
    expect(placeApi.getAll).toHaveBeenCalledTimes(2)
    expect(s.state.layers.spot).not.toBe(spotBefore)      // 받은 관광지는 새 배열
    expect(s.state.layers.cafe).toHaveLength(1)           // 지연 레이어는 그대로 - 칩만 켜진 채 핀이 사라지지 않는다
    expect(s.state.L.cafe).toBe(1)
  })

  it('날짜가 바뀐 재진입은 오늘 기준을 옮기고 선택 날짜를 오늘로 되돌린다 (최종점검 #16)', async () => {
    const s = await freshStore()
    const { today, at, iso } = await import('../utils/date')
    await s.loadPlaces()
    s.state.di = 3
    expect(iso(today())).toBe('2026-09-12')
    vi.setSystemTime(new Date(2026, 8, 13, 10, 0))
    await s.loadPlaces()
    expect(iso(today())).toBe('2026-09-13')       // 글자·달력의 오늘
    expect(iso(at(0))).toBe('2026-09-13')
    expect(s.state.di).toBe(0)                     // 선택 날짜는 오늘로
    const { attachSeries } = await import('../services/map/CrowdService')
    expect(attachSeries).toHaveBeenLastCalledWith(expect.anything(), expect.anything(), '2026-09-13')   // 데이터도 같은 날 기준
  })

  it('레이어 하나라도 못 받았으면 기록하지 않아 다음 진입에 전부 다시 받는다(재시도)', async () => {
    placeApi.getAll.mockResolvedValueOnce(ok(['stay']))
    const s = await freshStore()
    await s.loadPlaces()
    expect(s.canReuse()).toBe(false)
    await s.loadPlaces()
    expect(placeApi.getAll).toHaveBeenCalledTimes(2)
  })

  it('예보가 비어 왔으면(실패·미적재) 다음 진입에 다시 받는다', async () => {
    crowdApi.getForecast.mockResolvedValueOnce(forecast(false))
    const s = await freshStore()
    await s.loadPlaces()
    expect(s.canReuse()).toBe(false)
    await s.loadPlaces()
    expect(crowdApi.getForecast).toHaveBeenCalledTimes(2)
  })
})
