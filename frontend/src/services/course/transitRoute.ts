import { ref } from 'vue'
import { apiRequest } from '../../api/backendClient.js'
import { ASYNC_COURSES_ENABLED } from '../../api/notifications.js'
import type { CourseResult } from '../../assets/types/course'
export interface TransitStep { type: string; distance_meters: number | null; duration_seconds: number | null; stops: string[]; vehicles: string[] }
export interface TransitLeg { from: { id: string; name: string }; to: { id: string; name: string }; status: string;
  distance_meters: number | null; duration_seconds: number | null; transfers: number | null; landing_url: string | null; steps: TransitStep[] }
export interface TransitDay { day_no: number; visit_date: string; distance_meters: number | null; duration_seconds: number | null; legs: TransitLeg[] }
export interface TransitRoute { course_id: number; queried_at: string; cached: boolean; provider_attempts: number; days: TransitDay[] }
export interface TransitEdge { from_id: string; to_id: string }
export const minutes = (n: number | null | undefined) => n == null ? '정보 없음' : `${Math.ceil(n / 60)}분`
export const metres = (n: number | null | undefined) => n == null ? '정보 없음' : `${(n / 1000).toFixed(1)}km`
export const travelTime = (n: number | null | undefined) => {
  if (n == null) return '정보 없음'
  const m = Math.ceil(n / 60)
  return m < 60 ? `${m}분` : `${Math.floor(m / 60)}시간${m % 60 ? ` ${m % 60}분` : ''}`
}
export function walkingTime(leg: TransitLeg) {
  const steps = leg.steps.filter(s => s.type === 'WALKING')
  return steps.length && steps.every(s => s.duration_seconds != null) ? steps.reduce((sum,s) => sum + s.duration_seconds!,0) : null
}
export function transitState(day?: TransitDay, loading = false, error = '') {
  if (loading) return 'LOADING'
  if (error) return 'FAILED'
  if (!day || !day.legs.length) return 'UNQUERIED'
  if (day.legs.every(l => l.status === 'NOT_ENABLED')) return 'DISABLED'
  if (day.legs.every(l => l.status !== 'OK')) return 'FAILED'
  return day.legs.every(l => l.status === 'OK') ? 'COMPLETE' : 'PARTIAL'
}
export function transitLegsComplete(day: TransitDay, expectedEdges?: TransitEdge[]) {
  if (!day.legs.length || !day.legs.every(l => l.status === 'OK')) return false
  if (!expectedEdges) return true
  return day.legs.length === expectedEdges.length && day.legs.every((leg, index) =>
    leg.from.id === expectedEdges[index]?.from_id && leg.to.id === expectedEdges[index]?.to_id)
}
export const transitTotal = (day: TransitDay, expectedEdges?: TransitEdge[]) => transitLegsComplete(day, expectedEdges)
  && day.duration_seconds != null && day.distance_meters != null
  ? `${minutes(day.duration_seconds)} · ${metres(day.distance_meters)}` : '일부 구간 정보 없음'
export const transitLegMessage = (status: string | undefined, loading: boolean) => loading
  ? '경로를 조회하고 있어요'
  : status === 'NO_RESULTS' ? '이 구간의 대중교통 경로를 찾지 못했어요' : '경로를 불러오지 못했어요'
export function expectedTransitEdges(course: CourseResult, dayNo: number): TransitEdge[] {
  const day = course.days.find(candidate => candidate.day_no === dayNo)
  if (!day) return []
  const stops = day.items.map(item => `ITEM:${item.id}`)
  if (course.accommodation && stops.length) stops.unshift('ACCOMMODATION')
  const edges = stops.slice(0, -1).map((fromId, index) => ({ from_id: fromId, to_id: stops[index + 1]! }))
  if (course.accommodation && stops.length > 1) edges.push({ from_id: stops.at(-1)!, to_id: 'ACCOMMODATION' })
  return edges
}
export function transitTopologyMatches(course: CourseResult, route: TransitRoute) {
  if (route.course_id !== course.id || route.days.length !== course.days.length) return false
  return course.days.every(day => {
    const actual = route.days.find(candidate => candidate.day_no === day.day_no)
    const expected = expectedTransitEdges(course, day.day_no)
    return !!actual && actual.legs.length === expected.length && actual.legs.every((leg, index) =>
      leg.from.id === expected[index]?.from_id && leg.to.id === expected[index]?.to_id)
  })
}
export function transitSignature(c: CourseResult) {
  return JSON.stringify([c.id,c.start_date,c.end_date,c.transport,c.accommodation,c.days.map(d => [d.day_no,d.visit_date,
    d.items.map(i => [i.id,i.place_id,i.latitude,i.longitude,i.position,i.start_time,i.end_time])])])
}
export function useTransitRoute(fetcher = async (c: CourseResult): Promise<TransitRoute> => await apiRequest(`/courses/${c.id}/routes/transit`, {
  method: 'GET', auth: c.status === 'SAVED',
  ...(ASYNC_COURSES_ENABLED ? { optionalAuth: true } : {}),
}) as TransitRoute) {
  const data=ref<TransitRoute>(), loading=ref(false), error=ref('')
  let epoch=0
  const pending=new Map<string,Promise<TransitRoute>>()
  function cancel() { epoch++; data.value=undefined; loading.value=false; error.value='' }
  async function load(course: CourseResult) {
    const ticket=++epoch; data.value=undefined;error.value=''
    if(course.transport!=='PUBLIC_TRANSIT') {loading.value=false;return}
    loading.value=true
    const key=transitSignature(course)
    let request=pending.get(key)
    if(!request) { request=fetcher(course);pending.set(key,request);request.then(()=>pending.delete(key),()=>pending.delete(key)) }
    try {
      const response=await request
      if(ticket===epoch && response.course_id===course.id) { data.value=response; return response }
      return undefined
    }
    catch {if(ticket===epoch)error.value='대중교통 경로를 불러오지 못했어요. 일정은 유지됩니다.'}
    finally {if(ticket===epoch)loading.value=false}
  }
  return { data,loading,error,load,cancel }
}
