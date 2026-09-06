import { afterEach, describe, expect, it, vi } from 'vitest'
import type { AlternativePlace, CourseCondition, CourseItem, CourseResult } from '../assets/types/course'
import { apiRequest } from '../api/backendClient.js'
import { ApiError } from '../api/errors.js'
import {
  courseGenerationErrorMessage,
  courseMockService,
  toCourseRequestPayload,
} from './courseMockService'

vi.mock('../api/backendClient.js', () => ({ apiRequest: vi.fn() }))

const condition: CourseCondition = {
  start_date: '2026-08-28',
  end_date: '2026-08-29',
  people: 2,
  budget_total: 400000,
  transport: 'RENTAL_CAR',
  course_regions: [{ region_id: 1, code: 'EAST', name: '동부' }],
  course_styles: [{ tag_id: 1, code: 'NATURE', name: '자연', weight: 1 }],
  course_place_preferences: [],
  accommodation: {
    source_code: 'KAKAO_LOCAL',
    source_place_id: 'accommodation-1',
    place_name: '제주 숙소',
    address: '제주특별자치도 제주시 연동 1',
    road_address: '제주특별자치도 제주시 숙소로 1',
    latitude: 33.45,
    longitude: 126.55,
    category_name: '여행 > 숙박 > 호텔',
  },
}

const response: CourseResult = {
  id: 101,
  course_type: 'USER',
  generation_reason: 'INITIAL',
  status: 'READY',
  start_date: condition.start_date,
  end_date: condition.end_date,
  people: condition.people,
  budget_total: condition.budget_total,
  budget_summary: {
    has_cost_data: false,
    budget_total: condition.budget_total,
    verified_total: 0,
    unknown_count: 0,
  },
  transport: condition.transport,
  accommodation: condition.accommodation,
  days: [{
    day_no: 1,
    visit_date: condition.start_date,
    items: [{
      id: 201,
      course_id: 101,
      place_id: 301,
      candidate_id: 'candidate-kto-1',
      source_code: 'KTO',
      source_place_id: '125266',
      place_name: '비자림',
      image_url: '/images/bijarim.jpg',
      address: '제주특별자치도 제주시 구좌읍 비자숲길 55',
      road_address: '제주특별자치도 제주시 구좌읍 비자숲길 55',
      category_name: '관광지',
      day_no: 1,
      position: 1,
      visit_date: condition.start_date,
      start_time: '09:00',
      item_source: 'AI_RECOMMENDED',
      congestion_rate: 22.5,
      congestion_level: 'QUIET',
      recommendation_reason: '혼잡도가 낮고 자연 취향에 잘 맞아요.',
      costs: [],
    }],
  }],
}

const itineraryContract = (course: CourseResult) => course.days.flatMap(day =>
  day.items.map(item => ({
    candidateId: item.candidate_id,
    placeId: item.place_id,
    sourceCode: item.source_code,
    sourcePlaceId: item.source_place_id,
    placeName: item.place_name,
    imageUrl: item.image_url,
    recommendationReason: item.recommendation_reason,
    congestionRate: item.congestion_rate,
    congestionLevel: item.congestion_level,
    visitDate: item.visit_date,
    startTime: item.start_time,
  })))

afterEach(() => vi.clearAllMocks())

