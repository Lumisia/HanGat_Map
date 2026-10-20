import { apiRequest } from '../api/backendClient'
import type { CongestionLevel } from '../assets/types'
import type { CourseItem } from '../assets/types/course'

export interface ShareStatus { active: boolean; token: string | null }
export interface SharedItem {
  id: number; place_id: number; place_name: string; category_name: string | null
  region_name: string | null; place_business_status: string | null; image_url: string | null
  latitude: number | null; longitude: number | null; position: number
  start_time: string | null; end_time: string | null; inbound_distance_m: number | null
  inbound_travel_minutes: number | null; congestion_rate: number | null
  congestion_level: CongestionLevel | null; congestion_label: string | null
  weather: CourseItem['weather']
}
export interface SharedCourse {
  title: string | null; start_date: string; end_date: string; transport: string | null
  days: { day_no: number; visit_date: string; items: SharedItem[] }[]
}
function status(value: ShareStatus): ShareStatus {
  if (!value || typeof value.active !== 'boolean' || (value.active ? typeof value.token !== 'string' || !value.token : value.token !== null)) throw new Error('공유 상태 응답을 확인하지 못했어요.')
  return { active: value.active, token: value.token }
}
async function owner(id: string, method: string) {
  return status(await apiRequest(`/courses/${encodeURIComponent(id)}/share`, { method, auth: true }))
}
export default {
  status: (id: string) => owner(id, 'GET'),
  create: (id: string) => owner(id, 'POST'),
  revoke: (id: string) => owner(id, 'DELETE'),
  async getPublic(token: string): Promise<SharedCourse> {
    const value = await apiRequest(`/shared-courses/${encodeURIComponent(token)}`, { auth: false })
    if (!value || typeof value.start_date !== 'string' || typeof value.end_date !== 'string' || !Array.isArray(value.days)
      || !value.days.every((day: SharedCourse['days'][number]) => Number.isInteger(day.day_no) && typeof day.visit_date === 'string' && Array.isArray(day.items)
        && day.items.every(item => Number.isFinite(item.id) && Number.isFinite(item.place_id) && typeof item.place_name === 'string' && Number.isFinite(item.position)
          && (item.weather == null || Array.isArray(item.weather))))) throw new Error('코스 응답 형식을 확인하지 못했어요.')
    return value
  },
}
