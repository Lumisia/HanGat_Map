/**
 * 마이페이지 API 동작 검증.
 * 요구사항 정의서 MY_001 ~ MY_008 중 **말로만 확인하기 어려운 조건**을 골라 테스트했다.
 *  - 타인 데이터 접근 차단
 *  - 삭제 멱등성
 *  - 공유 URL 의 노출 범위 제한
 *  - 부분 재구성이 고정 일정을 건드리지 않는지
 */
import { describe, it, expect, beforeEach } from 'vitest'
import { resetDb, TEST_ACCOUNT, load } from './db.js'
import { storage } from './storage.js'
import { setLatency } from './client.js'
import { ApiError } from './errors.js'
import { _resetSessionState } from './session.js'
import { PASSWORD_POLICY } from '../components/security/passwordPolicy.js'
import { todayKst, addCalendarDays } from '../utils/format.js'
import { login, logout } from './auth.js'
import {
  listSavedCourses, getSavedCourse, deleteSavedCourse,
  createShare, stopShare, getSharedCourse,
  listAlerts, setAlertRead, regenerateAffectedDay,
  dismissAlert, isAlertLive, ALERT_RETENTION_DAYS
} from './mypage.js'

setLatency(0)
PASSWORD_POLICY.checkBreach = false

beforeEach(async () => {
  storage._resetMemory()
  _resetSessionState()
  resetDb()
  await login({ email: TEST_ACCOUNT.email, password: TEST_ACCOUNT.password })
})

const expectApiError = async (fn, status, code) => {
  await expect(fn()).rejects.toMatchObject({ status, ...(code ? { code } : {}) })
}

/* ────────────────────────── MY_001 ────────────────────────── */

describe('MY_001 저장 코스 목록', () => {
  it('내 코스만 나온다 (타인 코스 c9 는 제외)', async () => {
    const res = await listSavedCourses({ page: 1, size: 50 })
    expect(res.items.length).toBeGreaterThan(0)
    expect(res.items.some(c => c.courseId === 'c9')).toBe(false)
  })

  it('요약 정보에 필수 항목이 모두 들어 있다', async () => {
    const { items } = await listSavedCourses({ page: 1, size: 50 })
    const c = items[0]
    for (const k of ['courseName', 'startDate', 'endDate', 'partySize',
      'transportLabel', 'mainPlaces', 'estimatedCost', 'savedAt', 'weather', 'avgCrowd']) {
      expect(c[k], k).not.toBeUndefined()
    }
  })

  it('최신 저장순 / 여행 시작일순 정렬이 서로 다르게 동작한다', async () => {
    const bySaved = await listSavedCourses({ sort: 'saved_desc', page: 1, size: 50 })
    const byStart = await listSavedCourses({ sort: 'start_asc', page: 1, size: 50 })

    const savedTimes = bySaved.items.map(c => +new Date(c.savedAt))
    expect([...savedTimes].sort((a, b) => b - a)).toEqual(savedTimes)

    const startTimes = byStart.items.map(c => +new Date(c.startDate))
    expect([...startTimes].sort((a, b) => a - b)).toEqual(startTimes)
  })

  it('더보기: page 를 올리면 항목이 누적된다', async () => {
    const p1 = await listSavedCourses({ page: 1, size: 2 })
    expect(p1.items).toHaveLength(2)
    expect(p1.hasMore).toBe(true)
    const p2 = await listSavedCourses({ page: 2, size: 2 })
    expect(p2.items.length).toBeGreaterThan(p1.items.length)
    expect(p2.items.slice(0, 2)).toEqual(p1.items)
  })

  it('타인 코스는 ID 를 알아도 상세를 볼 수 없다', async () => {
    await expectApiError(() => getSavedCourse('c9'), 404)
  })
})

/* ────────────────────────── MY_002 ────────────────────────── */

describe('MY_002 저장 코스 삭제', () => {
  it('삭제하면 목록에서 사라진다', async () => {
    const before = await listSavedCourses({ page: 1, size: 50 })
    await deleteSavedCourse('c1')
    const after = await listSavedCourses({ page: 1, size: 50 })
    expect(after.total).toBe(before.total - 1)
    expect(after.items.some(c => c.courseId === 'c1')).toBe(false)
  })

  it('이미 삭제된 코스에 다시 요청해도 오류가 나지 않는다 (멱등)', async () => {
    await deleteSavedCourse('c1')
    const again = await deleteSavedCourse('c1')
    expect(again.ok).toBe(true)
    expect(again.alreadyDeleted).toBe(true)
  })

  it('타인 코스는 ID 를 알아도 삭제할 수 없다', async () => {
    await expectApiError(() => deleteSavedCourse('c9'), 403, 'FORBIDDEN')
  })

  it('삭제하면 발급된 공유 URL 도 열리지 않는다', async () => {
    const { token } = await createShare('c1')
    await expect(getSharedCourse(token)).resolves.toBeTruthy()
    await deleteSavedCourse('c1')
    await expectApiError(() => getSharedCourse(token), 404, 'SHARE_NOT_AVAILABLE')
  })
})

/* ────────────────────────── MY_003 ────────────────────────── */

