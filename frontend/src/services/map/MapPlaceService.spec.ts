import { describe, it, expect, vi, afterEach } from 'vitest'
import MapPlaceService, { thumbOf } from './MapPlaceService'

/** getDetail 사진 매핑 검증. 값은 2026-08-30 실응답에서 가져왔다. */
const REAL_DETAIL = {
  restDayText: '연중무휴',
  useFeeText: null,
  free: false,
  images: [
    {
      url: 'https://tong.visitkorea.or.kr/cms/resource/86/3026686_image2_1.jpg',
      thumbnailUrl: 'https://tong.visitkorea.or.kr/cms/resource/86/3026686_image2_1.jpg',
      caption: '가시리국산화풍력발전단지',
      attribution: '출처: 한국관광공사 국문 관광정보 서비스',
      licenseCode: 'Type3',
      primary: true
    },
    {
      url: 'https://tong.visitkorea.or.kr/cms/resource/87/3026687_image2_1.jpg',
      thumbnailUrl: null,
      caption: null,
      attribution: '출처: 한국관광공사 국문 관광정보 서비스',
      licenseCode: 'Type3',
      primary: false
    }
  ]
}

function mockFetch (result: unknown) {
  vi.stubGlobal('fetch', vi.fn().mockResolvedValue({
    ok: true,
    json: () => Promise.resolve({ success: true, code: 2000, message: '', result })
  }))
}

afterEach(() => vi.unstubAllGlobals())

describe('상세 조회 사진 매핑', () => {
  it('사진 목록과 출처 문구가 넘어온다', async () => {
    mockFetch(REAL_DETAIL)

    const d = await MapPlaceService.getDetail(9)

    expect(d?.images).toHaveLength(2)
    expect(d?.images[0].url).toContain('3026686')
    expect(d?.imageAttribution).toBe('출처: 한국관광공사 국문 관광정보 서비스')
  })

  it('같은 응답에서 장소 기본 정보(업종·권역·주소)도 뽑아 준다 - 코스 경유지 대체 객체({id,n,x,y})의 빈 칸을 채운다(#13)', async () => {
    // 값은 2026-09-14 로컬 /places/378 (코스 8의 식당 경유지) 응답 모양
    mockFetch({
      ...REAL_DETAIL,
      id: 378, name: '드르쿰다', regionCode: 'EAST', regionName: '동부', categoryCode: 'FOOD', categoryName: '음식점',
      tagCode: null, tagName: null, roadAddress: '제주특별자치도 서귀포시 성산읍 삼달로 63', lotAddress: null,
      latitude: 33.4444984, longitude: 126.9191262, phone: null, operatingHoursText: null,
      parkingAvailable: null, toiletAvailable: null, businessStatus: 'OPEN', goodPrice: false, hiddenGem: false
    })

    const d = await MapPlaceService.getDetail(378)

    expect(d?.place).toMatchObject({ id: 378, n: '드르쿰다', c: '음식점', r: '동부', cat: 'FOOD', addr: '제주특별자치도 서귀포시 성산읍 삼달로 63' })
    // 대체 객체와 합치면 원래 네 칸은 그대로, 나머지가 채워진다
    const stub = { id: 378, n: '드르쿰다', x: 126.9191262, y: 33.4444984 }
    expect({ ...d!.place, ...stub }).toMatchObject({ ...stub, c: '음식점', r: '동부' })
  })

  it('축소본 주소가 없거나 원본과 같으면 공사 규칙(_image3_)으로 축소본을 쓰고, 크게 보기용 url 은 원본 그대로다', async () => {
    mockFetch(REAL_DETAIL)

    const d = await MapPlaceService.getDetail(9)

    expect(d?.images[0].thumb).toBe('https://tong.visitkorea.or.kr/cms/resource/86/3026686_image3_1.jpg')   // 축소본 = 원본으로 온 것
    expect(d?.images[1].thumb).toBe('https://tong.visitkorea.or.kr/cms/resource/87/3026687_image3_1.jpg')   // 축소본 null
    expect(d?.images[0].url).toContain('_image2_')
  })

  it('thumbOf: 따로 온 축소본은 그대로, 공사 규칙이 안 맞는 주소는 원본 그대로', () => {
    expect(thumbOf('https://tong.visitkorea.or.kr/a/1_image2_1.jpg', 'https://tong.visitkorea.or.kr/a/1_image3_1.jpg'))
      .toBe('https://tong.visitkorea.or.kr/a/1_image3_1.jpg')
    expect(thumbOf('https://example.com/photo.jpg', null)).toBe('https://example.com/photo.jpg')
    expect(thumbOf('https://example.com/photo.jpg', 'https://example.com/photo.jpg')).toBe('https://example.com/photo.jpg')
  })

  it('사진이 없으면 빈 배열이고 출처도 없다 - 화면이 사진 영역을 숨기는 근거', async () => {
    mockFetch({ restDayText: null, useFeeText: null, free: false, images: [] })

    const d = await MapPlaceService.getDetail(1)

    expect(d?.images).toEqual([])
    expect(d?.imageAttribution).toBeNull()
  })

  it('착한가격 기준일은 그대로 넘기고, 없으면 null 이다', async () => {
    mockFetch({ ...REAL_DETAIL, goodPriceBaseDate: '2026-06-30' })
    expect((await MapPlaceService.getDetail(161))?.goodPriceBaseDate).toBe('2026-06-30')
    mockFetch(REAL_DETAIL)
    expect((await MapPlaceService.getDetail(9))?.goodPriceBaseDate).toBeNull()
  })

  it('호출이 실패하면 null - 패널은 목록 데이터로 계속 그려진다', async () => {
    vi.stubGlobal('fetch', vi.fn().mockRejectedValue(new Error('down')))

    expect(await MapPlaceService.getDetail(9)).toBeNull()
  })
})

