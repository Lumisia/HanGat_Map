/**
 * AI 코스(POST /courses 응답)·저장 코스(GET /courses/{id}) → 지도 코스(state.course) 변환 (MAP-06).
 *
 * 지도의 코스 표시 엔진(CoursePanel·경로선·일차 전환)은 샘플 코스 형식을 먹는다.
 * 두 응답을 그 형식으로 번역만 하면 화면 코드는 그대로 돈다.
 *
 * 전달 방식이 다르다 - AI 코스는 저장 전이라 id가 없어 sessionStorage로 넘기고(?course=ai),
 * 저장 코스는 id가 있어 URL로 넘긴다(?course=123). URL 쪽은 새로고침·공유가 된다.
 */
import type { MapPlace } from './MapPlaceService'
import type { CourseDetail } from '../CourseService'

/** AI 응답에서 지도가 쓰는 부분 (JSON 은 snake_case) */
export interface AiCourseResult {
  start_date: string
  end_date: string
  days: AiCourseDay[]
}

interface AiCourseDay {
  day_no: number
  visit_date: string
  items: AiCourseItem[]
}

interface AiCourseItem {
  place_id: number | null
  place_name: string
  latitude: number | null
  longitude: number | null
  category_name: string | null
  day_no: number
  position: number
  start_time: string | null
  congestion_rate: number | null
  recommendation_reason: string | null
  costs: { amount?: number | null }[] | null
  inbound_travel_minutes: number | null
}

/** 지도 코스 형식 - CoursePanel·MapCanvas 가 그리는 모양 (예전 샘플 생성기 utils/course.js 의 출력 형식을 계승) */
export interface MapCourse {
  /** 'ai'·'saved' = 여행일 고정 코스. 날짜 슬라이더가 혼잡을 재계산하지 않는다 */
  source: 'ai' | 'saved'
  startDate: string
  days: number
  stops: MapCourseStop[]
  spent: number
  /** 코스 평균 혼잡. 혼잡값이 하나도 없으면 null - 0으로 그리지 않는다 */
  avg: number | null
  /** 인기 코스 대비 비교값 - AI 응답엔 없다. 화면이 비교 문구를 숨기는 근거 */
  pav: null
  move: number
}

export interface MapCourseStop {
  d: number
  t: string
  /** 's'=관광 / 'm'=식사 - 패널 아이콘 구분 */
  k: 's' | 'm'
  o: MapCourseSpot
  c: number | null
  why: string
  cost: number
  mv: number
  alt: string[]
}

/** 핀·경로가 쓰는 최소 장소 모양. 적재 장소(MapPlace)와 매칭되면 그 객체를 그대로 쓴다.
    매칭 안 된 대체 객체에도 업종(cat)은 싣는다 - 코스 패널이 식당·카페·숙소를 회색 '예보 없음' 점이 아니라 업종 핀으로 그린다 */
type MapCourseSpot = MapPlace | { id: number | null, n: string, x: number, y: number, cat: string | null }

const STORAGE_KEY = 'hangat_map_course'

/** 식사류 카테고리 - 패널에서 식사 아이콘으로 표시 */
const MEAL_CATEGORIES = ['음식점', '식당', '카페']

/** 코스 응답의 category_name → 장소 업종 코드(MapPlace.cat 과 같은 값). 카카오 검색으로 넣은 곳은 "음식점 > 한식" 같은 긴 이름이라 포함 여부로 본다 */
function catOf (name: string | null | undefined): string | null {
  const n = name ?? ''
  if (n.includes('카페')) return 'CAFE'                       // '음식점 > 카페'도 카페로 - 음식점보다 먼저 본다
  if (n.includes('음식점') || n.includes('식당')) return 'FOOD'
  if (n.includes('숙소') || n.includes('숙박') || n.includes('호텔') || n.includes('펜션')) return 'LODGING'
  if (n.includes('관광')) return 'TOURIST'
  return null
}

