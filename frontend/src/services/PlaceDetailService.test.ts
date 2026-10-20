import { afterEach, describe, expect, it, vi } from 'vitest'
import { apiGet } from './apiClient'
import PlaceDetailService from './PlaceDetailService'

vi.mock('./apiClient', () => ({ apiGet: vi.fn() }))

afterEach(() => vi.clearAllMocks())

describe('장소 상세 조회 (MAP_008, 담당 정동현)', () => {
  it('상세는 백엔드 응답을 그대로 쓴다 - 화면이 없는 값을 지어내지 않게', async () => {
    vi.mocked(apiGet).mockResolvedValue({ id: 1613, name: '제주현대미술관', useFeeText: null, free: false })

    const detail = await PlaceDetailService.getDetail(1613)

    expect(apiGet).toHaveBeenCalledWith('/places/1613', 15000)
    expect(detail).toMatchObject({ id: 1613, name: '제주현대미술관', useFeeText: null })
  })

  it('없는 장소·백엔드 장애는 null - 화면이 "찾지 못했어요"를 보여준다', async () => {
    vi.mocked(apiGet).mockRejectedValue(new Error('HTTP 404'))

    expect(await PlaceDetailService.getDetail(999999)).toBeNull()
  })

  describe('getById - 소개 페이지는 "없는 장소"와 "못 받음"을 가른다', () => {
    it('받으면 detail, missing false', async () => {
      vi.mocked(apiGet).mockResolvedValue({ id: 1613, name: '제주현대미술관' })
      expect(await PlaceDetailService.getById(1613)).toEqual({ detail: { id: 1613, name: '제주현대미술관' }, missing: false })
    })

    it('백엔드가 4xx 로 답하면(없는 id 는 PLACE_NOT_FOUND 3201 → 400) missing', async () => {
      vi.mocked(apiGet).mockRejectedValue(new Error('HTTP 400'))
      expect(await PlaceDetailService.getById(999999)).toEqual({ detail: null, missing: true })
    })

    it('서버 오류·연결 끊김·시간 초과는 missing 이 아니다 - 화면이 다시 시도를 보여준다', async () => {
      for (const err of [new Error('HTTP 500'), new TypeError('Failed to fetch'), new DOMException('The user aborted a request.', 'AbortError')]) {
        vi.mocked(apiGet).mockRejectedValue(err)
        expect(await PlaceDetailService.getById(1613)).toEqual({ detail: null, missing: false })
      }
    })
  })

  it('예보는 최신 발표분에서 이 장소 것만 꺼낸다', async () => {
    vi.mocked(apiGet).mockResolvedValue({
      from: '2026-09-08', baseDate: '2026-09-09', days: 3,
      values: { '1613': [10.2, 22, 71], '1518': [40, 41, 42] },
    })

    const forecast = await PlaceDetailService.getForecast(1613)

    expect(apiGet).toHaveBeenCalledWith('/crowd/forecast', 15000)
    // 발표일은 창 시작일과 다르다 - 새벽 적재가 받는 창은 하루 앞에서 시작한다
    expect(forecast).toEqual({ from: '2026-09-08', baseDate: '2026-09-09', rates: [10.2, 22, 71] })
  })

  it('발표일이 없는 옛 응답도 읽는다 - baseDate는 null로 둔다', async () => {
    vi.mocked(apiGet).mockResolvedValue({ from: '2026-09-08', days: 3, values: { '1613': [10.2, 22, 71] } })

    expect(await PlaceDetailService.getForecast(1613)).toEqual(
      { from: '2026-09-08', baseDate: null, rates: [10.2, 22, 71] })
  })

  it('예보 대상이 아닌 장소는 null - 0으로 채우면 "정보 없음"이 "한산"으로 둔갑한다', async () => {
    vi.mocked(apiGet).mockResolvedValue({ from: '2026-09-08', days: 3, values: { '1518': [40, 41, 42] } })

    expect(await PlaceDetailService.getForecast(1613)).toBeNull()
  })

  it('예보 조회가 실패해도 null만 돌려준다 - 상세 본문은 살아 있어야 한다', async () => {
    vi.mocked(apiGet).mockRejectedValue(new Error('timeout'))

    expect(await PlaceDetailService.getForecast(1613)).toBeNull()
  })
})
