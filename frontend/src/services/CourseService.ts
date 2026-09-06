/**
 * 코스 조회 연동 (담당: 정동현) - 메인 추천 카드(MAIN_002) / 저장 코스 목록(MY_001) / 코스 상세.
 *
 * 1순위: 백엔드 실데이터. 폴백: 백엔드가 죽어 있으면 기존 목업으로 화면을 유지하고
 * live=false로 라벨을 정직하게 전환한다(날씨·한산 장소와 같은 패턴).
 *
 * 공개 조회는 apiGet, 저장 목록은 JWT가 필요해 apiRequest(auth)를 쓴다 - 토큰 재발급까지
 * 그쪽이 처리한다.
 */
import { apiGet } from './apiClient'
import { apiRequest, getBackendUserId } from '../api/backendClient'
import { homeCourses } from '../data/courses'
import type { CongestionLevel } from '../assets/types'
import type { AlternativePlace } from '../assets/types/course'
import type { AccommodationInput } from '../assets/types/course'

/** 화면이 그리는 코스 카드 한 장 - 메인 추천과 저장 목록이 같은 모양을 쓴다(백엔드 계약도 동일). */
export interface CourseCard {
  /** 라우팅 키. 실데이터는 숫자 id, 목업은 'sample-aewol' 같은 문자열이다 */
  id: string
  title: string
  /** "동부 1박 2일 · 2인" */
  conditionLabel: string
  /** "성산일출봉 → 광치기해변 → 혼인지" */
  stops: string
  highlight: string
  /** 실측 없이 지어내지 않는다 - 비용이 없으면 "요금 확인 필요" */
  budgetLabel: string
  level: CongestionLevel | null
  imageUrl: string | null
  /** 저장 목록에서만 채운다 */
  savedAtLabel: string | null
}

export interface CourseCards {
  /** true = 백엔드 실데이터, false = 목업 폴백 */
  live: boolean
  cards: CourseCard[]
  totalPages: number
  totalElements: number
}

/** 저장 코스 목록 - 개인 데이터라 목업으로 채우지 않고 실패를 실패로 알린다 */
export interface SavedCourses {
  ok: boolean
  cards: CourseCard[]
  totalPages: number
  totalElements: number
}

/** 백엔드 CourseSummaryResponse (course/model/CourseSummaryResponse.java 와 동일 모양) */
interface BackendCourseCard {
  id: number
  course_type: 'USER' | 'SAMPLE'
  title: string | null
  region_name: string | null
  start_date: string
  end_date: string
  duration_days: number
  duration_text: string
  people: number | null
  average_congestion_rate: number | null
  congestion_level: CongestionLevel | null
  congestion_label: string | null
  estimated_cost_min: number | null
  estimated_cost_max: number | null
  image_url: string | null
  place_count: number
  highlight_names: string[]
  saved_at: string | null
}

interface BackendPage<T> {
  content: T[]
  totalPages: number
  totalElements: number
}

/** 백엔드 CourseDetailResponse 의 일정 한 칸 */
export interface CourseDetailItem {
  id: number
  placeId: number
  placeName: string
  categoryName: string
  regionName: string | null
  imageUrl: string | null
  latitude: number | null
  longitude: number | null
  position: number
  startTime: string | null
  /** 지금 예보. null이면 '혼잡 정보 없음' */
  congestionRate: number | null
  congestionLevel: CongestionLevel | null
  congestionLabel: string | null
  /** 저장 시점 스냅숏 - 지금 값과 다르면 예보가 그만큼 움직인 것 */
  plannedCongestionRate: number | null
  reason: string | null
  /** 스왑으로 바뀐 일정에만 값이 있다 */
  replacedFromPlaceName: string | null
  inboundDistanceM: number | null
  inboundTravelMinutes: number | null
}

export interface CourseDetailDay {
  dayNo: number
  visitDate: string
  items: CourseDetailItem[]
}

export interface CourseDetail {
  id: string
  title: string | null
  conditionLabel: string
  durationText: string
  startDate: string
  endDate: string
  level: CongestionLevel | null
  levelLabel: string | null
  averageRate: number | null
  /** 저장 시점 평균 - 지금 값과 다르면 화면이 변화를 알릴 수 있다 */
  plannedAverageRate: number | null
  budgetLabel: string
  /** 일정을 대안으로 교체할 수 있는지 */
  swappable: boolean
  /** 이름 변경·삭제를 할 수 있는지 */
  manageable: boolean
  accommodation: AccommodationInput | null
  days: CourseDetailDay[]
}

