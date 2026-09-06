import type { AccommodationInput,
  AccuracyType,
  AlternativePlace,
  CostCategory,
  CourseCondition,
  CourseDay,
  CourseItem,
  CourseItemCost,
  CourseResult,
  CarRouteResult,
  CongestionRescheduleOption,
  IndoorOutdoor,
  PlacePreference,
  RegionRef,
  Transport,
  CongestionLevel,
} from '../assets/types/course'
import { getMockWeather, weatherRecommendationAdjustment, weatherWarning } from './weatherMockService'
import { savedCourseMockService } from './savedCourseMockService'
import { apiRequest } from '../api/backendClient.js'
import { ApiError } from '../api/errors.js'

const pause = (ms = 650) => new Promise(resolve => setTimeout(resolve, ms))
const RECOMMENDED_ITEMS_PER_DAY = 3
const FIXED_STAY_MINUTES = 90
const LONG_GAP_MINUTES = 240
const ROUTE_EFFICIENT_DISTANCE_M = 15000
const DAY_SLOTS = [
  { start: '09:00', end: '11:00' },
  { start: '12:00', end: '14:00' },
  { start: '15:00', end: '17:00' },
] as const
const RESCHEDULE_START_TIMES = ['07:00', '09:00', '12:00', '15:00', '17:00'] as const

type RegionCode = RegionRef['code']
type MockPlace = {
  id: number
  name: string
  category: string
  subcategory: 'OREUM' | 'BEACH' | 'CAFE' | 'MARKET' | 'FOREST' | 'CULTURE' | 'CAVE' | 'WATERFALL' | 'DRIVE'
  image: string
  region: RegionCode
  styles: string[]
  congestionRate: number
  lat: number
  lng: number
  alternativeOnly?: boolean
  costPerPerson?: { min: number; max: number }
  island?: boolean
  operatingHours?: { open_time?: string; close_time?: string }
  indoorOutdoor?: IndoorOutdoor
}
type AccommodationAnchor = { lat?: number; lng?: number; region?: RegionCode }

let sequence = 4000
let latestAvoidedPlaceIds = new Set<number>()
let latestAvoidedPlaceNames = new Set<string>()

const places: MockPlace[] = [
  { id: 101, name: '비자림', category: '자연', subcategory: 'FOREST', image: '/images/forest.svg', region: 'EAST', styles: ['NATURE', 'PHOTO'], congestionRate: 22, lat: 33.4913, lng: 126.8114, costPerPerson: { min: 3000, max: 3000 }, operatingHours: { open_time: '09:00', close_time: '18:00' } },
  { id: 102, name: '세화해변', category: '관광지', subcategory: 'BEACH', image: '/images/coast.svg', region: 'EAST', styles: ['NATURE', 'PHOTO'], congestionRate: 47, lat: 33.5252, lng: 126.8596 },
  { id: 103, name: '성산일출봉', category: '관광지', subcategory: 'OREUM', image: '/images/oreum.svg', region: 'EAST', styles: ['NATURE', 'PHOTO'], congestionRate: 68, lat: 33.4581, lng: 126.9425, costPerPerson: { min: 5000, max: 5000 }, operatingHours: { open_time: '07:00', close_time: '20:00' } },
  { id: 104, name: '제주 동문시장', category: '시장', subcategory: 'MARKET', image: '/images/village.svg', region: 'NORTH', styles: ['LOCAL', 'WITH_KIDS'], congestionRate: 56, lat: 33.5116, lng: 126.5260, indoorOutdoor: 'MIXED' },
  { id: 105, name: '우도 산호해변', category: '관광지', subcategory: 'BEACH', image: '/images/coast.svg', region: 'EAST', styles: ['NATURE', 'PHOTO'], congestionRate: 65, lat: 33.5018, lng: 126.9449, island: true },
  { id: 106, name: '제주 돌문화공원', category: '문화', subcategory: 'CULTURE', image: '/images/forest.svg', region: 'NORTH', styles: ['NATURE', 'LOCAL', 'WITH_KIDS'], congestionRate: 18, lat: 33.4485, lng: 126.6591, operatingHours: { open_time: '09:00', close_time: '18:00' }, indoorOutdoor: 'MIXED' },
  { id: 107, name: '사려니숲길', category: '자연', subcategory: 'FOREST', image: '/images/forest.svg', region: 'NORTH', styles: ['NATURE', 'PHOTO'], congestionRate: 29, lat: 33.4220, lng: 126.6265, operatingHours: { open_time: '09:00', close_time: '17:00' } },
  { id: 108, name: '산방산', category: '관광지', subcategory: 'OREUM', image: '/images/oreum.svg', region: 'SOUTH', styles: ['NATURE', 'PHOTO'], congestionRate: 64, lat: 33.2413, lng: 126.3135 },
  { id: 109, name: '애월 해안도로', category: '드라이브', subcategory: 'DRIVE', image: '/images/coast.svg', region: 'WEST', styles: ['CAFE', 'PHOTO'], congestionRate: 38, lat: 33.4622, lng: 126.3098 },
  { id: 110, name: '제주민속촌', category: '문화', subcategory: 'CULTURE', image: '/images/village.svg', region: 'SOUTH', styles: ['LOCAL', 'WITH_KIDS'], congestionRate: 52, lat: 33.3226, lng: 126.8428, operatingHours: { open_time: '08:30', close_time: '18:00' }, indoorOutdoor: 'MIXED' },
  { id: 111, name: '한담해변', category: '관광지', subcategory: 'BEACH', image: '/images/coast.svg', region: 'WEST', styles: ['CAFE', 'PHOTO'], congestionRate: 25, lat: 33.4590, lng: 126.3101 },
  { id: 112, name: '새별오름', category: '자연', subcategory: 'OREUM', image: '/images/oreum.svg', region: 'WEST', styles: ['NATURE', 'PHOTO'], congestionRate: 70, lat: 33.3662, lng: 126.3577 },
  { id: 113, name: '섭지코지', category: '관광지', subcategory: 'BEACH', image: '/images/coast.svg', region: 'EAST', styles: ['NATURE', 'PHOTO'], congestionRate: 42, lat: 33.4239, lng: 126.9306 },
  { id: 114, name: '아부오름', category: '자연', subcategory: 'OREUM', image: '/images/oreum.svg', region: 'EAST', styles: ['NATURE', 'PHOTO'], congestionRate: 19, lat: 33.4487, lng: 126.7772, costPerPerson: { min: 0, max: 0 }, operatingHours: { open_time: '10:00', close_time: '18:00' } },
  { id: 115, name: '월정리 카페거리', category: '카페', subcategory: 'CAFE', image: '/images/coast.svg', region: 'EAST', styles: ['CAFE', 'PHOTO'], congestionRate: 35, lat: 33.5565, lng: 126.7958, indoorOutdoor: 'INDOOR' },
  { id: 116, name: '정방폭포', category: '관광지', subcategory: 'WATERFALL', image: '/images/coast.svg', region: 'SOUTH', styles: ['NATURE', 'PHOTO'], congestionRate: 44, lat: 33.2449, lng: 126.5716 },
  { id: 117, name: '김녕해수욕장', category: '관광지', subcategory: 'BEACH', image: '/images/coast.svg', region: 'EAST', styles: ['NATURE', 'PHOTO'], congestionRate: 28, lat: 33.5572, lng: 126.7593 },
  { id: 118, name: '만장굴', category: '자연', subcategory: 'CAVE', image: '/images/forest.svg', region: 'EAST', styles: ['NATURE', 'WITH_KIDS'], congestionRate: 41, lat: 33.5284, lng: 126.7715, operatingHours: { open_time: '09:00', close_time: '18:00' }, indoorOutdoor: 'MIXED' },
  { id: 119, name: '다랑쉬오름', category: '자연', subcategory: 'OREUM', image: '/images/oreum.svg', region: 'EAST', styles: ['NATURE', 'PHOTO'], congestionRate: 33, lat: 33.4746, lng: 126.8216, costPerPerson: { min: 0, max: 0 } },
  { id: 120, name: '대수산봉', category: '자연', subcategory: 'OREUM', image: '/images/oreum.svg', region: 'EAST', styles: ['NATURE', 'PHOTO'], congestionRate: 17, lat: 33.4390, lng: 126.8990, alternativeOnly: true, costPerPerson: { min: 0, max: 0 } },
  { id: 121, name: '두산봉', category: '자연', subcategory: 'OREUM', image: '/images/oreum.svg', region: 'EAST', styles: ['NATURE', 'PHOTO'], congestionRate: 23, lat: 33.4785, lng: 126.8970, alternativeOnly: true, costPerPerson: { min: 0, max: 0 } },
  { id: 122, name: '지미봉', category: '자연', subcategory: 'OREUM', image: '/images/oreum.svg', region: 'EAST', styles: ['NATURE', 'PHOTO'], congestionRate: 27, lat: 33.4933, lng: 126.8951, alternativeOnly: true, costPerPerson: { min: 0, max: 0 } },
]

