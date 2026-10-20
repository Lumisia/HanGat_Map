import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { createRenderer, nextTick, reactive, ssrContextKey } from 'vue'
import { readFileSync } from 'node:fs'
import View from './AiCourseView.vue'
import { RESTORE_KEY, rememberResult } from '../../services/course/resultRestore'
import { storePendingCourseClaim } from '../../services/pendingCourseClaim'
import { tabDestination } from '../../config/navTabs.js'
import { apiRequest } from '../../api/backendClient.js'
import { getGenerationResult, submitGenerationJob } from '../../api/courseGeneration.js'
import { courseMockService } from '../../services/courseMockService'

const deps = vi.hoisted(() => ({ auth: null as any, ui: null as any, route: null as any, router: { replace: vi.fn(), push: vi.fn() }, transitLoad: vi.fn() }))
vi.mock('../../stores/ui.js', () => ({ useUiStore: () => deps.ui }))
vi.mock('vue-router', () => ({ useRoute: () => deps.route, useRouter: () => deps.router }))
vi.mock('../../app/stores/auth', () => ({ useAuthStore: () => deps.auth }))
vi.mock('../../stores/auth.js', () => ({ useAuthStore: () => deps.auth }))
vi.mock('../../api/notifications.js', () => ({ ASYNC_COURSES_ENABLED: true }))
vi.mock('../../api/backendClient.js', () => ({ apiRequest: vi.fn(), getBackendUserId: () => deps.auth?.user?.userId ?? null }))
vi.mock('../../api/courseGeneration.js', () => ({ getGenerationResult: vi.fn(), submitGenerationJob: vi.fn() }))
vi.mock('../../services/courseMockService', async importOriginal => {
  const actual = await importOriginal<any>()
  return { ...actual, courseMockService: { getCarRoute: vi.fn(), generateCourse: vi.fn(), regenerateCourse: vi.fn(), saveCourse: vi.fn(), getRecommendedAccommodations: vi.fn().mockResolvedValue([]) } }
})
vi.mock('../../services/course/transitRoute', async () => {
  const { ref } = await import('vue')
  return { expectedTransitEdges: () => [], transitTopologyMatches: () => true,
    useTransitRoute: () => ({ data: ref(), loading: ref(false), error: ref(''), cancel: vi.fn(), load: deps.transitLoad }) }
})
// Exercise the real view setup/watch/onMounted without mounting provider SDK children.
View.render = () => null
const renderer = createRenderer<any, any>({ createElement: () => ({}), createText: () => ({}), createComment: () => ({}),
  setText() {}, setElementText() {}, patchProp() {}, parentNode: () => null, nextSibling: () => null, insert() {}, remove() {} })