describe('courseMockService Backend generation', () => {
  it('posts the unchanged CourseCondition and returns the Backend CourseResult without fabricating data', async () => {
    const requestMock = vi.mocked(apiRequest).mockResolvedValue(response)

    const result = await courseMockService.generateCourse(condition)

    expect(requestMock).toHaveBeenCalledOnce()
    expect(requestMock).toHaveBeenCalledWith('/courses', {
      method: 'POST',
      body: toCourseRequestPayload(condition),
    })
    expect(requestMock.mock.calls[0]?.[1]?.body).toMatchObject({
      accommodation: {
        source_code: 'KAKAO_LOCAL',
        source_place_id: 'accommodation-1',
        place_name: '제주 숙소',
        address: '제주특별자치도 제주시 연동 1',
        road_address: '제주특별자치도 제주시 숙소로 1',
        latitude: 33.45,
        longitude: 126.55,
        category_name: '여행 > 숙박 > 호텔',
      },
    })
    expect(result).toBe(response)
    expect(result.days[0].items[0]).toMatchObject({
      id: 201,
      course_id: 101,
      place_id: 301,
      candidate_id: 'candidate-kto-1',
      source_code: 'KTO',
      source_place_id: '125266',
      road_address: '제주특별자치도 제주시 구좌읍 비자숲길 55',
      costs: [],
    })
    expect(result.days[0].items[0].end_time).toBeUndefined()
    expect(result.estimated_cost_min).toBeUndefined()
    expect(result.budget_summary).toEqual({
      has_cost_data: false,
      budget_total: 400000,
      verified_total: 0,
      unknown_count: 0,
    })
  })

  it('omits accommodation from the request payload when none is selected', async () => {
    const conditionWithoutAccommodation = { ...condition }
    delete conditionWithoutAccommodation.accommodation
    const requestMock = vi.mocked(apiRequest).mockResolvedValue({
      ...response,
      accommodation: undefined,
    })

    await courseMockService.generateCourse(conditionWithoutAccommodation)

    expect(requestMock).toHaveBeenCalledOnce()
    expect(requestMock.mock.calls[0]?.[1]?.body).not.toHaveProperty('accommodation')
  })

  it('persists a recommended accommodation before changing local state and keeps the Backend itinerary unchanged', async () => {
    const backendCourse: CourseResult = {
      ...response,
      accommodation: undefined,
      claim_token: 'opaque-proof',
      days: [{
        day_no: 1,
        visit_date: condition.start_date,
        items: [
          response.days[0].items[0],
          {
            ...response.days[0].items[0],
            id: 202,
            place_id: 302,
            candidate_id: 'candidate-kto-2',
            source_place_id: '125267',
            place_name: '종달리해변',
            image_url: '/images/jongdal.jpg',
            position: 2,
            start_time: '13:00',
            congestion_rate: undefined,
            congestion_level: undefined,
            recommendation_reason: '바다 풍경을 볼 수 있어요.',
          },
          {
            ...response.days[0].items[0],
            id: 203,
            place_id: 303,
            candidate_id: 'candidate-kto-3',
            source_place_id: '125268',
            place_name: '비밀의 숲',
            image_url: '/images/forest.jpg',
            position: 3,
            start_time: '17:00',
            congestion_rate: undefined,
            congestion_level: undefined,
            recommendation_reason: '조용한 숲길을 걸을 수 있어요.',
          },
        ],
      }],
    }
    const before = structuredClone(backendCourse)
    const requestMock = vi.mocked(apiRequest).mockResolvedValue(condition.accommodation!)

    const savedAccommodation = await courseMockService.updateAccommodation(
      backendCourse,
      condition.accommodation!,
    )
    const selected = courseMockService.applyAccommodationSelection(
      backendCourse,
      savedAccommodation,
    )

    expect(requestMock).toHaveBeenCalledWith('/courses/101/accommodation', {
      method: 'PATCH',
      auth: false,
      body: {
        accommodation: condition.accommodation,
        claim_token: 'opaque-proof',
      },
    })
    expect(selected.id).toBe(backendCourse.id)
    expect(selected.days).toBe(backendCourse.days)
    expect(selected.days).toEqual(before.days)
    expect(itineraryContract(selected)).toEqual(itineraryContract(before))
    expect(selected.accommodation).toEqual(condition.accommodation)
    expect(selected.days.flatMap(day => day.items).every(item =>
      item.weather_condition == null
      && item.temperature == null
      && item.inbound_distance_m == null
      && item.inbound_travel_minutes == null
      && item.costs.length === 0)).toBe(true)
    expect(selected.estimated_cost_min).toBeUndefined()
    expect(selected.estimated_cost_max).toBeUndefined()
  })

  it('does not mark an accommodation as selected when the Backend update fails', async () => {
    const backendCourse: CourseResult = {
      ...response,
      accommodation: undefined,
      claim_token: 'opaque-proof',
    }
    const before = structuredClone(backendCourse)
    vi.mocked(apiRequest).mockRejectedValue(new Error('숙소 저장 실패'))

    await expect(courseMockService.updateAccommodation(
      backendCourse,
      condition.accommodation!,
    )).rejects.toThrow('숙소 저장 실패')

    expect(backendCourse).toEqual(before)
    expect(backendCourse.accommodation).toBeUndefined()
  })

  it('loads real accommodation recommendations from the Backend without a mock fallback', async () => {
    const backendCourse: CourseResult = { ...response, claim_token: 'opaque-proof' }
    const kakaoAccommodation = {
      source_code: 'KAKAO_LOCAL' as const,
      source_place_id: 'real-kakao-123',
      place_name: 'Kakao 검증 호텔',
      latitude: 33.45,
      longitude: 126.55,
      region: 'EAST' as const,
    }
    const requestMock = vi.mocked(apiRequest).mockResolvedValue([kakaoAccommodation])

    const recommendations = await courseMockService.getRecommendedAccommodations(backendCourse)

    expect(requestMock).toHaveBeenCalledWith('/courses/101/accommodations/search', {
      method: 'POST',
      auth: false,
      body: { claim_token: 'opaque-proof' },
    })
    expect(recommendations).toEqual([expect.objectContaining({
      source_place_id: 'real-kakao-123',
      recommendation_reason: expect.any(String),
    })])

    requestMock.mockRejectedValueOnce(new Error('Kakao unavailable'))
    await expect(courseMockService.getRecommendedAccommodations(backendCourse))
      .rejects.toThrow('Kakao unavailable')
    expect(recommendations.every(item => !item.source_place_id.startsWith('MOCK_KAKAO_'))).toBe(true)
  })

  it('loads the car route after the course without changing the itinerary', async () => {
    const before = structuredClone(response)
    const route = {
      course_id: 101,
      transport: 'RENTAL_CAR' as const,
      provider: 'KAKAO_MOBILITY' as const,
      priority: 'RECOMMEND' as const,
      cached: false,
      fetched_at: '2026-09-03T12:00:00+09:00',
      days: [{
        day_no: 1,
        visit_date: condition.start_date,
        total_distance_meters: 12500,
        total_duration_seconds: 1800,
        legs: [],
        polyline: [{ latitude: 33.45, longitude: 126.55 }],
      }],
    }
    const requestMock = vi.mocked(apiRequest).mockResolvedValue(route)

    expect(await courseMockService.getCarRoute(response)).toBe(route)
    expect(requestMock).toHaveBeenCalledWith('/courses/101/routes/car', {
      method: 'GET',
      auth: false,
    })
    expect(response).toEqual(before)
  })

  it('preserves a no-accommodation itinerary on full route failure and later re-queries after selection', async () => {
    const original: CourseResult = { ...structuredClone(response), accommodation: null }
    const before = structuredClone(original)
    const request = vi.mocked(apiRequest).mockRejectedValueOnce(new Error('route unavailable'))
    await expect(courseMockService.getCarRoute(original)).rejects.toThrow('route unavailable')
    expect(original).toEqual(before)
    const partial = { days: [{ total_distance_meters: null, total_duration_seconds: null,
      legs: [{ distance_meters: null, duration_seconds: null }, { distance_meters: 1000, duration_seconds: 120 }] }] }
    request.mockResolvedValueOnce(partial)
    expect(await courseMockService.getCarRoute(original)).toEqual(partial)
    const selected = { ...original, accommodation: condition.accommodation }
    request.mockResolvedValueOnce({ days: [] })
    await courseMockService.getCarRoute(selected)
    expect(request).toHaveBeenCalledTimes(3)
    expect(selected.days).toEqual(before.days)
    expect(original.accommodation).toBeNull()
  })

  it('uses the authenticated owner boundary for a SAVED course', async () => {
    const savedCourse: CourseResult = {
      ...response,
      status: 'SAVED',
      accommodation: undefined,
    }
    const requestMock = vi.mocked(apiRequest).mockResolvedValue(condition.accommodation!)

    await courseMockService.updateAccommodation(savedCourse, condition.accommodation!)

    expect(requestMock).toHaveBeenCalledWith('/courses/101/accommodation', {
      method: 'PATCH',
      auth: true,
      body: { accommodation: condition.accommodation },
    })
  })

  it('propagates common client failures without falling back to mock generation', async () => {
    const requestMock = vi.mocked(apiRequest)
            .mockRejectedValue(new Error('코스 생성 API 요청에 실패했습니다.'))

    await expect(courseMockService.generateCourse(condition))
      .rejects.toThrow('코스 생성 API 요청에 실패했습니다.')
    expect(requestMock).toHaveBeenCalledOnce()
  })

  it('shows the stable server message for exhausted transient Gemini failures', () => {
    const error = new ApiError(
      503,
      5003,
      'AI 코스 생성 서버가 일시적으로 혼잡합니다. 잠시 후 다시 시도해 주세요.'
    )

    expect(courseGenerationErrorMessage(error)).toBe(
      'AI 코스 생성 서버가 일시적으로 혼잡합니다. 잠시 후 다시 시도해 주세요.'
    )
    expect(courseGenerationErrorMessage(new Error('internal detail'))).toBe(
      '코스를 생성하지 못했어요. 다시 시도해 주세요.'
    )
  })

  it('keeps regeneration unavailable instead of sending an INITIAL request or returning mock data', async () => {
    const requestMock = vi.mocked(apiRequest)

    await expect(courseMockService.regenerateCourse(condition)).rejects.toThrow('재생성은 아직 지원되지 않습니다')
    expect(requestMock).not.toHaveBeenCalled()
  })

  it('claims a generated guest course with authenticated API and removes the proof from the result', async () => {
    const guestCourse: CourseResult = {
      ...response,
      claim_token: 'opaque-proof',
      claim_expires_at: '2026-08-31T12:30:00Z',
    }
    const requestMock = vi.mocked(apiRequest).mockResolvedValue({
      id: 101,
      status: 'SAVED',
      title: '제주 여행',
      saved_at: '2026-08-31T12:00:00',
    })

    const result = await courseMockService.saveCourse(guestCourse, '제주 여행')

    expect(requestMock).toHaveBeenCalledWith('/courses/101/claim', {
      method: 'POST',
      auth: true,
      body: { claim_token: 'opaque-proof', title: '제주 여행' },
    })
    expect(result).toMatchObject({ id: 101, status: 'SAVED', title: '제주 여행' })
    expect(result.budget_summary).toEqual(guestCourse.budget_summary)
    expect(result.claim_token).toBeUndefined()
    expect(result.claim_expires_at).toBeUndefined()
  })
})