const regionCentres: Record<RegionCode, { lat: number; lng: number }> = {
  EAST: { lat: 33.46, lng: 126.83 },
  WEST: { lat: 33.36, lng: 126.31 },
  SOUTH: { lat: 33.25, lng: 126.56 },
  NORTH: { lat: 33.48, lng: 126.55 },
}

const dateAt = (start: string, offset: number) => {
  const date = new Date(`${start}T00:00:00Z`)
  date.setUTCDate(date.getUTCDate() + offset)
  return date.toISOString().slice(0, 10)
}

const dayOffset = (start: string, date: string) =>
  Math.round((new Date(`${date}T00:00:00Z`).getTime() - new Date(`${start}T00:00:00Z`).getTime()) / 86400000)
const normalizePlaceName = (name: string) => name.normalize('NFKC').replace(/\s+/g, '').toLocaleLowerCase('ko-KR')
const minutesFromTime = (time: string) => {
  const [hours, minutes] = time.split(':').map(Number)
  return hours * 60 + minutes
}
const timeFromMinutes = (minutes: number) => `${String(Math.floor(minutes / 60)).padStart(2, '0')}:${String(minutes % 60).padStart(2, '0')}`
const overlaps = (startA: string, endA: string, startB: string, endB: string) =>
  minutesFromTime(startA) < minutesFromTime(endB) && minutesFromTime(startB) < minutesFromTime(endA)
const findPlace = (placeId: number | undefined, placeName: string) =>
  (placeId == null ? undefined : places.find(place => place.id === placeId)) ?? places.find(place => normalizePlaceName(place.name) === normalizePlaceName(placeName))
const inferAccommodationRegion = (name: string): RegionCode | undefined => {
  const normalized = normalizePlaceName(name)
  if (['성산', '세화', '월정', '구좌', '표선'].some(keyword => normalized.includes(keyword))) return 'EAST'
  if (['애월', '협재', '한림', '한경'].some(keyword => normalized.includes(keyword))) return 'WEST'
  if (['서귀포', '중문', '산방', '안덕'].some(keyword => normalized.includes(keyword))) return 'SOUTH'
  if (['제주시', '공항', '조천'].some(keyword => normalized.includes(keyword))) return 'NORTH'
  return undefined
}
const accommodationAnchor = (condition: CourseCondition): AccommodationAnchor | undefined => {
  const accommodation = condition.accommodation
  if (!accommodation?.place_name.trim()) return undefined
  return {
    lat: accommodation.latitude,
    lng: accommodation.longitude,
    region: accommodation.region ?? inferAccommodationRegion(`${accommodation.place_name} ${accommodation.address ?? ''}`),
  }
}
const isWithinOperatingHours = (place: MockPlace | undefined, startTime: string, endTime: string) => {
  if (!place?.operatingHours) return true
  const { open_time: openTime, close_time: closeTime } = place.operatingHours
  if (openTime && minutesFromTime(startTime) < minutesFromTime(openTime)) return false
  if (closeTime && minutesFromTime(endTime) > minutesFromTime(closeTime)) return false
  return true
}
const indoorOutdoorOf = (place: MockPlace | undefined): IndoorOutdoor => place?.indoorOutdoor ?? 'OUTDOOR'

function applyWeather(item: CourseItem, place: MockPlace | undefined, fixed = false) {
  const weather = getMockWeather(item.visit_date, item.start_time)
  item.weather_condition = weather.weather_condition
  item.temperature = weather.temperature
  item.precipitation_probability = weather.precipitation_probability
  item.weather_warning = weatherWarning(weather, indoorOutdoorOf(place), fixed)
}