const inputs: any = { start_date: '2026-09-20', end_date: '2026-09-22', people: 2, transport: 'PUBLIC_TRANSIT', course_regions: [], course_styles: [], course_place_preferences: [] }
const course: any = { id: 80, status: 'READY', course_type: 'USER', ...inputs, days: [], accommodation: null }
let app: any
const flush = async () => { for (let i = 0; i < 12; i++) await nextTick() }
function mount() { app = renderer.createApp(View); app.provide(ssrContextKey, {}); app.mount({}); return app._instance.setupState }
beforeEach(() => {
  vi.clearAllMocks()
  deps.auth = reactive({ isAuthenticated: false, isLoggedIn: false, user: null })
  deps.ui = reactive({ aiCourseEntryVersion: 0 })
  deps.route = reactive({ path: '/ai-course', fullPath: '/ai-course', query: {} })
  deps.router.replace.mockImplementation(async (to: any) => { if (to.query) deps.route.query = to.query })
  const values = new Map<string, string>()
  vi.stubGlobal('sessionStorage', { getItem: (k: string) => values.get(k) ?? null, setItem: (k: string, v: string) => values.set(k, v), removeItem: (k: string) => values.delete(k) })
  vi.stubGlobal('requestAnimationFrame', (callback: any) => callback())
  vi.stubGlobal('window', { scrollTo: vi.fn() })
  vi.mocked(apiRequest).mockResolvedValue(course)
  deps.transitLoad.mockResolvedValue(undefined)
})
afterEach(() => { app?.unmount(); vi.unstubAllGlobals(); vi.useRealTimers() })
describe('AI entry, restoration and budgetless generation', () => {
  it('team menu event resets a currently mounted form without deleting a course or job', async () => {
    const view = mount(); await flush()
    view.condition.people = 6
    view.condition.accommodation = { source_place_id: 'old-hotel' }
    deps.ui.aiCourseEntryVersion++; await flush()
    expect(view.condition.people).toBe(2)
    expect(view.condition.accommodation).toBeUndefined()
    expect(apiRequest).not.toHaveBeenCalled()
    expect(submitGenerationJob).not.toHaveBeenCalled()
  })
  it.each([false, true])('fresh entry resets all inputs and accommodation at current KST day for member=%s', async member => {
    vi.useFakeTimers({ toFake: ['Date'] })
    vi.setSystemTime(new Date('2026-09-20T14:59:59Z'))
    deps.auth.isAuthenticated = member; deps.auth.isLoggedIn = member
    deps.auth.user = member ? { userId: 6 } : null
    const old = { ...inputs, people: 5, accommodation: { source_code: 'KAKAO_LOCAL', source_place_id: 'test-hotel', place_name: '이전 숙소', latitude: 33.4, longitude: 126.5 }, course_styles: [{ code: 'CAFE' }], course_regions: [{ code: 'EAST' }] }
    rememberResult({ ...course, accommodation: old.accommodation }, old)
    deps.route.query = { entry: 'new' }
    const view = mount(); await flush()
    expect(view.condition).toEqual({ start_date: '2026-09-20', end_date: '2026-09-22', people: 2, transport: 'RENTAL_CAR', course_regions: [], course_styles: [], course_place_preferences: [] })
    Object.assign(view.condition, old)
    const revision = view.formRevision
    vi.setSystemTime(new Date('2026-09-20T15:00:00Z'))
    deps.route.query = { entry: 'new' }; await flush()
    expect(view.condition.start_date).toBe('2026-09-21')
    expect(view.condition.end_date).toBe('2026-09-23')
    expect(view.condition.accommodation).toBeUndefined()
    expect(view.condition.people).toBe(2)
    expect(view.condition.course_styles).toEqual([])
    expect(view.formRevision).toBeGreaterThan(revision)
    expect(JSON.parse(sessionStorage.getItem(RESTORE_KEY)!).condition).toEqual(view.condition)
    expect(apiRequest).not.toHaveBeenCalled()
    expect(courseMockService.generateCourse).not.toHaveBeenCalled()
    expect(submitGenerationJob).not.toHaveBeenCalled()
  })
  it('condition edit preserves the course inputs and selected accommodation without a fresh-form reset', async () => {
    const hotel = { source_code: 'KAKAO_LOCAL', source_place_id: 'test-hotel', place_name: '저장 숙소', latitude: 33.4, longitude: 126.5 }
    vi.mocked(apiRequest).mockResolvedValue({ ...course, accommodation: hotel })
    rememberResult(course, inputs)
    const view = mount(); await flush()
    const before = JSON.parse(JSON.stringify(view.condition)); const revision = view.formRevision
    view.editConditions(); await flush()
    expect(view.condition).toEqual(before)
    expect(view.condition.accommodation.source_place_id).toBe('test-hotel')
    expect(view.formRevision).toBe(revision)
    expect(view.editing).toBe(true)
  })
  it('member input opens existing history dialog; guest input does not', async () => {
    const view = mount(); await flush()
    const dialog = { showModal: vi.fn(), close: vi.fn() }
    view.historyDialog = dialog
    view.openHistory(); expect(view.historyOpen).toBe(false)
    deps.auth.isLoggedIn = true; deps.auth.isAuthenticated = true; deps.auth.user = { userId: 6 }
    await flush(); view.openHistory()
    expect(view.historyOpen).toBe(true); expect(dialog.showModal).toHaveBeenCalledOnce()
    view.closeHistory(); expect(view.historyOpen).toBe(false)
    expect(submitGenerationJob).not.toHaveBeenCalled()
  })
  it('removes budget display from input/result/saved course templates without changing place fees', () => {
    for (const url of [new URL('./AiCourseView.vue', import.meta.url), new URL('../course/SavedCoursesView.vue', import.meta.url), new URL('../../components/course/CourseConditionForm.vue', import.meta.url)]) {
      const source = readFileSync(url, 'utf8')
      expect(source).not.toMatch(/BudgetGauge|전체 예산|예상 비용|예상 경비/)
    }
  })
  it.each([false, true])('menu entry always shows form for member=%s without course calls', async member => {
    deps.auth.isAuthenticated = member; deps.auth.user = member ? { userId: 6 } : null
    rememberResult(course, inputs)
    deps.route.query = (tabDestination({ to: '/ai-course' }) as any).query
    const view = mount(); await flush()
    expect(view.editing).toBe(true); expect(view.result).toBeUndefined()
    expect(apiRequest).not.toHaveBeenCalled(); expect(submitGenerationJob).not.toHaveBeenCalled()
    expect(JSON.parse(sessionStorage.getItem(RESTORE_KEY)!)).toMatchObject({ mode: 'editing' })
  })
  it.each([false, true])('refresh and explicit course link use existing GET for member=%s', async member => {
    deps.auth.isAuthenticated = member; deps.auth.user = member ? { userId: 6 } : null
    rememberResult(course, inputs)
    const view = mount(); await flush()
    expect(view.result.id).toBe(80); expect(view.editing).toBe(false)
    expect(apiRequest).toHaveBeenCalledWith('/courses/80', { method: 'GET', auth: member })
    vi.mocked(apiRequest).mockResolvedValue({ ...course, id: 81 })
    deps.route.query = { course: '81' }; await flush()
    expect(view.result.id).toBe(81)
    expect(courseMockService.generateCourse).not.toHaveBeenCalled()
  })
  it('menu transition discards a late detail response', async () => {
    let resolve: any
    vi.mocked(apiRequest).mockReturnValue(new Promise(r => { resolve = r }))
    rememberResult(course, inputs)
    const view = mount(); await nextTick()
    deps.route.query = { entry: 'new' }; await flush()
    resolve(course); await flush()
    expect(view.editing).toBe(true); expect(view.result).toBeUndefined(); expect(deps.transitLoad).not.toHaveBeenCalled()
  })
  it('clears prior user results and claim state on account transition', async () => {
    deps.auth.isAuthenticated = true; deps.auth.user = { userId: 6 }
    rememberResult(course, inputs)
    const view = mount(); await flush(); expect(view.result.id).toBe(80)
    deps.auth.user = { userId: 7 }; await flush()
    expect(view.result).toBeUndefined(); expect(view.editing).toBe(true)
    expect(JSON.parse(sessionStorage.getItem(RESTORE_KEY)!)).toMatchObject({ ownerId: 7, mode: 'editing' })
  })
  it('login-to-save return still uses real save contract and removes budget from inputs', async () => {
    deps.auth.isAuthenticated = true; deps.auth.user = { userId: 6 }
    const proof = { ...course, claim_token: 'test-only-proof', claim_expires_at: '2099-01-01T00:00:00Z' }
    storePendingCourseClaim(proof, inputs, '저장 제목')
    vi.mocked(courseMockService.saveCourse).mockResolvedValue({ ...course, status: 'SAVED' })
    const view = mount(); await flush()
    expect(courseMockService.saveCourse).toHaveBeenCalledWith(proof, '저장 제목')
    expect(view.result.status).toBe('SAVED'); expect(view.condition).not.toHaveProperty('budget_total')
  })
  it('explicit job result uses authenticated result and detail then starts routes', async () => {
    deps.auth.isAuthenticated = true; deps.auth.user = { userId: 6 }
    deps.route.query = { job: '11111111-1111-1111-1111-111111111111' }
    vi.mocked(getGenerationResult).mockResolvedValue({ course, request: inputs })
    const view = mount(); await flush()
    expect(getGenerationResult).toHaveBeenCalledOnce(); expect(view.result.id).toBe(80)
    expect(deps.transitLoad).toHaveBeenCalledOnce(); expect(submitGenerationJob).not.toHaveBeenCalled()
  })
  it('submits member job without fake budget and prevents duplicate pending submission', async () => {
    deps.auth.isAuthenticated = true; deps.auth.user = { userId: 6 }
    let resolve: any
    vi.mocked(submitGenerationJob).mockReturnValue(new Promise(r => { resolve = r }))
    const view = mount(); await flush()
    const first = view.generate({ ...inputs, budget_total: 400000 }); await view.generate(inputs)
    expect(submitGenerationJob).toHaveBeenCalledOnce()
    expect(vi.mocked(submitGenerationJob).mock.calls[0][0]).not.toHaveProperty('budget_total')
    deps.route.query = { entry: 'new' }; await flush()
    resolve({ jobId: 'accepted-job' }); await first
    expect(deps.router.push).not.toHaveBeenCalled(); expect(view.editing).toBe(true)
  })
  it('guest generation remains synchronous without budget and retains restored dates', async () => {
    vi.mocked(courseMockService.generateCourse).mockResolvedValue(course)
    const view = mount(); await flush(); await view.generate(inputs); await flush()
    expect(courseMockService.generateCourse).toHaveBeenCalledOnce()
    expect(vi.mocked(courseMockService.generateCourse).mock.calls[0][0]).not.toHaveProperty('budget_total')
    expect(view.result.id).toBe(80); expect(view.result.start_date).toBe(inputs.start_date)
    expect(submitGenerationJob).not.toHaveBeenCalled()
  })
  it('a failed or not-ready job does not request routes or regenerate', async () => {
    deps.auth.isAuthenticated = true; deps.auth.user = { userId: 6 }
    deps.route.query = { job: '11111111-1111-1111-1111-111111111111' }
    vi.mocked(getGenerationResult).mockRejectedValue(Error('not-ready'))
    const view = mount(); await flush()
    expect(view.jobResultError).not.toBe(''); expect(deps.transitLoad).not.toHaveBeenCalled()
    expect(submitGenerationJob).not.toHaveBeenCalled(); expect(courseMockService.generateCourse).not.toHaveBeenCalled()
  })
})
