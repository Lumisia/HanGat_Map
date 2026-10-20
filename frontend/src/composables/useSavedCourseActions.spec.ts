import { describe, it, expect, vi } from 'vitest'
import { ref } from 'vue'
import { useSavedCourseActions } from './useSavedCourseActions'
import type { CourseDetail, StraightAlternatives } from '../services/CourseService'

const detail = () => ({ id: '1', title: '원래 이름', manageable: true, swappable: true,
  days: [{ dayNo: 2, visitDate: '2020-09-10', items: [{ id: 11, placeId: 101 }, { id: 12, placeId: 102 }] }] }) as CourseDetail
const response = (ids: number[] = []): StraightAlternatives => ({ forecast_date: '2026-09-18', distance_basis: 'STRAIGHT_LINE',
  places: ids.map(place_id => ({ place_id, place_name: `장소 ${place_id}`, category_name: '관광지', distance_m: 1000,
    recommendation_reason: '', replacement_reason: '', congestion_level: 'QUIET' })) })
const fakeApi = () => ({ getStraightAlternatives: vi.fn().mockResolvedValue(response()), swapItem: vi.fn().mockResolvedValue({}),
  getCourseDetail: vi.fn().mockResolvedValue({ ...detail(), title: '갱신된 코스' }) })

describe('saved course straight-line alternatives', () => {
  it('ignores a past itinerary date, uses the server forecast date and preserves the itinerary when swapping', async () => {
    const course = ref(detail()), api = fakeApi(), actions = useSavedCourseActions(course, api)
    await actions.openSwap(course.value.days[0], course.value.days[0].items[0])
    expect(api.getStraightAlternatives).toHaveBeenCalledWith(101, [102])
    expect(actions.forecastDate.value).toBe('2026-09-18')
    expect(course.value.days[0].visitDate).toBe('2020-09-10')
    await actions.applySwap(response([103]).places[0])
    expect(api.swapItem).toHaveBeenCalledWith('1', 11, 103, true)
    expect(course.value.title).toBe('갱신된 코스')
  })
  it('reveals three at a time without another API call and removes duplicates and course places', async () => {
    const course = ref(detail()), api = fakeApi()
    api.getStraightAlternatives.mockResolvedValue(response([201,202,203,204,204,101,102]))
    const actions = useSavedCourseActions(course, api)
    await actions.openSwap(course.value.days[0], course.value.days[0].items[0])
    expect(actions.alternatives.value.map(p => p.place_id)).toEqual([201,202,203])
    expect(actions.hasMore.value).toBe(true)
    await actions.loadMore()
    expect(actions.alternatives.value.map(p => p.place_id)).toEqual([201,202,203,204])
    expect(actions.hasMore.value).toBe(false)
    await actions.loadMore(); await actions.loadMore()
    expect(api.getStraightAlternatives).toHaveBeenCalledTimes(1)
  })
  it('does not request again on scroll after failure, but allows explicit retry', async () => {
    const course = ref(detail()), api = fakeApi()
    api.getStraightAlternatives.mockRejectedValueOnce(new Error('offline'))
    const actions = useSavedCourseActions(course, api)
    await actions.openSwap(course.value.days[0], course.value.days[0].items[0])
    await actions.loadMore(); await actions.loadMore()
    expect(api.getStraightAlternatives).toHaveBeenCalledTimes(1)
    expect(actions.loadFailed.value).toBe(true)
    await actions.loadMore(true)
    expect(api.getStraightAlternatives).toHaveBeenCalledTimes(2)
    expect(actions.loadFailed.value).toBe(false)
    expect(actions.hasMore.value).toBe(false)
  })
  it('prevents duplicate requests while loading and ignores responses after another course opens', async () => {
    let resolve!: (value: StraightAlternatives) => void
    const course = ref(detail()), api = fakeApi()
    api.getStraightAlternatives.mockImplementation(() => new Promise<StraightAlternatives>(r => { resolve = r }))
    const actions = useSavedCourseActions(course, api)
    const pending = actions.openSwap(course.value.days[0], course.value.days[0].items[0])
    await actions.loadMore(); await actions.loadMore(true)
    expect(api.getStraightAlternatives).toHaveBeenCalledTimes(1)
    course.value = { ...detail(), id: '2' }; actions.reset()
    resolve(response([999])); await pending
    expect(actions.alternatives.value).toEqual([])
    expect(actions.target.value).toBeNull()
  })
  it('does not overwrite another course after an in-flight swap', async () => {
    let resolve!: (value: unknown) => void
    const course = ref(detail()), api = fakeApi()
    api.swapItem.mockImplementation(() => new Promise(r => { resolve = r }))
    const actions = useSavedCourseActions(course, api)
    await actions.openSwap(course.value.days[0], course.value.days[0].items[0])
    const pending = actions.applySwap(response([103]).places[0])
    course.value = { ...detail(), id: '2' }; actions.reset(); resolve({}); await pending
    expect(course.value.id).toBe('2')
  })
})