function makeCost(id: number, courseId: number, itemId: number, categoryName: string, people: number): CourseItemCost {
  let category: CostCategory = 'ACTIVITY'
  let accuracyType: AccuracyType = 'ESTIMATED'
  let amountMin: number | undefined = 12000 * people
  let amountMax: number | undefined = 18000 * people
  let basisText = `1인 예상가 × ${people}명`

  if (categoryName === '시장' || categoryName === '식당') {
    category = 'FOOD'
    accuracyType = 'VERIFIED'
    amountMin = amountMax = 10000 * people
    basisText = `검증 메뉴가 10,000원 × ${people}명`
  } else if (id % 5 === 0) {
    accuracyType = 'UNKNOWN'
    amountMin = undefined
    amountMax = undefined
    basisText = '현장 가격 확인 필요'
  }

  return { id, course_id: courseId, course_item_id: itemId, category, accuracy_type: accuracyType, amount_min: amountMin, amount_max: amountMax, currency: 'KRW', basis_text: basisText }
}

function makePlaceCost(id: number, courseId: number, itemId: number, place: MockPlace | undefined, categoryName: string, people: number): CourseItemCost {
  if (!place?.costPerPerson) return makeCost(id, courseId, itemId, categoryName, people)
  return {
    id,
    course_id: courseId,
    course_item_id: itemId,
    category: 'ACTIVITY',
    accuracy_type: 'ESTIMATED',
    amount_min: place.costPerPerson.min * people,
    amount_max: place.costPerPerson.max * people,
    currency: 'KRW',
    basis_text: `1인 예상가 ${place.costPerPerson.min.toLocaleString()}~${place.costPerPerson.max.toLocaleString()}원 × ${people}명`,
  }
}

export function calculateCourseCostSummary(items: CourseItem[]) {
  const costs = items.flatMap(item => item.costs)
  const verified = costs.filter(cost => cost.accuracy_type === 'VERIFIED')
  const estimated = costs.filter(cost => cost.accuracy_type === 'ESTIMATED')
  return {
    verified_amount: verified.reduce((sum, cost) => sum + (cost.amount_max ?? cost.amount_min ?? 0), 0),
    estimated_min: estimated.reduce((sum, cost) => sum + (cost.amount_min ?? cost.amount_max ?? 0), 0) + 60000,
    estimated_max: estimated.reduce((sum, cost) => sum + (cost.amount_max ?? cost.amount_min ?? 0), 0) + 110000,
    unknown_count: costs.filter(cost => cost.accuracy_type === 'UNKNOWN').length,
  }
}

function recalc(course: CourseResult) {
  const items = course.days.flatMap(day => day.items)
  const summary = calculateCourseCostSummary(items)
  course.cost_summary = summary
  course.estimated_cost_min = summary.verified_amount + summary.estimated_min
  course.estimated_cost_max = summary.verified_amount + summary.estimated_max
  const rates = items.flatMap(item => item.congestion_rate == null ? [] : [item.congestion_rate])
  course.average_congestion_rate = rates.length ? Math.round(rates.reduce((sum, rate) => sum + rate, 0) / rates.length) : undefined
  return course
}

function slotIsAvailable(day: CourseDay, slot: { start: string; end: string }) {
  return day.items.every(item => !item.start_time || !item.end_time || !overlaps(slot.start, slot.end, item.start_time, item.end_time))
}

function availableSlots(day: CourseDay) {
  return DAY_SLOTS.filter(slot => slotIsAvailable(day, slot))
}

function makeWantItem(courseId: number, itemId: number, preference: PlacePreference, day: CourseDay, startTime: string, endTime: string, fixed: boolean, people: number): CourseItem {
  const metadata = findPlace(preference.place_id, preference.place_name)
  const internalPlaceId = metadata?.id ?? preference.place_id ?? 0
  const rate = metadata?.congestionRate ?? (fixed ? 58 : 34)
  const item: CourseItem = {
    id: itemId,
    course_id: courseId,
    place_id: internalPlaceId,
    source_code: preference.source_code,
    source_place_id: preference.source_place_id,
    latitude: preference.latitude ?? metadata?.lat,
    longitude: preference.longitude ?? metadata?.lng,
    place_name: preference.place_name,
    category_name: metadata?.category ?? '관광지',
    image_url: metadata?.image ?? '/images/placeholder.svg',
    day_no: day.day_no,
    position: day.items.length + 1,
    visit_date: day.visit_date,
    start_time: startTime,
    end_time: endTime,
    item_source: fixed ? 'USER_FIXED' : 'AI_RECOMMENDED',
    congestion_rate: rate,
    congestion_level: rate < 35 ? 'QUIET' : rate < 65 ? 'NORMAL' : 'CROWDED',
    recommendation_reason_code: fixed ? 'ROUTE' : 'STYLE',
    recommendation_reason: fixed ? '사용자가 지정한 일정으로 유지했어요.' : '꼭 가고 싶은 장소로 선택해 일정에 포함했어요.',
    operating_hours_warning: metadata?.operatingHours ? !isWithinOperatingHours(metadata, startTime, endTime) : undefined,
    costs: [makePlaceCost(itemId + 9000, courseId, itemId, metadata, metadata?.category ?? '관광지', people)],
  }
  applyWeather(item, metadata, fixed)
  return item
}

function accommodationMatchScore(place: MockPlace, accommodation?: AccommodationAnchor) {
  if (!accommodation) return 0
  if (accommodation.lat != null && accommodation.lng != null) {
    const distanceKm = haversineKm(accommodation as { lat: number; lng: number }, place)
    if (distanceKm <= 20) return 6
    if (distanceKm <= 35) return 2
    return 0
  }
  return accommodation.region === place.region ? 4 : 0
}

function recommendationScore(place: MockPlace, condition: CourseCondition, dayRegion: RegionCode, previousPlace: MockPlace | undefined, accommodation: AccommodationAnchor | undefined, hasFixedSchedule: boolean, accommodationWeight: number) {
  const preferredRegions = new Set(condition.course_regions.map(region => region.code))
  const selectedStyles = new Set(condition.course_styles.map(style => style.code))
  const styleMatches = place.styles.filter(style => selectedStyles.has(style)).length
  let score = 0
  if (!hasFixedSchedule) score += accommodationMatchScore(place, accommodation) * accommodationWeight
  if (preferredRegions.has(place.region)) score += 3
  if (styleMatches) score += 2
  if (place.congestionRate < 35) score += 2
  if (place.region === dayRegion) score += 2
  if (previousPlace?.region === place.region) score += 2
  return score
}