interface BackendCourseDetail {
  id: number
  title: string | null
  start_date: string
  end_date: string
  duration_text: string
  people: number | null
  estimated_cost_min: number | null
  estimated_cost_max: number | null
  average_congestion_rate: number | null
  congestion_level: CongestionLevel | null
  congestion_label: string | null
  planned_average_congestion_rate: number | null
  swappable: boolean
  manageable: boolean
  accommodation: AccommodationInput | null
  days: Array<{
    day_no: number
    visit_date: string
    items: Array<{
      id: number
      place_id: number
      place_name: string
      category_name: string
      region_name: string | null
      image_url: string | null
      latitude: number | null
      longitude: number | null
      position: number
      start_time: string | null
      congestion_rate: number | null
      congestion_level: CongestionLevel | null
      congestion_label: string | null
      planned_congestion_rate: number | null
      recommendation_reason: string | null
      replaced_from_place_name: string | null
      inbound_distance_m: number | null
      inbound_travel_minutes: number | null
    }>
  }>
}

/** 교체 결과 요약 - 화면은 상세를 다시 읽으므로 토스트에 쓸 값만 */
export interface SwapSummary {
  averageRate: number | null
  levelLabel: string | null
  message: string | null
}

/** 백엔드 CourseSwapResponse(course/model/CourseSwapResponse.java) 중 이 화면이 쓰는 필드 */
interface BackendSwap {
  average_congestion_rate: number | null
  congestion_label: string | null
  message: string | null
}

const won = (value: number) => value.toLocaleString('ko-KR')

/** 실측 비용이 없으면 지어내지 않는다 - 정직성 원칙 */
const budgetLabelOf = (min: number | null, max: number | null): string => {
  if (min == null && max == null) return '요금 확인 필요'
  if (min != null && max != null && min !== max) return `예상 ${won(min)}~${won(max)}원`
  return `예상 ${won((min ?? max) as number)}원`
}

const conditionLabelOf = (region: string | null, durationText: string, people: number | null) =>
  [region, durationText, people ? `${people}인` : null].filter(Boolean).join(' · ')

const savedAtLabelOf = (savedAt: string | null): string | null => {
  if (!savedAt) return null
  const [, month, day] = savedAt.slice(0, 10).split('-')
  return `${Number(month)}월 ${Number(day)}일`
}

const toCard = (row: BackendCourseCard): CourseCard => ({
  id: String(row.id),
  title: row.title ?? '이름 없는 코스',
  conditionLabel: conditionLabelOf(row.region_name, row.duration_text, row.people),
  stops: row.highlight_names.join(' → '),
  highlight: row.region_name
    ? `${row.region_name} 권역에서 그날 예보가 여유로운 곳들로 이었어요`
    : '그날 예보가 여유로운 곳들로 이었어요',
  budgetLabel: budgetLabelOf(row.estimated_cost_min, row.estimated_cost_max),
  level: row.congestion_level,
  imageUrl: row.image_url,
  savedAtLabel: savedAtLabelOf(row.saved_at),
})

/** 목업 코스를 같은 카드 모양으로 - 백엔드가 죽어도 화면이 비지 않게 */
const mockCards = (source: typeof homeCourses): CourseCard[] =>
  source.map(course => ({
    id: course.id,
    title: course.title,
    conditionLabel: course.conditionLabel,
    stops: '',
    highlight: course.highlight,
    budgetLabel: course.budgetLabel,
    level: null,
    imageUrl: null,
    savedAtLabel: null,
  }))

