export type Transport = 'RENTAL_CAR' | 'PUBLIC_TRANSIT' | 'TAXI' | 'WALK_BIKE'
export type CourseStatus = 'GENERATING' | 'READY' | 'SAVED' | 'FAILED' | 'EXPIRED' | 'DELETED'
export type CourseType = 'USER' | 'SAMPLE'
export type GenerationReason = 'INITIAL' | 'USER_REGENERATE' | 'WEATHER_REPLAN' | 'SAMPLE_BATCH'
export type PreferenceType = 'WANT' | 'AVOID'
export type ItemSource = 'USER_FIXED' | 'AI_RECOMMENDED' | 'REPLACEMENT'
export type CongestionLevel = 'QUIET' | 'NORMAL' | 'CROWDED'
export type CostCategory = 'FOOD' | 'LODGING' | 'TRANSPORT' | 'ACTIVITY' | 'OTHER'
export type AccuracyType = 'VERIFIED' | 'ESTIMATED' | 'UNKNOWN'
export type RecommendationReasonCode = 'CONGESTION' | 'STYLE' | 'GOOD_PRICE' | 'HIDDEN_GEM' | 'ROUTE'
export type WeatherCondition = 'SUNNY' | 'CLOUDY' | 'RAIN' | 'SNOW' | 'STRONG_WIND'
export type IndoorOutdoor = 'INDOOR' | 'OUTDOOR' | 'MIXED'
export type PlaceSourceCode = 'KTO' | 'KAKAO_LOCAL'

export interface RegionRef { region_id: number; code: 'EAST'|'WEST'|'SOUTH'|'NORTH'; name: string }
export interface CourseStyle { tag_id: number; code: string; name: string; weight: number }
export interface PlacePreference {
  place_id?: number
  source_code?: 'KAKAO_LOCAL'
  source_place_id?: string
  place_name: string
  address?: string
  road_address?: string
  latitude?: number
  longitude?: number
  category_name?: string
  preference_type: PreferenceType
  fixed_date?: string
  fixed_time?: string
}
export interface KakaoPlaceSearchResult {
  source_code: 'KAKAO_LOCAL'; source_place_id: string; place_name: string
  address?: string; road_address?: string; latitude: number; longitude: number
  phone?: string; place_url?: string; category_name?: string
}
export interface AccommodationInput extends KakaoPlaceSearchResult { region?: RegionRef['code']; image_url?: string }
export interface AccommodationRecommendation extends AccommodationInput { recommendation_reason: string }
export interface CourseCondition {
  start_date: string; end_date: string; people: number; budget_total: number; transport: Transport
  course_regions: RegionRef[]; course_styles: CourseStyle[]; course_place_preferences: PlacePreference[]; accommodation?: AccommodationInput
}
export interface CourseItemCost {
  id: number; course_id: number; course_item_id?: number; category: CostCategory; accuracy_type: AccuracyType
  amount_min?: number; amount_max?: number; currency: 'KRW'; basis_text?: string
}
export interface CourseGap { start_time: string; end_time: string; minutes: number; type: 'FREE_TIME' }
export interface CourseCostSummary {
  verified_amount: number
  estimated_min: number
  estimated_max: number
  unknown_count: number
}
export interface CourseBudgetSummary {
  has_cost_data: boolean
  budget_total?: number
  verified_total: number
  estimated_total?: number
  estimated_min?: number
  estimated_max?: number
  total_expected?: number
  total_expected_min?: number
  total_expected_max?: number
  remaining_budget?: number
  usage_rate?: number
  over_budget?: boolean
  unknown_count: number
}
export interface CourseItem {
  id: number; course_id: number; place_id: number; place_name: string; category_name: string; image_url?: string
  candidate_id?: string; source_code?: PlaceSourceCode; source_place_id?: string
  address?: string; road_address?: string; latitude?: number; longitude?: number
  day_no: number; position: number; visit_date: string; start_time?: string; end_time?: string
  item_source: ItemSource; inbound_distance_m?: number; inbound_travel_minutes?: number
  congestion_rate?: number; congestion_level?: CongestionLevel; recommendation_reason_code?: RecommendationReasonCode
  recommendation_reason?: string; replaced_from_place_id?: number; gap_before?: CourseGap
  weather_condition?: WeatherCondition; temperature?: number; precipitation_probability?: number
  weather_warning?: string; weather_influenced?: boolean
  operating_hours_warning?: boolean; accommodation_influenced?: boolean; costs: CourseItemCost[]
}
export interface CourseDay {
  day_no: number; visit_date: string; items: CourseItem[]
  accommodation_departure_distance_m?: number; accommodation_departure_travel_minutes?: number
  accommodation_return_distance_m?: number; accommodation_return_travel_minutes?: number
}
export interface CarRouteCoordinate { latitude: number; longitude: number }
export interface CarRouteAccessPoint {
  original_place_id: number; original_place_name: string; type: 'PLACE_ENTRANCE' | 'PARKING'
  name: string; latitude: number; longitude: number; straight_distance_meters: number
  source_code: 'KAKAO_LOCAL'; source_place_id: string; notice: string
}
export interface CarRouteStop { type: 'COURSE_ITEM' | 'ACCOMMODATION'; id: string; name: string; access_point?: CarRouteAccessPoint | null }
export interface CarRouteLeg {
  from: CarRouteStop; to: CarRouteStop; distance_meters: number | null; duration_seconds: number | null
  polyline: CarRouteCoordinate[]
}
export interface CarDayRoute {
  day_no: number; visit_date: string; total_distance_meters?: number | null; total_duration_seconds?: number | null
  legs: CarRouteLeg[]; polyline: CarRouteCoordinate[]
}
export interface CarRouteResult {
  course_id: number; transport: 'RENTAL_CAR'; provider: 'KAKAO_MOBILITY'; priority: 'RECOMMEND'
  cached: boolean; fetched_at: string; days: CarDayRoute[]
}
export interface CourseResult {
  id: number; course_type: CourseType; generation_reason: GenerationReason; status: CourseStatus; title?: string
  claim_token?: string; claim_expires_at?: string
  start_date: string; end_date: string; people: number; budget_total?: number; transport: Transport
  estimated_cost_min?: number; estimated_cost_max?: number; average_congestion_rate?: number
  cost_summary?: CourseCostSummary; budget_summary?: CourseBudgetSummary
  generation_error_code?: string; accommodation?: AccommodationInput | null; days: CourseDay[]
  car_route?: CarRouteResult
}
export interface AlternativePlace {
  place_id: number; place_name: string; category_name: string; subcategory_name?: string; image_url?: string
  distance_m: number; congestion_rate?: number; congestion_level?: CongestionLevel
  recommendation_reason: string; replacement_reason: string; radius_km?: 10|20
}
export interface CongestionRescheduleOption {
  visit_date: string
  start_time: string
  end_time: string
  congestion_rate: number
  congestion_level: CongestionLevel
  weather_condition: WeatherCondition
  temperature: number
  precipitation_probability: number
}
export interface SavedCourseSummary {
  course_id: number
  title: string
  start_date: string
  end_date: string
  representative_places: string[]
  average_congestion_rate?: number
  estimated_cost_min?: number
  estimated_cost_max?: number
}
export interface SavedCourseRecord { summary: SavedCourseSummary; course: CourseResult }