function makeRecommendedItem(courseId: number, itemId: number, place: MockPlace, day: CourseDay, slot: { start: string; end: string }, people: number, accommodationInfluenced: boolean, weatherInfluenced: boolean): CourseItem {
  const rate = place.congestionRate
  const item: CourseItem = {
    id: itemId,
    course_id: courseId,
    place_id: place.id,
    place_name: place.name,
    category_name: place.category,
    image_url: place.image,
    latitude: place.lat,
    longitude: place.lng,
    day_no: day.day_no,
    position: day.items.length + 1,
    visit_date: day.visit_date,
    start_time: slot.start,
    end_time: slot.end,
    item_source: 'AI_RECOMMENDED',
    congestion_rate: rate,
    congestion_level: rate < 35 ? 'QUIET' : rate < 65 ? 'NORMAL' : 'CROWDED',
    recommendation_reason_code: 'ROUTE',
    recommendation_reason: '',
    accommodation_influenced: accommodationInfluenced || undefined,
    weather_influenced: weatherInfluenced || undefined,
    costs: [makePlaceCost(itemId + 9000, courseId, itemId, place, place.category, people)],
  }
  applyWeather(item, place)
  return item
}

function haversineKm(a: { lat: number; lng: number }, b: { lat: number; lng: number }) {
  const toRadians = (degrees: number) => degrees * Math.PI / 180
  const earthRadiusKm = 6371
  const latDistance = toRadians(b.lat - a.lat)
  const lngDistance = toRadians(b.lng - a.lng)
  const value = Math.sin(latDistance / 2) ** 2
    + Math.cos(toRadians(a.lat)) * Math.cos(toRadians(b.lat)) * Math.sin(lngDistance / 2) ** 2
  return earthRadiusKm * 2 * Math.atan2(Math.sqrt(value), Math.sqrt(1 - value))
}

function fallbackLocation(item: CourseItem, dayRegion: RegionCode) {
  const metadata = findPlace(item.place_id, item.place_name)
  if (metadata) return metadata
  if (item.latitude != null && item.longitude != null) return { lat: item.latitude, lng: item.longitude, region: dayRegion, styles: [] as string[], island: false }
  const centre = regionCentres[dayRegion]
  const offset = (Math.abs(item.place_id) % 7) * 0.002
  return { lat: centre.lat + offset, lng: centre.lng - offset, region: dayRegion, styles: [] as string[], island: false }
}

function travelMinutes(distanceKm: number, transport: Transport) {
  const speed = { RENTAL_CAR: 38, PUBLIC_TRANSIT: 24, TAXI: 40, WALK_BIKE: 12 }[transport]
  const transferMinutes = { RENTAL_CAR: 4, PUBLIC_TRANSIT: 12, TAXI: 5, WALK_BIKE: 0 }[transport]
  return Math.max(5, Math.round(distanceKm / speed * 60 + transferMinutes))
}

function routeLeg(from: { lat: number; lng: number; island?: boolean }, to: { lat: number; lng: number; island?: boolean }, transport: Transport) {
  if (from.island || to.island) return undefined
  const distanceKm = Math.max(1.2, haversineKm(from, to) * 1.28)
  return {
    distance_m: Math.round(distanceKm * 1000 / 100) * 100,
    travel_minutes: travelMinutes(distanceKm, transport),
  }
}

function accommodationLocation(accommodation?: AccommodationInput | null) {
  if (!accommodation) return undefined
  return { lat: accommodation.latitude, lng: accommodation.longitude }
}

function recalculateDayTravel(
  day: CourseDay,
  transport: Transport,
  accommodation?: AccommodationInput | null,
) {
  const fallbackRegion = findPlace(day.items[0]?.place_id ?? 0, day.items[0]?.place_name ?? '')?.region ?? accommodation?.region ?? 'EAST'
  day.items.forEach((item, index) => {
    item.position = index + 1
    item.inbound_distance_m = undefined
    item.inbound_travel_minutes = undefined
    if (index === 0) return
    const leg = routeLeg(fallbackLocation(day.items[index - 1], fallbackRegion), fallbackLocation(item, fallbackRegion), transport)
    item.inbound_distance_m = leg?.distance_m
    item.inbound_travel_minutes = leg?.travel_minutes
  })

  day.accommodation_departure_distance_m = undefined
  day.accommodation_departure_travel_minutes = undefined
  day.accommodation_return_distance_m = undefined
  day.accommodation_return_travel_minutes = undefined
  const stay = accommodationLocation(accommodation)
  if (!stay || !day.items.length) return
  const departure = routeLeg(stay, fallbackLocation(day.items[0], fallbackRegion), transport)
  const returning = routeLeg(fallbackLocation(day.items.at(-1)!, fallbackRegion), stay, transport)
  day.accommodation_departure_distance_m = departure?.distance_m
  day.accommodation_departure_travel_minutes = departure?.travel_minutes
  day.accommodation_return_distance_m = returning?.distance_m
  day.accommodation_return_travel_minutes = returning?.travel_minutes
}

function refreshDayOrder(day: CourseDay) {
  day.items.sort((a, b) => (a.start_time ?? '').localeCompare(b.start_time ?? ''))
  day.items.forEach((item, index) => {
    item.day_no = day.day_no
    item.visit_date = day.visit_date
    item.position = index + 1
    item.gap_before = undefined
    const previous = day.items[index - 1]
    if (!previous?.end_time || !item.start_time) return
    const gapMinutes = minutesFromTime(item.start_time) - minutesFromTime(previous.end_time)
    if (gapMinutes >= LONG_GAP_MINUTES) item.gap_before = { start_time: previous.end_time, end_time: item.start_time, minutes: gapMinutes, type: 'FREE_TIME' }
  })
}

function mockCongestionAt(place: MockPlace, visitDate: string, startTime: string) {
  const hour = Number(startTime.slice(0, 2))
  const day = Number(visitDate.slice(-2))
  const hourAdjustment = hour <= 9 ? -25 : hour <= 11 ? -12 : hour >= 15 ? 8 : 2
  const dayAdjustment = ((day + place.id) % 4) * 3 - 4
  return Math.min(95, Math.max(12, place.congestionRate + hourAdjustment + dayAdjustment))
}

function congestionLevel(rate: number): CourseItem['congestion_level'] {
  return rate < 35 ? 'QUIET' : rate < 65 ? 'NORMAL' : 'CROWDED'
}

