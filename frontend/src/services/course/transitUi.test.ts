import { describe,it,expect } from 'vitest'
import { readFileSync } from 'node:fs'
import { createSSRApp } from 'vue'
import { renderToString } from 'vue/server-renderer'
import Card from '../../components/course/TransitLegCard.vue'
import Summary from '../../components/course/TransitDayRoute.vue'
import { travelTime,walkingTime,transitState,transitLegMessage,type TransitLeg } from './transitRoute'
const leg:TransitLeg={from:{id:'1',name:'출발'},to:{id:'2',name:'도착'},status:'OK',distance_meters:27983,duration_seconds:5074,transfers:1,landing_url:null,steps:[
 {type:'BUS',distance_meters:8616,duration_seconds:875,vehicles:['211','212'],stops:['풍력생태길입구','성산농협']},
 {type:'WALKING',distance_meters:215,duration_seconds:192,vehicles:[],stops:['성산농협','고성리 성산농협']},
 {type:'BUS',distance_meters:17185,duration_seconds:2246,vehicles:['722-2'],stops:['고성리 성산농협','일출랜드입구']},
]}
describe('transit cards',()=>{
 it('keeps async job lifecycle and transit cancellation integrated',()=>{
  const view=readFileSync(new URL('../../views/ai-course/AiCourseView.vue',import.meta.url),'utf8')
  const restore=view.slice(view.indexOf('async function restoreJobResult'),view.indexOf('onMounted(async'))
  expect(restore).toContain('getGenerationResult(id)')
  expect(restore).toContain('restoration.restore')
  expect(restore.indexOf('void loadCarRoute()')).toBeGreaterThan(restore.indexOf('result.value = current'))
  expect(restore).toContain('transit.cancel()')
  expect(restore).toContain('watch(() => route.query.job')
  expect(restore).toContain('watch(() => (auth.user')
 })
 it('reloads transit only after PATCH and authoritative detail confirmation',()=>{
  const view=readFileSync(new URL('../../views/ai-course/AiCourseView.vue',import.meta.url),'utf8')
  const handler=view.slice(view.indexOf('async function selectRecommendedAccommodation'),view.indexOf('async function chooseAccommodation'))
  const confirmed=handler.indexOf('await accommodationSelection.save')
  const apply=handler.indexOf('result.value = outcome.course')
  const reload=handler.indexOf('void loadCarRoute()')
  expect(confirmed).toBeGreaterThan(-1)
  expect(apply).toBeGreaterThan(confirmed)
  expect(reload).toBeGreaterThan(apply)
  expect(handler.indexOf('if (!outcome')).toBeGreaterThan(confirmed)
 })
 it('reconciles a stale screen accommodation from the authoritative detail when transit topology differs',()=>{
  const view=readFileSync(new URL('../../views/ai-course/AiCourseView.vue',import.meta.url),'utf8')
  const loader=view.slice(view.indexOf('async function loadCarRoute'),view.indexOf('function transitLeg'))
  expect(loader).toContain('transitTopologyMatches(course, response)')
  expect(loader.indexOf('await fetchRestoredCourse')).toBeGreaterThan(loader.indexOf('transitTopologyMatches(course, response)'))
  expect(loader.indexOf('result.value = confirmed')).toBeGreaterThan(loader.indexOf('await fetchRestoredCourse'))
  expect(loader).toContain('syncConfirmedCourseCondition(condition, confirmed)')
 })
 it('keeps provider total and only sums supplied walking steps',()=>{
  expect(travelTime(leg.duration_seconds)).toBe('1시간 25분');expect(walkingTime(leg)).toBe(192)
  expect(walkingTime({...leg,steps:[]})).toBeNull()
  expect(walkingTime({...leg,steps:[{...leg.steps[1]!,duration_seconds:null}]})).toBeNull()
  expect(travelTime(0)).toBe('0분');expect(travelTime(null)).toBe('정보 없음')
 })
 it('renders accessible native disclosure with ordered boarding, walking and alighting',async()=>{
  const html=await renderToString(createSSRApp(Card,{leg,from:'출발',to:'도착',loading:false}))
  expect(html).toContain('<details');expect(html).toMatch(/<summary(?:\s[^>]*)?>/);expect(html).toContain('1시간 25분')
  expect(html).toContain('승차 풍력생태길입구 → 하차 성산농협')
  expect(html.indexOf('승차 풍력생태길입구')).toBeLessThan(html.indexOf('승차 고성리 성산농협'))
  expect(html).toContain('722-2');expect(html).not.toContain('요금');expect(html).not.toContain('도착 예정')
 })
 it('keeps internal states separate and hides error codes',async()=>{
  const day={day_no:1,visit_date:'2026-09-07',legs:[{...leg,status:'REQUEST_LIMIT'}],distance_meters:null,duration_seconds:null}
  expect(transitState(undefined,true)).toBe('LOADING');expect(transitState()).toBe('UNQUERIED')
  expect(transitState({...day,legs:[{...leg,status:'NOT_ENABLED'}]})).toBe('DISABLED')
  expect(transitState(day)).toBe('FAILED');expect(transitState({...day,legs:[leg,...day.legs]})).toBe('PARTIAL')
  const html=await renderToString(createSSRApp(Summary,{day,loading:false,error:''}))
  expect(html).toContain('대중교통 경로 정보를 불러오지 못했어요');expect(html).not.toContain('REQUEST_LIMIT')
  expect(html).not.toContain('<details')
 })
 it('distinguishes no route, transport failure and loading without hiding successful legs',async()=>{
  expect(transitLegMessage('NO_RESULTS',false)).toBe('이 구간의 대중교통 경로를 찾지 못했어요')
  expect(transitLegMessage('NETWORK_TIMEOUT',false)).toBe('경로를 불러오지 못했어요')
  expect(transitLegMessage(undefined,true)).toBe('경로를 조회하고 있어요')
  const noRoute=await renderToString(createSSRApp(Card,{leg:{...leg,status:'NO_RESULTS'},from:'숙소',to:'첫 장소',loading:false}))
  const failed=await renderToString(createSSRApp(Card,{leg:{...leg,status:'NETWORK_TIMEOUT'},from:'첫 장소',to:'다음 장소',loading:false}))
  expect(noRoute).toContain('이 구간의 대중교통 경로를 찾지 못했어요')
  expect(failed).toContain('경로를 불러오지 못했어요')
  expect(noRoute).not.toContain('NETWORK_TIMEOUT');expect(failed).not.toContain('NETWORK_TIMEOUT')
 })
})