describe('MY_003 코스 공유', () => {
  it('공유 URL 은 로그아웃 상태에서도 열린다', async () => {
    const { token } = await createShare('c1')
    await logout()
    const shared = await getSharedCourse(token)
    expect(shared.courseName).toBeTruthy()
    expect(shared.days.length).toBeGreaterThan(0)
  })

  it('공유 응답에 예산·비용·인원·소유자 정보가 들어 있지 않다', async () => {
    const { token } = await createShare('c1')
    const shared = await getSharedCourse(token)
    for (const k of ['budgetAmount', 'estimatedCost', 'partySize', 'ownerUserId', 'savedAt']) {
      expect(shared[k], k).toBeUndefined()
    }
    // 대신 일정·이동·소요시간은 있어야 한다
    expect(shared.totalTravelMinutes).toBeTypeOf('number')
    expect(shared.days[0].stops[0]).toHaveProperty('travelMinutes')
    expect(shared.days[0].stops[0]).toHaveProperty('stayMinutes')
  })

  it('공유를 중지하면 같은 URL 로 볼 수 없다', async () => {
    const { token } = await createShare('c1')
    await stopShare('c1')
    await expectApiError(() => getSharedCourse(token), 404)
  })

  it('중지한 공유를 다시 켜면 같은 토큰이 다시 열린다', async () => {
    const first = await createShare('c1')
    await stopShare('c1')
    const second = await createShare('c1')
    expect(second.token).toBe(first.token)
    await expect(getSharedCourse(first.token)).resolves.toBeTruthy()
  })

  it('타인 코스는 공유할 수 없다', async () => {
    await expectApiError(() => createShare('c9'), 403, 'FORBIDDEN')
  })
})


/* ────────────────────────── MY_008 ────────────────────────── */

describe('MY_008 예보 변경 알림', () => {
  it('중요도 높은 알림이 먼저 나온다', async () => {
    const { items } = await listAlerts({})
    const firstLow = items.findIndex(a => a.severity === 'LOW')
    const lastHigh = items.map(a => a.severity).lastIndexOf('HIGH')
    if (firstLow !== -1) expect(firstLow).toBeGreaterThan(lastHigh)
  })

  it('읽음 처리하면 안 읽은 개수가 준다', async () => {
    const before = await listAlerts({})
    const unreadOne = before.items.find(a => !a.read)
    await setAlertRead(unreadOne.alertId, true)
    const after = await listAlerts({})
    expect(after.unread).toBe(before.unread - 1)
  })

  it('부분 재구성은 고정 일정을 바꾸지 않는다', async () => {
    // a1 은 c1 의 2일차(제주현대미술관 고정 + 수월봉 비고정)를 가리킨다
    const res = await regenerateAffectedDay('a1')
    const kept = res.changes.find(c => c.from === '제주현대미술관')
    expect(kept).toBeTruthy()
    expect(kept.to).toBeNull()
    expect(res.keptConditions.fixedPlaces).toContain('제주현대미술관')
  })

  it('부분 재구성은 야외 장소를 같은 권역 실내 장소로 바꾼다', async () => {
    const res = await regenerateAffectedDay('a1')
    const moved = res.changes.find(c => c.from === '수월봉')
    expect(moved).toBeTruthy()
    expect(moved.to).toBeTruthy()

    const course = await getSavedCourse('c1')
    const day2 = course.days.find(d => d.date === res.date)
    const names = day2.stops.filter(s => s.place).map(s => s.place.name)
    expect(names).toContain(moved.to)
    expect(names).not.toContain('수월봉')
  })

  it('부분 재구성 뒤 예산·인원·이동수단 조건이 그대로 남는다', async () => {
    const before = await getSavedCourse('c1')
    const res = await regenerateAffectedDay('a1')
    expect(res.keptConditions.partySize).toBe(before.partySize)
    expect(res.keptConditions.budgetAmount).toBe(before.budgetAmount)
    expect(res.keptConditions.transportModeCode).toBe(before.transportModeCode)
  })

  it('대체할 실내 장소가 없으면 바꾸지 않고 이유를 남긴다', async () => {
    // a2 는 c3(서귀포·중문) 1일차 — 이 권역 실내 후보는 오설록티뮤지엄 하나뿐이다
    const res = await regenerateAffectedDay('a2')
    expect(res.replacedCount).toBe(1)
    const kept = res.changes.find(c => !c.to)
    expect(kept).toBeTruthy()
    expect(kept.reason).toContain('실내 장소가 없어')
  })

  it('재구성한 알림은 읽음 + 처리 완료로 바뀐다', async () => {
    await regenerateAffectedDay('a1')
    const { items } = await listAlerts({})
    const a1 = items.find(a => a.alertId === 'a1')
    expect(a1.read).toBe(true)
    expect(a1.resolvedAt).toBeTruthy()
  })
})

/* ────────────────────────── 인증 경계 ────────────────────────── */

describe('로그인이 필요한 API', () => {
  it('로그아웃 상태에서는 401 을 낸다', async () => {
    await logout()
    await expect(listSavedCourses({})).rejects.toBeInstanceOf(ApiError)
    await expect(listSavedCourses({})).rejects.toMatchObject({ status: 401 })
  })
})