function rescheduleCandidates(course: CourseResult, itemId: number): CongestionRescheduleOption[] {
  const item = course.days.flatMap(day => day.items).find(candidate => candidate.id === itemId)
  if (!item || item.congestion_rate == null) return []
  const place = findPlace(item.place_id, item.place_name)
  if (!place) return []
  const duration = item.start_time && item.end_time ? minutesFromTime(item.end_time) - minutesFromTime(item.start_time) : 120
  return course.days
    .flatMap(day => RESCHEDULE_START_TIMES.map(startTime => {
      const endTime = timeFromMinutes(minutesFromTime(startTime) + duration)
      const rate = mockCongestionAt(place, day.visit_date, startTime)
      const weather = getMockWeather(day.visit_date, startTime)
      const conflict = day.items.some(other => other.id !== itemId && other.start_time && other.end_time && overlaps(startTime, endTime, other.start_time, other.end_time))
      return {
        visit_date: day.visit_date,
        start_time: startTime,
        end_time: endTime,
        congestion_rate: rate,
        congestion_level: congestionLevel(rate)!,
        ...weather,
        conflict,
      }
    }))
    .filter(option => !(option.visit_date === item.visit_date && option.start_time === item.start_time))
    .filter(option => !option.conflict && option.congestion_rate < item.congestion_rate! && isWithinOperatingHours(place, option.start_time, option.end_time))
    .sort((a, b) => a.congestion_rate - b.congestion_rate || a.visit_date.localeCompare(b.visit_date) || a.start_time.localeCompare(b.start_time))
    .slice(0, 3)
    .map(({ conflict: _conflict, ...option }) => option)
}

type ReasonCandidate = { key: string; code: CourseItem['recommendation_reason_code']; text: string }

function buildRecommendationReason(item: CourseItem, place: MockPlace, condition: CourseCondition, previousPlace: MockPlace | undefined, usedReasonKeys: Set<string>): ReasonCandidate {
  const matchedRegion = condition.course_regions.find(region => region.code === place.region)
  const matchedStyle = condition.course_styles.find(style => place.styles.includes(style.code))
  const isLowCongestion = place.congestionRate < 35
  const isRouteEfficient = Boolean(item.inbound_distance_m && item.inbound_distance_m <= ROUTE_EFFICIENT_DISTANCE_M)
  const isSameRegionRoute = Boolean(matchedRegion && previousPlace?.region === place.region && isRouteEfficient)
  const candidates: ReasonCandidate[] = []

  if (item.weather_influenced && item.weather_condition === 'RAIN' && indoorOutdoorOf(place) === 'INDOOR') candidates.push({ key: 'WEATHER_RAIN_INDOOR', code: 'ROUTE', text: '비 예보를 고려해 실내 장소를 우선 배치했어요.' })
  if (item.weather_influenced && item.weather_condition === 'SUNNY' && indoorOutdoorOf(place) === 'OUTDOOR') candidates.push({ key: 'WEATHER_SUNNY_OUTDOOR', code: 'ROUTE', text: '맑은 시간대라 야외 자연 장소를 추천했어요.' })
  if (item.accommodation_influenced && item.position === 1) candidates.push({ key: 'ACCOMMODATION_START', code: 'ROUTE', text: '숙소와 가까워 첫 일정으로 이동하기 좋은 장소예요.' })
  if (isLowCongestion) candidates.push({ key: 'CONGESTION', code: 'CONGESTION', text: '예상 혼잡도가 낮은 시간대라 우선 추천했어요.' })
  if (matchedRegion && matchedStyle) candidates.push({ key: 'REGION_STYLE', code: 'STYLE', text: `선호한 ${matchedRegion.name} 권역과 ${matchedStyle.name} 여행 스타일을 함께 고려했어요.` })
  if (isSameRegionRoute && matchedRegion) candidates.push({ key: 'REGION_ROUTE', code: 'ROUTE', text: `${matchedRegion.name}권 안에서 이동 부담이 적도록 이어서 배치했어요.` })
  if (matchedRegion) candidates.push({ key: 'REGION', code: 'ROUTE', text: `선호한 ${matchedRegion.name} 권역의 장소예요.` })
  if (matchedStyle) candidates.push({ key: 'STYLE', code: 'STYLE', text: `선택한 ${matchedStyle.name} 여행 스타일과 잘 맞는 장소예요.` })
  if (isRouteEfficient) candidates.push({ key: 'ROUTE', code: 'ROUTE', text: '앞 장소와 가까워 이동 동선을 줄일 수 있어요.' })
  candidates.push({ key: 'BALANCED', code: 'ROUTE', text: '하루 이동 동선과 예상 혼잡도를 함께 고려해 배치했어요.' })

  const selected = candidates.find(candidate => !usedReasonKeys.has(candidate.key)) ?? candidates[0]
  usedReasonKeys.add(selected.key)
  return selected
}

function finalizeDay(day: CourseDay, dayRegion: RegionCode, condition: CourseCondition, usedReasonKeys: Set<string>) {
  day.items.sort((a, b) => (a.start_time ?? '').localeCompare(b.start_time ?? ''))
  day.items.forEach((item, index) => {
    item.position = index + 1
    item.inbound_distance_m = undefined
    item.inbound_travel_minutes = undefined
    item.gap_before = undefined

    const currentPlace = findPlace(item.place_id, item.place_name)
    const previousItem = day.items[index - 1]
    item.operating_hours_warning = currentPlace?.operatingHours && item.start_time && item.end_time
      ? !isWithinOperatingHours(currentPlace, item.start_time, item.end_time)
      : undefined

    if (!previousItem) return
    if (previousItem.end_time && item.start_time) {
      const gapMinutes = minutesFromTime(item.start_time) - minutesFromTime(previousItem.end_time)
      if (gapMinutes >= LONG_GAP_MINUTES) {
        item.gap_before = { start_time: previousItem.end_time, end_time: item.start_time, minutes: gapMinutes, type: 'FREE_TIME' }
      }
    }
    const from = fallbackLocation(previousItem, dayRegion)
    const to = fallbackLocation(item, dayRegion)
    if (from.island || to.island) return
    const roadDistanceKm = Math.max(1.2, haversineKm(from, to) * 1.28)
    item.inbound_distance_m = Math.round(roadDistanceKm * 1000 / 100) * 100
    item.inbound_travel_minutes = travelMinutes(roadDistanceKm, condition.transport)
  })

  day.items.forEach((item, index) => {
    const isPreference = condition.course_place_preferences.some(preference => preference.preference_type === 'WANT' && (preference.place_id === item.place_id || normalizePlaceName(preference.place_name) === normalizePlaceName(item.place_name)))
    if (item.item_source !== 'AI_RECOMMENDED' || isPreference) return
    const currentPlace = findPlace(item.place_id, item.place_name)
    if (!currentPlace) return
    const previousItem = day.items[index - 1]
    const previousPlace = previousItem ? findPlace(previousItem.place_id, previousItem.place_name) : undefined
    const reason = buildRecommendationReason(item, currentPlace, condition, previousPlace, usedReasonKeys)
    item.recommendation_reason_code = reason.code
    item.recommendation_reason = reason.text
  })
}