export function toMapCourse (
  result: AiCourseResult,
  /** placeId → 적재 장소. 매칭되면 핀 클릭 시 상세도 열린다 */
  findPlace?: (placeId: number | null, name: string) => MapPlace | null
): MapCourse {
  const stops: MapCourseStop[] = []

  for (const day of [...result.days].sort((a, b) => a.day_no - b.day_no)) {
    const items = [...day.items].sort((a, b) => a.position - b.position)
    for (const it of items) {
      // 좌표 없는 아이템은 지도에 못 그린다 - 만들어내지 않고 뺀다
      if (it.latitude == null || it.longitude == null) continue

      const matched = findPlace?.(it.place_id, it.place_name) ?? null
      stops.push({
        d: it.day_no,
        t: (it.start_time ?? '').slice(0, 5) || '--:--',
        k: MEAL_CATEGORIES.some(m => (it.category_name ?? '').includes(m)) ? 'm' : 's',
        o: matched ?? { id: it.place_id, n: it.place_name, x: it.longitude, y: it.latitude, cat: catOf(it.category_name) },
        c: it.congestion_rate == null ? null : Math.round(it.congestion_rate),
        why: it.recommendation_reason ?? 'AI 추천 코스예요',
        cost: (it.costs ?? []).reduce((a, b) => a + (b.amount ?? 0), 0),
        mv: it.inbound_travel_minutes ?? 0,
        alt: []
      })
    }
  }

  const rated = stops.filter(s => s.c != null)
  return {
    source: 'ai',
    startDate: result.start_date,
    days: result.days.length,
    stops,
    spent: stops.reduce((a, b) => a + b.cost, 0),
    avg: rated.length ? Math.round(rated.reduce((a, b) => a + (b.c ?? 0), 0) / rated.length) : null,
    pav: null,
    move: stops.reduce((a, b) => a + b.mv, 0)
  }
}

/** 저장 코스 상세(CourseService 변환본) → 지도 코스. 코스 상세 페이지의 '지도에서 보기'가 쓴다 */
export function toMapCourseFromDetail (
  detail: CourseDetail,
  findPlace?: (placeId: number | null, name: string) => MapPlace | null
): MapCourse {
  const stops: MapCourseStop[] = []

  for (const day of [...detail.days].sort((a, b) => a.dayNo - b.dayNo)) {
    for (const it of [...day.items].sort((a, b) => a.position - b.position)) {
      if (it.latitude == null || it.longitude == null) continue

      const matched = findPlace?.(it.placeId, it.placeName) ?? null
      stops.push({
        d: day.dayNo,
        t: (it.startTime ?? '').slice(0, 5) || '--:--',
        k: MEAL_CATEGORIES.some(m => (it.categoryName ?? '').includes(m)) ? 'm' : 's',
        o: matched ?? { id: it.placeId, n: it.placeName, x: it.longitude, y: it.latitude, cat: catOf(it.categoryName) },
        c: it.congestionRate == null ? null : Math.round(it.congestionRate),
        why: it.reason ?? '저장한 코스예요',
        cost: 0,   // 상세 응답엔 일정별 비용이 없다 - 지어내지 않고 0 (경비 footer는 bud=0이면 숨는다)
        mv: it.inboundTravelMinutes ?? 0,
        alt: []
      })
    }
  }

  const rated = stops.filter(s => s.c != null)
  return {
    source: 'saved',
    startDate: detail.startDate,
    days: detail.days.length,
    stops,
    spent: 0,
    avg: rated.length ? Math.round(rated.reduce((a, b) => a + (b.c ?? 0), 0) / rated.length) : null,
    pav: null,
    move: stops.reduce((a, b) => a + b.mv, 0)
  }
}

/** AI코스 페이지에서 호출 - 담아 두고 /map?course=ai 로 이동한다 */
export function stashAiCourse (result: AiCourseResult): boolean {
  try {
    sessionStorage.setItem(STORAGE_KEY, JSON.stringify(result))
    return true
  } catch {
    return false
  }
}

/** 지도 페이지에서 호출 - 없으면 null. 꺼내도 지우지 않는다(새로고침 유지) */
export function popAiCourse (): AiCourseResult | null {
  try {
    const raw = sessionStorage.getItem(STORAGE_KEY)
    return raw ? JSON.parse(raw) as AiCourseResult : null
  } catch {
    return null
  }
}