describe('alternative places and swap (backend, 담당 정동현)', () => {
  const item = (id: number, place_id: number, place_name: string, extra: Partial<CourseItem> = {}): CourseItem => ({
    id, course_id: 101, place_id, place_name, category_name: '관광지', day_no: 1, position: id, visit_date: '2026-08-28',
    start_time: '09:00', end_time: '11:00', item_source: 'AI_RECOMMENDED', costs: [], latitude: 33.46, longitude: 126.94, ...extra,
  })
  const course = (status: CourseResult['status'] = 'READY'): CourseResult => ({
    ...response,
    status,
    average_congestion_rate: 70,
    car_route: { days: [] } as unknown as CourseResult['car_route'],
    days: [{
      day_no: 1,
      visit_date: '2026-08-28',
      items: [
        item(1, 501, '성산일출봉', { congestion_level: 'CROWDED', congestion_rate: 82 }),
        item(2, 502, '광치기해변', { congestion_level: 'NORMAL', congestion_rate: 58, inbound_distance_m: 3000, inbound_travel_minutes: 5 }),
      ],
    }],
  })
  const alternative: AlternativePlace = {
    place_id: 601, place_name: '두산봉', category_name: '관광지', distance_m: 4200, congestion_rate: 21, congestion_level: 'QUIET',
    recommendation_reason: '이 날짜 혼잡 예보가 여유예요', replacement_reason: '성산일출봉보다 집중률이 61 낮고 4.2km 거리예요', radius_km: 10,
  }

  it('asks the backend with the visit date and the other course places excluded, then drops AVOID places only', async () => {
    const requestMock = vi.mocked(apiRequest).mockResolvedValue([
      alternative,
      { ...alternative, place_id: 602, place_name: '대수산봉' },
    ])
    const avoiding: CourseCondition = { ...condition, course_place_preferences: [{ place_id: 602, place_name: '대수산봉', preference_type: 'AVOID' }] }

    const result = await courseMockService.getAlternativePlaces(course(), 1, avoiding)

    expect(requestMock).toHaveBeenCalledWith('/places/501/alternatives?date=2026-08-28&limit=3&exclude=502')
    expect(result.map(candidate => candidate.place_id)).toEqual([601])
  })

  it('passes a 3401 (no forecast for that date) through instead of pretending there are no alternatives', async () => {
    vi.mocked(apiRequest).mockRejectedValue(new ApiError(400, 3401, '해당 날짜의 혼잡 예보가 없습니다.'))

    await expect(courseMockService.getAlternativePlaces(course(), 1, condition)).rejects.toMatchObject({ code: 3401 })
  })

  it('posts the chosen place and overlays only the updated items - schedule, other items and server average stay authoritative', async () => {
    const requestMock = vi.mocked(apiRequest).mockResolvedValue({
      course_id: 101,
      average_congestion_rate: 39.5,
      congestion_level: 'QUIET',
      congestion_label: '여유',
      message: '두산봉으로 바꿨어요',
      updated_items: [
        {
          item_id: 1, day_no: 1, position: 1, visit_date: '2026-08-28', place_id: 601, place_name: '두산봉', category_name: '관광지', image_url: 'https://img/601.jpg',
          congestion_rate: 21, congestion_level: 'QUIET', congestion_label: '여유', recommendation_reason: '성산일출봉보다 집중률이 61 낮고 4.2km 거리예요',
          replaced_from_place_id: 501, replaced_from_place_name: '성산일출봉', inbound_distance_m: null, inbound_travel_minutes: null,
        },
        {
          item_id: 2, day_no: 1, position: 2, visit_date: '2026-08-28', place_id: 502, place_name: '광치기해변', category_name: '관광지', image_url: null,
          congestion_rate: 58, congestion_level: 'NORMAL', congestion_label: '보통', recommendation_reason: null,
          replaced_from_place_id: null, replaced_from_place_name: null, inbound_distance_m: 4500, inbound_travel_minutes: 7,
        },
      ],
    })
    const before = course()

    const replaced = await courseMockService.replaceCourseItem(before, 1, alternative)

    expect(requestMock).toHaveBeenCalledWith('/courses/101/items/1/swap', { method: 'POST', body: { place_id: 601 }, auth: false })
    const changed = replaced.days[0].items[0]
    expect(changed).toMatchObject({
      id: 1, place_id: 601, place_name: '두산봉', item_source: 'REPLACEMENT', replaced_from_place_id: 501,
      congestion_level: 'QUIET', congestion_rate: 21, visit_date: '2026-08-28', start_time: '09:00', end_time: '11:00', costs: [],
    })
    expect(changed.latitude).toBeUndefined()   // 옛 장소 좌표를 남기지 않는다
    expect(replaced.days[0].items[1]).toMatchObject({ place_id: 502, inbound_distance_m: 4500, inbound_travel_minutes: 7 })
    expect(replaced.average_congestion_rate).toBe(39.5)
    expect(replaced.car_route).toBeUndefined()   // 옛 장소 기준 렌터카 구간은 버린다 - 화면이 다시 받는다
    expect(replaced.cost_summary).toEqual(before.cost_summary)   // 비용 집계는 서버가 안 바꾸니 로컬도 안 바꾼다
    expect(before.days[0].items[0].place_id).toBe(501)   // 입력 코스는 건드리지 않는다
  })

  it('sends the JWT only for saved courses - temporary courses swap on the public route', async () => {
    const requestMock = vi.mocked(apiRequest).mockResolvedValue({ course_id: 101, average_congestion_rate: null, congestion_level: null, congestion_label: null, message: null, updated_items: [] })

    await courseMockService.replaceCourseItem(course('SAVED'), 1, alternative)

    expect(requestMock).toHaveBeenCalledWith('/courses/101/items/1/swap', expect.objectContaining({ auth: true }))
  })
})