function chooseDayRegions(days: CourseDay[], condition: CourseCondition, fixedPreferences: PlacePreference[], accommodation?: AccommodationAnchor) {
  const preferred = condition.course_regions.map(region => region.code)
  return days.map((day, index) => {
    const fixedHere = fixedPreferences.find(preference => preference.fixed_date === day.visit_date)
    const fixedRegion = fixedHere && findPlace(fixedHere.place_id, fixedHere.place_name)?.region
    return fixedRegion ?? accommodation?.region ?? preferred[index % preferred.length] ?? 'EAST'
  })
}

function placeFixedPreferences(days: CourseDay[], preferences: PlacePreference[], courseId: number, people: number) {
  preferences.forEach((preference, index) => {
    const requestedIndex = preference.fixed_date ? dayOffset(days[0].visit_date, preference.fixed_date) : 0
    const day = days[Math.min(days.length - 1, Math.max(0, requestedIndex))]
    const startTime = preference.fixed_time ?? availableSlots(day)[0]?.start ?? '09:00'
    const endTime = preference.fixed_time
      ? timeFromMinutes(minutesFromTime(preference.fixed_time) + FIXED_STAY_MINUTES)
      : DAY_SLOTS.find(slot => slot.start === startTime)?.end ?? timeFromMinutes(minutesFromTime(startTime) + FIXED_STAY_MINUTES)
    if (!slotIsAvailable(day, { start: startTime, end: endTime })) throw new Error('사용자 지정 일정 시간이 서로 겹칩니다.')
    day.items.push(makeWantItem(courseId, courseId * 100 + index, preference, day, startTime, endTime, true, people))
  })
}

function placeFlexibleWants(days: CourseDay[], dayRegions: RegionCode[], preferences: PlacePreference[], courseId: number, people: number, itemOffset: number) {
  preferences.forEach((preference, index) => {
    const metadata = findPlace(preference.place_id, preference.place_name)
    const preferredRegion = metadata?.region ?? (preference.latitude != null && preference.longitude != null
      ? Object.entries(regionCentres).sort(([, first], [, second]) => haversineKm({ lat: preference.latitude!, lng: preference.longitude! }, first) - haversineKm({ lat: preference.latitude!, lng: preference.longitude! }, second))[0][0] as RegionCode
      : undefined)
    const candidates = days
      .map((day, dayIndex) => ({ day, dayIndex, slots: availableSlots(day) }))
      .filter(candidate => candidate.slots.length)
      .sort((a, b) => {
        const regionDifference = Number(dayRegions[b.dayIndex] === preferredRegion) - Number(dayRegions[a.dayIndex] === preferredRegion)
        return regionDifference || a.day.items.length - b.day.items.length || a.dayIndex - b.dayIndex
      })
    const target = candidates[0]
    if (!target) throw new Error('꼭 가고 싶은 장소를 배치할 빈 시간이 부족합니다.')
    const slot = target.slots[0]
    target.day.items.push(makeWantItem(courseId, courseId * 100 + itemOffset + index, preference, target.day, slot.start, slot.end, false, people))
  })
}

async function generateMockCourse(condition: CourseCondition, generationReason: CourseResult['generation_reason']): Promise<CourseResult> {
  await pause()
  const courseId = ++sequence
  const dayCount = Math.max(1, Math.round((new Date(condition.end_date).getTime() - new Date(condition.start_date).getTime()) / 86400000) + 1)
  const days = Array.from({ length: dayCount }, (_, index) => ({ day_no: index + 1, visit_date: dateAt(condition.start_date, index), items: [] as CourseItem[] }))
  const wants = condition.course_place_preferences.filter(preference => preference.preference_type === 'WANT')
  const fixedWants = wants.filter(preference => preference.fixed_date || preference.fixed_time)
  const flexibleWants = wants.filter(preference => !preference.fixed_date && !preference.fixed_time)
  const avoids = condition.course_place_preferences.filter(preference => preference.preference_type === 'AVOID')

  latestAvoidedPlaceIds = new Set(avoids.flatMap(preference => preference.place_id == null ? [] : [preference.place_id]))
  latestAvoidedPlaceNames = new Set(avoids.map(preference => normalizePlaceName(preference.place_name)))

  placeFixedPreferences(days, fixedWants, courseId, condition.people)
  const accommodation = accommodationAnchor(condition)
  const fixedDates = new Set(fixedWants.flatMap(preference => preference.fixed_date ? [preference.fixed_date] : []))
  const dayRegions = chooseDayRegions(days, condition, fixedWants, accommodation)
  placeFlexibleWants(days, dayRegions, flexibleWants, courseId, condition.people, fixedWants.length)

  const wantedIds = new Set(wants.flatMap(preference => preference.place_id == null ? [] : [preference.place_id]))
  const wantedNames = new Set(wants.map(preference => normalizePlaceName(preference.place_name)))
  const usedPlaceIds = new Set(days.flatMap(day => day.items.map(item => item.place_id)))
  let recommendationIndex = 0

  for (let round = 0; round < RECOMMENDED_ITEMS_PER_DAY; round += 1) {
    for (const [dayIndex, day] of days.entries()) {
      if (day.items.length >= RECOMMENDED_ITEMS_PER_DAY) continue
      const slots = availableSlots(day)
      if (!slots.length) continue
      const previousItem = [...day.items].sort((a, b) => (a.start_time ?? '').localeCompare(b.start_time ?? '')).at(-1)
      const previousPlace = previousItem && findPlace(previousItem.place_id, previousItem.place_name)
      const candidates = places
        .filter(place => !place.island)
        .filter(place => !place.alternativeOnly)
        .filter(place => !usedPlaceIds.has(place.id))
        .filter(place => !latestAvoidedPlaceIds.has(place.id) && !latestAvoidedPlaceNames.has(normalizePlaceName(place.name)))
        .filter(place => !wantedIds.has(place.id) && !wantedNames.has(normalizePlaceName(place.name)))
        .map(place => ({
          place,
          score: recommendationScore(
            place,
            condition,
            dayRegions[dayIndex],
            previousPlace,
            accommodation,
            fixedDates.has(day.visit_date),
            round === 0 || round === RECOMMENDED_ITEMS_PER_DAY - 1 ? 2 : 1,
          ),
        }))
        .sort((a, b) => b.score - a.score || (generationReason === 'USER_REGENERATE' ? b.place.id - a.place.id : a.place.id - b.place.id))
      const placement = candidates
        .flatMap(candidate => slots.map(slot => {
          const weather = getMockWeather(day.visit_date, slot.start)
          const weatherScore = weatherRecommendationAdjustment(weather, indoorOutdoorOf(candidate.place), candidate.place.subcategory)
          return { ...candidate, slot, weatherScore, combinedScore: candidate.score + weatherScore }
        }))
        .sort((a, b) => b.combinedScore - a.combinedScore || (generationReason === 'USER_REGENERATE' ? b.place.id - a.place.id : a.place.id - b.place.id))
        .find(candidate => isWithinOperatingHours(candidate.place, candidate.slot.start, candidate.slot.end))
      if (!placement) continue
      usedPlaceIds.add(placement.place.id)
      const accommodationInfluenced = !fixedDates.has(day.visit_date) && accommodationMatchScore(placement.place, accommodation) > 0
      day.items.push(makeRecommendedItem(courseId, courseId * 100 + wants.length + recommendationIndex++, placement.place, day, placement.slot, condition.people, accommodationInfluenced, placement.weatherScore > 0))
    }
  }

  const usedReasonKeys = new Set<string>()
  for (const [dayIndex, day] of days.entries()) {
    finalizeDay(day, dayRegions[dayIndex], condition, usedReasonKeys)
    recalculateDayTravel(day, condition.transport, condition.accommodation)
  }

  return recalc({
    id: courseId,
    course_type: 'USER',
    generation_reason: generationReason,
    status: 'READY',
    start_date: condition.start_date,
    end_date: condition.end_date,
    people: condition.people,
    budget_total: condition.budget_total,
    transport: condition.transport,
    accommodation: condition.accommodation ? { ...condition.accommodation } : undefined,
    days,
  })
}