export const CourseService = {
  /** 메인 추천 코스 3장. 배치 전이면 빈 배열이 오므로 그때도 목업으로 채운다. */
  async getMainCourses (): Promise<CourseCards> {
    try {
      const rows = await apiGet<BackendCourseCard[]>('/main/courses')
      if (rows.length > 0) {
        return {
          live: true,
          cards: rows.map(toCard),
          totalPages: 1,
          totalElements: rows.length,
        }
      }
    } catch {
      // 폴백으로 내려간다
    }
    const cards = mockCards(homeCourses)
    return { live: false, cards, totalPages: 1, totalElements: cards.length }
  },

  /**
   * 내 저장 코스 목록(로그인 필수 - 라우터 가드가 앞에서 막는다).
   *
   * <b>여기만 목업 폴백을 두지 않는다.</b> 날씨·추천 코스는 공용 데이터라 시연용 샘플로
   * 채워도 되지만, "내가 저장한 코스"를 가짜로 채우면 사용자에게 하지 않은 일을 했다고
   * 말하는 셈이다. 실패는 실패로 알린다.
   *
   * @param page 0부터
   */
  async getSavedCourses (page = 0, size = 10): Promise<SavedCourses> {
    try {
      const body = await apiRequest(`/courses?page=${page}&size=${size}`, { auth: true }) as BackendPage<BackendCourseCard>
      return {
        ok: true,
        cards: body.content.map(toCard),
        totalPages: Math.max(1, body.totalPages),
        totalElements: body.totalElements,
      }
    } catch {
      return { ok: false, cards: [], totalPages: 1, totalElements: 0 }
    }
  },

  /**
   * 일정 한 칸의 대안 후보 - GET /places/{placeId}/alternatives (담당 정동현).
   * 같은 카테고리·그 날짜 예보 혼잡 미만·10km 우선(부족하면 20km)·코스 내 중복 제외는 서버가 한다.
   * apiGet은 4xx 본문을 읽지 않아 코드를 잃으므로 apiRequest를 쓴다 - 그 날짜 예보가 없으면 ApiError(3401)가 온다.
   */
  async getAlternatives (placeId: number, visitDate: string, excludeIds: number[]): Promise<AlternativePlace[]> {
    const query = new URLSearchParams({ date: visitDate, limit: '3' })
    if (excludeIds.length) query.set('exclude', excludeIds.join(','))
    return await apiRequest(`/places/${placeId}/alternatives?${query.toString()}`) as AlternativePlace[]
  },

  /**
   * 교체 실행 - POST /courses/{id}/items/{itemId}/swap. 제자리 교체·중복 검사·이동·평균 재계산은 서버.
   * 저장 코스는 본인만 바꿀 수 있어 JWT를 보내고(owned), 임시 코스는 공개 경로다.
   */
  async swapItem (courseId: string, itemId: number, placeId: number, owned: boolean): Promise<SwapSummary> {
    const body = await apiRequest(`/courses/${courseId}/items/${itemId}/swap`, {
      method: 'POST',
      body: { place_id: placeId },
      auth: owned,
    }) as BackendSwap
    return { averageRate: body.average_congestion_rate, levelLabel: body.congestion_label, message: body.message }
  },

  /**
   * 코스 상세. 숫자 id만 백엔드에 묻는다 - 목업 코스는 'sample-aewol' 같은 문자열 id라
   * 전환기 동안 두 경로가 공존한다.
   *
   * 로그인돼 있으면 JWT를 붙인다 - 소유자가 있는 저장 코스는 본인 확인(3307)을 통과해야 열리고,
   * 그래야 swappable/manageable이 참이 되어 교체·이름 변경이 가능하다. 비로그인은 공개 경로.
   */
  async getCourseDetail (id: string): Promise<CourseDetail | null> {
    if (!/^\d+$/.test(id)) return null
    try {
      const row = await apiRequest(`/courses/${id}`, { auth: getBackendUserId() != null }) as BackendCourseDetail
      return {
        id: String(row.id),
        title: row.title,
        conditionLabel: conditionLabelOf(
          row.days[0]?.items[0]?.region_name ?? null, row.duration_text, row.people),
        durationText: row.duration_text,
        startDate: row.start_date,
        endDate: row.end_date,
        level: row.congestion_level,
        levelLabel: row.congestion_label,
        averageRate: row.average_congestion_rate,
        plannedAverageRate: row.planned_average_congestion_rate,
        budgetLabel: budgetLabelOf(row.estimated_cost_min, row.estimated_cost_max),
        swappable: row.swappable,
        manageable: row.manageable,
        accommodation: row.accommodation,
        days: row.days.map(day => ({
          dayNo: day.day_no,
          visitDate: day.visit_date,
          items: day.items.map(item => ({
            id: item.id,
            placeId: item.place_id,
            placeName: item.place_name,
            categoryName: item.category_name,
            regionName: item.region_name,
            imageUrl: item.image_url,
            latitude: item.latitude,
            longitude: item.longitude,
            position: item.position,
            startTime: item.start_time,
            congestionRate: item.congestion_rate,
            congestionLevel: item.congestion_level,
            congestionLabel: item.congestion_label,
            plannedCongestionRate: item.planned_congestion_rate,
            reason: item.recommendation_reason,
            replacedFromPlaceName: item.replaced_from_place_name,
            inboundDistanceM: item.inbound_distance_m,
            inboundTravelMinutes: item.inbound_travel_minutes,
          })),
        })),
      }
    } catch {
      return null
    }
  },
}

export default CourseService
