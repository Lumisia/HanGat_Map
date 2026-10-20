import { describe, expect, it } from 'vitest'

const groupedViews = import.meta.glob('./**/*View.vue')
const legacyPages = import.meta.glob('../pages/*.vue')

describe('route view organization', () => {
  it('keeps every route screen inside its feature view folder', () => {
    expect(Object.keys(groupedViews)).toEqual(expect.arrayContaining([
      './ai-course/AiCourseView.vue',
      './course/SavedCoursesView.vue',
      './home/HomeView.vue',
      './map/MapView.vue',
      './place/PlaceDetailView.vue',
      './share/ShareCourseView.vue',
      './system/NotFoundView.vue',
      './system/OutOfScopeView.vue',
    ]))
  })

  it('does not leave route screens in the legacy pages folder', () => {
    expect(Object.keys(legacyPages)).toEqual([])
  })

  it('provides a lazy loader for each grouped view', () => {
    for (const loadView of Object.values(groupedViews)) {
      expect(loadView).toBeTypeOf('function')
    }
  })
})