export function toCourseRequestPayload(condition: CourseCondition): CourseCondition {
  return {
    start_date: condition.start_date,
    end_date: condition.end_date,
    people: condition.people,
    budget_total: condition.budget_total,
    transport: condition.transport,
    course_regions: condition.course_regions,
    course_styles: condition.course_styles,
    course_place_preferences: condition.course_place_preferences,
    ...(condition.accommodation
      ? { accommodation: { ...condition.accommodation } }
      : {}),
  }
}

export function applyAccommodationSelection(
  course: CourseResult,
  accommodation: AccommodationInput,
): CourseResult {
  return {
    ...course,
    accommodation: { ...accommodation },
  }
}

async function generate(condition: CourseCondition): Promise<CourseResult> {
  return await apiRequest('/courses', {
    method: 'POST',
    body: toCourseRequestPayload(condition),
  }) as CourseResult
}

export function courseGenerationErrorMessage(error: unknown): string {
  if (error instanceof ApiError && error.status === 503) {
    return error.message || 'AI 코스 생성 서버가 일시적으로 혼잡합니다. 잠시 후 다시 시도해 주세요.'
  }
  return '코스를 생성하지 못했어요. 다시 시도해 주세요.'
}

async function updateAccommodation(
  course: CourseResult,
  accommodation: AccommodationInput,
): Promise<AccommodationInput> {
  return await apiRequest(`/courses/${course.id}/accommodation`, {
    method: 'PATCH',
    auth: !course.claim_token,
    body: {
      accommodation,
      ...(course.claim_token ? { claim_token: course.claim_token } : {}),
    },
  }) as AccommodationInput
}

async function getRecommendedAccommodations(
  course: CourseResult,
): Promise<import('../assets/types/course').AccommodationRecommendation[]> {
  const items = await apiRequest(`/courses/${course.id}/accommodations/search`, {
    method: 'POST',
    auth: !course.claim_token,
    body: course.claim_token ? { claim_token: course.claim_token } : {},
  }) as AccommodationInput[]
  return items.map(item => ({
    ...item,
    recommendation_reason: '현재 코스의 저장된 장소 주변에서 확인한 Kakao 숙박 장소예요.',
  }))
}

async function getCarRoute(course: CourseResult): Promise<CarRouteResult> {
  return await apiRequest(`/courses/${course.id}/routes/car`, {
    method: 'GET',
    auth: !course.claim_token && course.status === 'SAVED',
  }) as CarRouteResult
}

export const generateMockCourseForTest = generateMockCourse

/** 백엔드 CourseSwapResponse(course/model/CourseSwapResponse.java, snake_case) - 교체된 칸과 이동이 바뀐 다음 칸만 온다 */
interface CourseSwapResponse {
  course_id: number
  average_congestion_rate: number | null
  congestion_level: CongestionLevel | null
  congestion_label: string | null
  message: string | null
  updated_items: Array<{
    item_id: number; day_no: number; position: number; visit_date: string
    place_id: number; place_name: string; category_name: string; image_url: string | null
    congestion_rate: number | null; congestion_level: CongestionLevel | null; congestion_label: string | null
    recommendation_reason: string | null; replaced_from_place_id: number | null; replaced_from_place_name: string | null
    inbound_distance_m: number | null; inbound_travel_minutes: number | null
  }>
}