/** 실응답(2026-09-05 /places?type=cafe 첫 행) - toMapPlace 가 읽는 필드가 전부 있다 */
const ROW = {
  id: 3729, name: '리치망고협제점', latitude: 33.4024033, longitude: 126.2517715,
  categoryCode: 'CAFE', categoryName: '카페', regionCode: 'WEST', regionName: '서부',
  roadAddress: '제주특별자치도 제주시 한림읍 한림로 482', lotAddress: null, phone: null,
  operatingHoursText: null, parkingAvailable: null, toiletAvailable: null,
  goodPrice: false, hiddenGem: false, businessStatus: 'UNKNOWN', tagCode: null, tagName: null
}

function mockFetchByUrl (failWhen: (url: string) => boolean) {
  vi.stubGlobal('fetch', vi.fn((url: string) => failWhen(url)
    ? Promise.reject(new Error('down'))
    : Promise.resolve({ ok: true, json: () => Promise.resolve({ success: true, code: 2000, message: '', result: [ROW] }) })))
}

describe('getAll 첫 진입 - 관광지만 받고, 목업으로 바꿔치기하지 않는다', () => {
  it('첫 진입 요청은 type=spot 하나뿐 - 꺼져 있는 착한가격·식당·숙소는 칩을 켤 때 받는다', async () => {
    mockFetchByUrl(() => false)

    const r = await MapPlaceService.getAll()

    const urls = vi.mocked(fetch).mock.calls.map(c => String(c[0]))
    expect(urls).toHaveLength(1)
    expect(urls[0]).toContain('/places?type=spot')
    expect(r.live).toBe(true)
    expect(r.failed).toEqual([])
    expect(r.layers.spot[0].id).toBe(3729)      // 목업이면 id가 null 이다
    expect(r.layers.dine).toEqual([])
  })

  it('관광지를 못 받으면 live=false 에 빈 레이어 - 가짜 장소를 만들어내지 않는다', async () => {
    mockFetchByUrl(() => true)

    const r = await MapPlaceService.getAll()

    expect(r.live).toBe(false)
    expect(r.failed).toEqual(['spot'])
    expect(Object.values(r.layers).every(l => l.length === 0)).toBe(true)
  })

  it('지연 레이어는 getLayer 로 하나씩 - 실패하면 null 이고 다른 레이어에 영향이 없다', async () => {
    mockFetchByUrl(url => url.includes('type=dine'))

    expect(await MapPlaceService.getLayer('dine')).toBeNull()
    const food = await MapPlaceService.getLayer('food')
    expect(food).toHaveLength(1)
    expect(food![0].id).toBe(3729)
  })
})

