import { describe,it,expect,vi } from 'vitest'
import { apiRequest } from '../../api/backendClient.js'
import { expectedTransitEdges, transitTopologyMatches, useTransitRoute,transitTotal,transitSignature,minutes, type TransitRoute } from './transitRoute'
import type { CourseResult } from '../../assets/types/course'
vi.mock('../../api/backendClient.js',()=>({apiRequest:vi.fn()}))
vi.mock('../../api/notifications.js',()=>({ASYNC_COURSES_ENABLED:true}))
const course={id:15,status:'READY',transport:'PUBLIC_TRANSIT',start_date:'2026-09-08',end_date:'2026-09-08',days:[{day_no:1,visit_date:'2026-09-08',items:[{id:1,place_id:10,latitude:33.4,longitude:126.5,position:1,start_time:'10:00:00'}]}]} as CourseResult
const response={course_id:15,queried_at:'2026-09-07T12:00:00Z',cached:false,provider_attempts:1,days:[{day_no:1,visit_date:'2026-09-08',distance_meters:5013,duration_seconds:2115,legs:[{from:{id:'1',name:'출발'},to:{id:'2',name:'도착'},status:'OK',distance_meters:5013,duration_seconds:2115,transfers:0,landing_url:null,steps:[{type:'WALKING',distance_meters:800,duration_seconds:600,stops:[],vehicles:[]}]}]}]} satisfies TransitRoute
describe('course-only transit routes',()=>{
 it('sends optional member authentication for an owned READY async result',async()=>{
   vi.mocked(apiRequest).mockResolvedValueOnce(response)
   const state=useTransitRoute()
   await state.load(course)
   expect(apiRequest).toHaveBeenCalledWith('/courses/15/routes/transit',{
     method:'GET',auth:false,optionalAuth:true,
   })
 })
 it('uses provider totals, preserves real zero and incomplete totals',()=>{
   expect(transitTotal(response.days[0])).toBe('36분 · 5.0km')
   expect(transitTotal(response.days[0], [{ from_id: 'ACCOMMODATION', to_id: '1' }, { from_id: '1', to_id: '2' }])).toBe('일부 구간 정보 없음')
   expect(transitTotal(response.days[0], [{ from_id: '1', to_id: '2' }])).toBe('36분 · 5.0km')
   expect(minutes(0)).toBe('0분');expect(minutes(null)).toBe('정보 없음')
   expect(transitTotal({...response.days[0],legs:[{...response.days[0].legs[0],status:'NO_RESULTS',duration_seconds:null}]})).toBe('일부 구간 정보 없음')
 })
 it('detects a stale accommodation when the server route snapshot has tourist legs only',()=>{
   const twoItems={...structuredClone(course),days:[{...structuredClone(course.days[0]),items:[
     {...course.days[0]!.items[0],id:394,position:1},
     {...course.days[0]!.items[0],id:395,position:2},
   ]}]} as CourseResult
   const hotelCourse={...twoItems,accommodation:{source_code:'KAKAO_LOCAL',source_place_id:'hotel',place_name:'롯데호텔 제주',latitude:33.4,longitude:126.5}} as CourseResult
   const touristOnly={...response,course_id:15,days:[{...response.days[0],legs:[{
     ...response.days[0]!.legs[0],from:{id:'ITEM:394',name:'김녕요트투어'},to:{id:'ITEM:395',name:'해녀촌'},
   }]}]} as TransitRoute
   expect(expectedTransitEdges(hotelCourse,1)).toEqual([
     {from_id:'ACCOMMODATION',to_id:'ITEM:394'},
     {from_id:'ITEM:394',to_id:'ITEM:395'},
     {from_id:'ITEM:395',to_id:'ACCOMMODATION'},
   ])
   expect(transitTopologyMatches(hotelCourse,touristOnly)).toBe(false)
   expect(transitTopologyMatches(twoItems,touristOnly)).toBe(true)
 })
 it('deduplicates concurrent loads and drops late old schedule response after swap',async()=>{
   let resolve!:(r:TransitRoute)=>void
   const fetcher=vi.fn(()=>new Promise<TransitRoute>(r=>{resolve=r}));const state=useTransitRoute(fetcher)
   const a=state.load(course),b=state.load(course);expect(fetcher).toHaveBeenCalledTimes(1)
   resolve(response);await Promise.all([a,b]);expect(state.data.value).toEqual(response)
   const old=state.load(course)
   const oldResolve=resolve
   const swapped=structuredClone(course);swapped.days[0].items[0].place_id=99
   const current=state.load(swapped);expect(state.data.value).toBeUndefined()
   resolve({...response,provider_attempts:2});await current
   oldResolve(response);await old;expect(state.data.value?.provider_attempts).toBe(2)
 })
 it('accommodation invalidates, cancel discards late response, no itinerary mutation',async()=>{
   expect(transitSignature({...course,accommodation:{place_name:'숙소',latitude:33.4,longitude:126.5} as CourseResult['accommodation']})).not.toBe(transitSignature(course))
   const before=structuredClone(course);let resolve!:(r:TransitRoute)=>void
   const state=useTransitRoute(()=>new Promise(r=>{resolve=r}));const pending=state.load(course);state.cancel();resolve(response);await pending
   expect(state.data.value).toBeUndefined();expect(course).toEqual(before)
 })
 it('reloads after first accommodation and replacement, discarding the previous hotel response',async()=>{
   const pending=new Map<string,(r:TransitRoute)=>void>()
   const fetcher=vi.fn((requested:CourseResult)=>new Promise<TransitRoute>(resolve=>pending.set(transitSignature(requested),resolve)))
   const state=useTransitRoute(fetcher)
   const withoutHotel=structuredClone(course)
   const firstHotel={...structuredClone(course),accommodation:{source_code:'KAKAO_LOCAL',source_place_id:'hotel-a',place_name:'숙소 A',latitude:33.41,longitude:126.51}}
   const replacement={...structuredClone(course),accommodation:{source_code:'KAKAO_LOCAL',source_place_id:'hotel-b',place_name:'숙소 B',latitude:33.42,longitude:126.52}}

   const initial=state.load(withoutHotel)
   pending.get(transitSignature(withoutHotel))!(response)
   await initial
   expect(state.data.value).toEqual(response)

   const first=state.load(firstHotel as CourseResult)
   expect(fetcher).toHaveBeenCalledTimes(2)
   expect(state.loading.value).toBe(true)
   expect(state.data.value).toBeUndefined()

   const changed=state.load(replacement as CourseResult)
   expect(fetcher).toHaveBeenCalledTimes(3)
   pending.get(transitSignature(firstHotel as CourseResult))!({...response,provider_attempts:10})
   await first
   expect(state.data.value).toBeUndefined()

   pending.get(transitSignature(replacement as CourseResult))!({...response,provider_attempts:20})
   await changed
   expect(state.data.value?.provider_attempts).toBe(20)
   expect(state.loading.value).toBe(false)
 })
 it('failure preserves course and non-transit never calls API',async()=>{
   const fetcher=vi.fn().mockRejectedValue(new Error('offline'));const state=useTransitRoute(fetcher);const before=structuredClone(course)
   await state.load(course);expect(state.error.value).toContain('일정은 유지');expect(course).toEqual(before)
   await state.load({...course,transport:'RENTAL_CAR'});expect(fetcher).toHaveBeenCalledTimes(1)
 })
})