export const courseMockService = {
  generateCourse: (condition: CourseCondition) => generate(condition),
  regenerateCourse: (_condition: CourseCondition): Promise<CourseResult> => Promise.reject(new Error('코스 재생성은 아직 지원되지 않습니다.')),
  updateAccommodation,
  getRecommendedAccommodations,
  getCarRoute,
  applyAccommodationSelection,
  recalculateRouteWithAccommodation: (condition: CourseCondition, accommodation: AccommodationInput) => generateMockCourse({
    ...JSON.parse(JSON.stringify(condition)) as CourseCondition,
    accommodation: { ...accommodation },
  }, 'USER_REGENERATE'),
  /**
   * 대안 후보 - 백엔드 GET /places/{place_id}/alternatives (담당: 정동현).
   * 같은 카테고리, 그 날짜 예보 혼잡 미만, 10km 우선(부족하면 20km), 코스 내 중복 제외, 근거 문구는 서버가 만든다.
   * 프론트는 사용자의 '피하고 싶은 장소'만 한 번 더 거른다 - 서버는 선호를 모른다.
   * 그 날짜 예보가 없으면 서버가 3401을 내고 그대로 던진다 - 빈 배열로 뭉개면 '대안이 없다'와 구분이 안 된다.
   */
  async getAlternativePlaces(course: CourseResult, itemId: number, condition?: CourseCondition): Promise<AlternativePlace[]> {
    const item = course.days.flatMap(day => day.items).find(candidate => candidate.id === itemId)
    if (!item) throw new Error('대안을 찾을 일정을 확인하지 못했습니다.')

    const avoidedIds = new Set(latestAvoidedPlaceIds)
    const avoidedNames = new Set(latestAvoidedPlaceNames)
    condition?.course_place_preferences
      .filter(preference => preference.preference_type === 'AVOID')
      .forEach(preference => {
        if (preference.place_id != null) avoidedIds.add(preference.place_id)
        avoidedNames.add(normalizePlaceName(preference.place_name))
      })
    const exclude = course.days.flatMap(day => day.items).filter(candidate => candidate.id !== itemId).map(candidate => candidate.place_id)
    const query = new URLSearchParams({ date: item.visit_date, limit: '3' })
    if (exclude.length) query.set('exclude', exclude.join(','))

    const candidates = await apiRequest(`/places/${item.place_id}/alternatives?${query.toString()}`) as AlternativePlace[]
    return candidates.filter(candidate => !avoidedIds.has(candidate.place_id) && !avoidedNames.has(normalizePlaceName(candidate.place_name)))
  },
  /**
   * 교체 실행 - 백엔드 POST /courses/{id}/items/{itemId}/swap (담당: 정동현).
   * 제자리 교체, 중복 검사, 이동 거리·평균 혼잡도 재계산, 권한(샘플 코스 차단·저장 코스 본인 확인)은 서버가 한다.
   * 응답의 updated_items(교체된 칸 + 이동이 바뀐 다음 칸)만 로컬 코스에 덮어쓴다 - 나머지 일정은 그대로.
   */
  async replaceCourseItem(course: CourseResult, itemId: number, alternative: AlternativePlace): Promise<CourseResult> {
    if (latestAvoidedPlaceIds.has(alternative.place_id) || latestAvoidedPlaceNames.has(normalizePlaceName(alternative.place_name))) throw new Error('피하고 싶은 장소는 대안으로 선택할 수 없습니다.')
    const swap = await apiRequest(`/courses/${course.id}/items/${itemId}/swap`, {
      method: 'POST',
      body: { place_id: alternative.place_id },
      // 저장 코스는 본인만(JWT). 임시(READY) 코스는 생성이 비로그인이라 공개 경로
      auth: course.status === 'SAVED',
    }) as CourseSwapResponse

    const copy = JSON.parse(JSON.stringify(course)) as CourseResult
    for (const updated of swap.updated_items) {
      const item = copy.days.flatMap(day => day.items).find(candidate => candidate.id === updated.item_id)
      if (!item) continue
      item.place_id = updated.place_id
      item.place_name = updated.place_name
      item.category_name = updated.category_name
      item.image_url = updated.image_url ?? undefined
      item.congestion_rate = updated.congestion_rate ?? undefined
      item.congestion_level = updated.congestion_level ?? undefined
      item.recommendation_reason = updated.recommendation_reason ?? undefined
      item.replaced_from_place_id = updated.replaced_from_place_id ?? undefined
      item.inbound_distance_m = updated.inbound_distance_m ?? undefined
      item.inbound_travel_minutes = updated.inbound_travel_minutes ?? undefined
      if (updated.item_id === itemId) {
        item.item_source = 'REPLACEMENT'
        item.recommendation_reason_code = 'CONGESTION'
        // 새 장소의 좌표·출처·실측 비용은 응답에 없다 - 옛 장소 값을 남기지도, 지어내지도 않는다
        delete item.latitude
        delete item.longitude
        delete item.source_code
        delete item.source_place_id
        item.costs = []
      }
    }
    // 비용 집계는 서버가 스왑에서 건드리지 않는다(실측 없는 비용을 지어내지 않음) - 로컬에서도 다시 계산하지 않는다
    if (swap.average_congestion_rate != null) copy.average_congestion_rate = swap.average_congestion_rate
    // 렌터카 경로는 옛 장소 기준 구간이라 버린다 - 화면이 교체된 장소로 다시 받는다
    delete copy.car_route
    return copy
  },
  async getQuieterTimeOptions(course: CourseResult, itemId: number) {
    await pause(250)
    return rescheduleCandidates(course, itemId)
  },
  async rescheduleCourseItem(course: CourseResult, itemId: number, option: CongestionRescheduleOption) {
    await pause(250)
    const copy = JSON.parse(JSON.stringify(course)) as CourseResult
    const sourceDay = copy.days.find(day => day.items.some(item => item.id === itemId))
    const targetDay = copy.days.find(day => day.visit_date === option.visit_date)
    const item = sourceDay?.items.find(candidate => candidate.id === itemId)
    if (!sourceDay || !targetDay || !item) throw new Error('시간을 변경할 일정을 찾지 못했습니다.')
    const conflict = targetDay.items.some(other => other.id !== itemId && other.start_time && other.end_time && overlaps(option.start_time, option.end_time, other.start_time, other.end_time))
    if (conflict) throw new Error('선택한 시간은 다른 일정과 겹칩니다.')
    if (item.congestion_rate != null && option.congestion_rate >= item.congestion_rate) throw new Error('현재보다 한산한 시간만 선택할 수 있습니다.')
    sourceDay.items = sourceDay.items.filter(candidate => candidate.id !== itemId)
    item.visit_date = option.visit_date
    item.start_time = option.start_time
    item.end_time = option.end_time
    item.congestion_rate = option.congestion_rate
    item.congestion_level = option.congestion_level
    const place = findPlace(item.place_id, item.place_name)
    item.operating_hours_warning = !isWithinOperatingHours(place, option.start_time, option.end_time) || undefined
    applyWeather(item, place, item.item_source === 'USER_FIXED')
    targetDay.items.push(item)
    refreshDayOrder(sourceDay)
    recalculateDayTravel(sourceDay, copy.transport, copy.accommodation)
    if (targetDay !== sourceDay) {
      refreshDayOrder(targetDay)
      recalculateDayTravel(targetDay, copy.transport, copy.accommodation)
    }
    return recalc(copy)
  },
  async saveCourse(course: CourseResult, title: string) {
    if (course.claim_token) {
      const saved = await apiRequest(`/courses/${course.id}/claim`, {
        method: 'POST',
        auth: true,
        body: { claim_token: course.claim_token, title },
      }) as { id: number; status: 'SAVED'; title: string; saved_at: string }
      const { claim_token: _claimToken, claim_expires_at: _claimExpiresAt, ...safeCourse } = course
      return { ...safeCourse, id: saved.id, status: saved.status, title: saved.title }
    }
    const saved = await savedCourseMockService.save(course, title)
    return saved.course
  },
}
