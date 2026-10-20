import type { CourseResult, SavedCourseRecord, SavedCourseSummary } from '../assets/types/course'

const savedCourses = new Map<number, SavedCourseRecord>()
const pause = (ms = 200) => new Promise(resolve => setTimeout(resolve, ms))

function makeSummary(course: CourseResult, title: string): SavedCourseSummary {
  return {
    course_id: course.id,
    title,
    start_date: course.start_date,
    end_date: course.end_date,
    representative_places: course.days.flatMap(day => day.items).slice(0, 3).map(item => item.place_name),
    average_congestion_rate: course.average_congestion_rate ?? undefined,
    estimated_cost_min: course.estimated_cost_min,
    estimated_cost_max: course.estimated_cost_max,
  }
}

export const savedCourseMockService = {
  async save(course: CourseResult, title: string): Promise<SavedCourseRecord> {
    await pause()
    const clean = title.trim()
    if (clean.length < 1 || clean.length > 100) throw new Error('코스명은 1~100자로 입력해 주세요.')
    if (savedCourses.has(course.id)) throw new Error('이미 저장된 코스예요.')
    const saved = JSON.parse(JSON.stringify({ ...course, title: clean, status: 'SAVED' as const })) as CourseResult
    const record = { summary: makeSummary(saved, clean), course: saved }
    savedCourses.set(course.id, record)
    return JSON.parse(JSON.stringify(record)) as SavedCourseRecord
  },
  isSaved(courseId: number) { return savedCourses.has(courseId) },
  list(): SavedCourseSummary[] { return [...savedCourses.values()].map(record => ({ ...record.summary, representative_places: [...record.summary.representative_places] })) },
  get(courseId: number): SavedCourseRecord | undefined {
    const record = savedCourses.get(courseId)
    return record ? JSON.parse(JSON.stringify(record)) as SavedCourseRecord : undefined
  },
  clear() { savedCourses.clear() },
}