/** 폐업 장소 - 목록엔 없지만 찜·공유 링크로 열리므로 id 단건 조회와 closed 플래그가 필요하다 */
const CLOSED_ROW = {
  id: 77, name: '문닫은집', regionCode: 'WEST', regionName: '서부', categoryCode: 'FOOD', categoryName: '음식점',
  tagCode: null, tagName: '한식', roadAddress: '제주 제주시 애월읍', lotAddress: null, latitude: 33.4, longitude: 126.3,
  phone: null, operatingHoursText: null, parkingAvailable: null, toiletAvailable: null,
  businessStatus: 'CLOSED', goodPrice: false, hiddenGem: false
}

describe('폐업 장소', () => {
  it('목록 변환은 CLOSED 를 closed 플래그로 옮기고 나머지는 false 다', async () => {
    mockFetch([CLOSED_ROW, { ...CLOSED_ROW, id: 78, businessStatus: 'UNKNOWN' }])
    const rows = await MapPlaceService.getLayer('dine')
    expect(rows?.map(r => r.closed)).toEqual([true, false])
  })

  it('부제(c)는 세부 태그 → 대분류 → 정보 없음 순 - 태그 없는 카페는 "카페"로 보인다', async () => {
    mockFetch([
      ROW,                                                          // 카페, tagName null
      { ...ROW, id: 1, tagName: '호텔', categoryName: '숙소' },      // 태그 있으면 태그
      { ...ROW, id: 2, tagName: null, categoryName: null },         // 둘 다 없을 때만 정보 없음
    ])
    const rows = await MapPlaceService.getLayer('cafe')
    expect(rows?.map(r => r.c)).toEqual(['카페', '호텔', '정보 없음'])
  })

  it('getById 는 상세 응답을 목록과 같은 모양으로 돌려준다', async () => {
    mockFetch({ ...CLOSED_ROW, overview: '소개', images: [] })
    const { place, missing } = await MapPlaceService.getById(77)
    expect(place).toMatchObject({ id: 77, n: '문닫은집', x: 126.3, y: 33.4, cat: 'FOOD', closed: true })
    expect(missing).toBe(false)
  })

  it('getById 는 없는 장소(400/3201)면 missing - 호출부가 "찾지 못했어요" 로 바로 안내한다(#49)', async () => {
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue({ ok: false, status: 400, json: () => Promise.resolve({ success: false, code: 3201, message: '존재하지 않는 장소입니다.', result: null }) }))
    expect(await MapPlaceService.getById(99999999)).toEqual({ place: null, missing: true })
  })

  it('getById 는 연결 실패면 place null + missing false - "못 불러왔어요" 로 구분한다', async () => {
    vi.stubGlobal('fetch', vi.fn().mockRejectedValue(new Error('down')))
    expect(await MapPlaceService.getById(1)).toEqual({ place: null, missing: false })
  })
})

describe('통합 검색 (2026-09-14 백엔드 단일화)', () => {
  it('업종을 좁히지 않으면 categories 를 보내지 않고, 권역만 붙인다 - 결과는 목록과 같은 모양에 읍면(unit)이 붙는다', async () => {
    mockFetch([{ ...ROW, roadAddress: '제주특별자치도 제주시 애월읍 애월로 1' }])
    const rows = await MapPlaceService.search('스타벅스', { region: 'NORTH' })
    const url = (fetch as ReturnType<typeof vi.fn>).mock.calls[0][0] as string
    expect(url).toContain('/places/search?q=')
    expect(url).toContain('region=NORTH')
    expect(url).not.toContain('categories=')
    expect(rows).toHaveLength(1)
    expect(rows![0]).toMatchObject({ c: '카페', unit: '애월읍' })
  })

  it('실패하면 null - 빈 배열(0건)과 구분해 화면이 "연결하지 못했어요"를 보여준다(#26)', async () => {
    vi.stubGlobal('fetch', vi.fn().mockRejectedValue(new Error('down')))
    expect(await MapPlaceService.search('국수')).toBeNull()
    mockFetch([])
    expect(await MapPlaceService.search('없는이름')).toEqual([])
  })
})