/* ═══════════ 알림 보관 기간 · 닫기 ═══════════
   "일주일이나 여행 끝날 때까지는 유지" — 둘 중 하나라도 해당하면 남는다. */

describe('알림 보관 기간', () => {
  const DAY_MS = 24 * 60 * 60 * 1000
  const ago = d => new Date(Date.now() - d * DAY_MS).toISOString()
  const inDays = d => addCalendarDays(todayKst(), d)

  beforeEach(async () => {
    await login({ email: TEST_ACCOUNT.email, password: TEST_ACCOUNT.password })
  })

  const alertOf = id => load().alerts.find(a => a.alertId === id)

  it('일주일이 안 지났으면 여행이 끝났어도 남는다', () => {
    const a = alertOf('a1')
    a.createdAt = ago(3)
    a.affectedDate = inDays(-30)      // 여행은 한참 전에 끝남
    a.courseId = 'no-such-course'     // 코스 종료일도 없음
    expect(isAlertLive(load(), a)).toBe(true)
  })

  it('일주일이 지났어도 여행이 안 끝났으면 남는다', () => {
    const a = alertOf('a1')
    a.createdAt = ago(ALERT_RETENTION_DAYS + 20)
    // c1 은 시드에서 +7 ~ +8일 코스라 아직 안 끝났다
    expect(isAlertLive(load(), a)).toBe(true)
  })

  it('일주일도 지나고 여행도 끝났으면 사라진다', async () => {
    const a = alertOf('a1')
    a.createdAt = ago(ALERT_RETENTION_DAYS + 1)
    a.affectedDate = inDays(-30)
    a.courseId = 'no-such-course'
    expect(isAlertLive(load(), a)).toBe(false)

    const res = await listAlerts()
    expect(res.items.some(x => x.alertId === 'a1')).toBe(false)
  })

  it('경계: 보관 기간 직전은 남고, 직후는 여행 종료 여부를 본다', () => {
    const a = alertOf('a1')
    a.affectedDate = inDays(-30)
    a.courseId = 'no-such-course'

    a.createdAt = ago(ALERT_RETENTION_DAYS - 0.01)
    expect(isAlertLive(load(), a)).toBe(true)

    a.createdAt = ago(ALERT_RETENTION_DAYS + 0.01)
    expect(isAlertLive(load(), a)).toBe(false)
  })

  it('여행 마지막 날 당일까지는 남는다', () => {
    const a = alertOf('a1')
    a.createdAt = ago(ALERT_RETENTION_DAYS + 5)
    a.courseId = 'no-such-course'
    a.affectedDate = inDays(0)        // 오늘이 마지막 날
    expect(isAlertLive(load(), a)).toBe(true)
    a.affectedDate = inDays(-1)
    expect(isAlertLive(load(), a)).toBe(false)
  })

  it('읽었다고 사라지지 않는다', async () => {
    await setAlertRead('a1', true)
    const res = await listAlerts()
    expect(res.items.some(x => x.alertId === 'a1')).toBe(true)
  })

  it('만료된 알림은 안 읽은 개수에도 안 들어간다', async () => {
    const before = (await listAlerts()).unread
    const a = alertOf('a1')
    expect(a.read).toBe(false)
    a.createdAt = ago(ALERT_RETENTION_DAYS + 1)
    a.affectedDate = inDays(-30)
    a.courseId = 'no-such-course'
    expect((await listAlerts()).unread).toBe(before - 1)
  })
})

describe('알림 닫기 (X)', () => {
  beforeEach(async () => {
    await login({ email: TEST_ACCOUNT.email, password: TEST_ACCOUNT.password })
  })

  it('닫으면 목록에서 빠진다', async () => {
    const before = await listAlerts()
    await dismissAlert('a1')
    const after = await listAlerts()
    expect(after.items.some(x => x.alertId === 'a1')).toBe(false)
    expect(after.total).toBe(before.total - 1)
  })

  it('레코드를 지우지 않고 dismissedAt 만 남긴다 (dedupeKey 보존)', async () => {
    await dismissAlert('a1')
    const raw = load().alerts.find(a => a.alertId === 'a1')
    expect(raw).toBeTruthy()
    expect(raw.dismissedAt).toBeTruthy()
    expect(raw.dedupeKey).toBeTruthy()
  })

  it('안 읽은 채 닫아도 배지 숫자가 남지 않는다', async () => {
    const before = (await listAlerts()).unread
    await dismissAlert('a1')            // a1 은 안 읽음 상태
    expect((await listAlerts()).unread).toBe(before - 1)
  })

  it('두 번 닫아도 오류가 아니다 (멱등)', async () => {
    await dismissAlert('a1')
    await expect(dismissAlert('a1')).resolves.toMatchObject({ ok: true })
  })

  it('없는 알림은 404', async () => {
    await expect(dismissAlert('nope')).rejects.toMatchObject({ status: 404 })
  })

  it('로그아웃 상태에서는 401', async () => {
    await logout()
    await expect(dismissAlert('a1')).rejects.toBeInstanceOf(ApiError)
  })
})
