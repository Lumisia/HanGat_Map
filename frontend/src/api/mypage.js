/**
 * 저장 코스·알림용 기존 임시 API. 내 리뷰·찜은 myActivity.js의 실제 서버 API를 사용한다.
 *
 * 모든 조회·변경은 **토큰의 userId 로만** 대상을 좁힌다.
 * 요청 본문에 들어온 ID 를 믿지 않는다 (MY_002 / MY_005 / MY_007 의 타인 데이터 접근 금지 조건).
 */

import { call, ApiError, nextId } from './client.js'
import { PLACE_BY_ID, PLACE_BY_NAME } from '../data/places.js'
import { crowdOn, weatherOn, tier, drive } from '../utils/crowd.js'
import { iso, toDate, todayKst, tripPhase } from '../utils/format.js'

/* ────────────────────────── 공통 ────────────────────────── */

/** 일정이 바뀐 뒤 이동시간·예상 비용을 다시 계산한다 (db.js 시드 계산과 동일 규칙) */
function recomputeCourse (course) {
  let totalTravel = 0
  let cost = 0
  course.days.forEach(day => {
    let prev = null
    day.stops.forEach(stop => {
      const p =
        stop.kind === 'meal'
          ? stop.at
            ? PLACE_BY_NAME[stop.at]
            : null
          : stop.placeId
            ? PLACE_BY_ID[stop.placeId]
            : null
      stop.travelMinutes = prev && p ? drive(prev, p) : 0
      totalTravel += stop.travelMinutes
      if (stop.kind === 'meal') cost += (stop.price || 0) * course.partySize
      else if (p) cost += p.fee * course.partySize
      if (p) prev = p
    })
  })
  course.totalTravelMinutes = totalTravel
  course.estimatedCost = cost
  return course
}

function page (items, { page: p = 1, size = 5 }) {
  const total = items.length
  const start = 0
  const end = Math.max(0, p) * size
  return {
    items: items.slice(start, end),
    total,
    page: p,
    size,
    hasMore: end < total
  }
}


/* ────────────────────────── MY_001 저장 코스 목록 ────────────────────────── */

export const COURSE_SORTS = [
  { key: 'saved_desc', label: '최신 저장순' },
  { key: 'start_asc', label: '여행 시작일순' }
]

const TRANSPORT_KO = {
  CAR: '렌터카',
  PUBLIC_TRANSIT: '대중교통',
  TAXI: '택시',
  WALK: '도보',
  BICYCLE: '자전거'
}

function courseSummary (db, course, savedAt) {
  const placeStops = course.days.flatMap(d =>
    d.stops.filter(s => s.kind !== 'meal' && s.placeId).map(s => ({ ...s, date: d.date }))
  )
  const places = placeStops.map(s => PLACE_BY_ID[s.placeId]).filter(Boolean)

  // 여행 예정일의 날씨·혼잡 (MY_001: "정보가 존재하는 경우 배지를 표시")
  const startDate = toDate(course.startDate)
  const weather = weatherOn(startDate)
  const crowdVals = placeStops
    .map(s => {
      const p = PLACE_BY_ID[s.placeId]
      return p ? crowdOn(p, s.date) : null
    })
    .filter(v => v != null)
  const avgCrowd = crowdVals.length
    ? Math.round(crowdVals.reduce((a, b) => a + b, 0) / crowdVals.length)
    : null

  const share = db.shares.find(s => s.courseId === course.courseId && s.active)

  return {
    courseId: course.courseId,
    courseName: course.courseName,
    summary: course.summary,
    startDate: course.startDate,
    endDate: course.endDate,
    partySize: course.partySize,
    transportModeCode: course.transportModeCode,
    transportLabel: TRANSPORT_KO[course.transportModeCode] || course.transportModeCode,
    mainPlaces: places.slice(0, 4).map(p => p.name),
    placeCount: places.length,
    estimatedCost: course.estimatedCost,
    budgetAmount: course.budgetAmount,
    totalTravelMinutes: course.totalTravelMinutes,
    savedAt,
    dayCount: course.days.length,
    weather: { kind: weather.k, t: weather.t, tmin: weather.tmin },
    avgCrowd,
    crowdTier: tier(avgCrowd),
    shared: !!share,
    shareToken: share ? share.token : null,
    isPast: tripPhase(course.startDate, course.endDate) === 'PAST'
  }
}

export function listSavedCourses ({ sort = 'saved_desc', page: p = 1, size = 5 } = {}) {
  return call(({ userId, db }) => {
    const rows = db.savedCourses
      .filter(s => s.userId === userId)
      .map(s => ({ saved: s, course: db.courses.find(c => c.courseId === s.courseId) }))
      .filter(r => r.course && !r.course.deletedAt)

    rows.sort((a, b) => {
      if (sort === 'start_asc') {
        return toDate(a.course.startDate) - toDate(b.course.startDate)
      }
      return toDate(b.saved.createdAt) - toDate(a.saved.createdAt)
    })

    return page(rows.map(r => courseSummary(db, r.course, r.saved.createdAt)), { page: p, size })
  }, { auth: true })
}

/** 코스 상세 — 소유자만 (MY_001 → 상세 이동) */
export function getSavedCourse (courseId) {
  return call(({ userId, db }) => {
    const saved = db.savedCourses.find(s => s.userId === userId && s.courseId === courseId)
    const course = db.courses.find(c => c.courseId === courseId && !c.deletedAt)
    if (!saved || !course) {
      throw new ApiError(404, 'COURSE_NOT_FOUND', '저장한 코스를 찾을 수 없어요')
    }
    if (course.ownerUserId !== userId) {
      throw new ApiError(403, 'FORBIDDEN', '접근할 수 없는 코스예요')
    }
    return { ...courseSummary(db, course, saved.createdAt), days: hydrateDays(course) }
  }, { auth: true })
}

function hydrateDays (course) {
  return course.days.map(day => ({
    day: day.day,
    date: day.date,
    weather: (() => {
      const w = weatherOn(day.date)
      return { kind: w.k, t: w.t, tmin: w.tmin }
    })(),
    stops: day.stops.map(s => {
      const p = s.placeId ? PLACE_BY_ID[s.placeId] : null
      return {
        ...s,
        place: p
          ? {
              id: p.id, name: p.name, category: p.category, region: p.region,
              addr: p.addr, fee: p.fee, indoor: p.indoor, x: p.x, y: p.y,
              stayMinutes: p.stayMinutes
            }
          : null,
        crowd: p ? crowdOn(p, day.date) : null
      }
    })
  }))
}

/* ────────────────────────── MY_002 저장 코스 삭제 ────────────────────────── */

/**
 * 삭제는 멱등이다. 이미 지운 코스에 다시 요청해도 오류를 내지 않는다
 * (정의서 MY_002: "이미 삭제된 코스에 다시 삭제 요청이 오더라도 오류가 안생기게끔").
 */
export function deleteSavedCourse (courseId) {
  return call(({ userId, db }) => {
    const course = db.courses.find(c => c.courseId === courseId)

    // 타인 코스는 ID 를 알아도 지울 수 없다
    if (course && course.ownerUserId !== userId) {
      throw new ApiError(403, 'FORBIDDEN', '다른 사람의 코스는 삭제할 수 없어요')
    }

    const before = db.savedCourses.length
    db.savedCourses = db.savedCourses.filter(
      s => !(s.userId === userId && s.courseId === courseId)
    )
    const removed = before !== db.savedCourses.length

    if (course && course.ownerUserId === userId) {
      course.deletedAt = course.deletedAt || new Date().toISOString()
      course.statusCode = 'DELETED'
    }
    // 삭제되면 공유 URL 도 더 이상 열리지 않아야 한다
    db.shares.forEach(s => {
      if (s.courseId === courseId) s.active = false
    })
    // 이 코스에 걸린 알림도 정리한다
    db.alerts = db.alerts.filter(a => !(a.userId === userId && a.courseId === courseId))

    return { ok: true, removed, alreadyDeleted: !removed }
  }, { auth: true })
}

/* ────────────────────────── MY_003 코스 공유 ────────────────────────── */

export function createShare (courseId) {
  return call(({ userId, db }) => {
    const course = db.courses.find(c => c.courseId === courseId && !c.deletedAt)
    if (!course) throw new ApiError(404, 'COURSE_NOT_FOUND', '코스를 찾을 수 없어요')
    if (course.ownerUserId !== userId) {
      throw new ApiError(403, 'FORBIDDEN', '내가 만든 코스만 공유할 수 있어요')
    }
    let share = db.shares.find(s => s.courseId === courseId)
    if (share) {
      share.active = true
    } else {
      share = {
        shareId: nextId('share', 'sh'),
        courseId,
        token: 'shr_' + Math.random().toString(36).slice(2, 12),
        active: true,
        createdAt: new Date().toISOString()
      }
      db.shares.push(share)
    }
    return { token: share.token, active: true }
  }, { auth: true })
}

export function stopShare (courseId) {
  return call(({ userId, db }) => {
    const course = db.courses.find(c => c.courseId === courseId)
    if (course && course.ownerUserId !== userId) {
      throw new ApiError(403, 'FORBIDDEN', '내가 만든 코스만 공유를 중지할 수 있어요')
    }
    db.shares.forEach(s => {
      if (s.courseId === courseId) s.active = false
    })
    return { ok: true, active: false }
  }, { auth: true })
}

/**
 * 공유 URL 조회 — **비로그인도 가능**.
 * 정의서 MY_003: "코스 일정, 방문 장소, 이동 경로, 예상 소요 시간 등의 정보만 제공"
 * → 예산·예상 비용·소유자 정보·인원은 내려주지 않는다.
 */
export function getSharedCourse (token) {
  return call(({ db }) => {
    const share = db.shares.find(s => s.token === token)
    if (!share || !share.active) {
      throw new ApiError(404, 'SHARE_NOT_AVAILABLE', '더 이상 볼 수 없는 코스예요')
    }
    const course = db.courses.find(c => c.courseId === share.courseId && !c.deletedAt)
    if (!course) {
      throw new ApiError(404, 'SHARE_NOT_AVAILABLE', '더 이상 볼 수 없는 코스예요')
    }
    return {
      courseName: course.courseName,
      summary: course.summary,
      startDate: course.startDate,
      endDate: course.endDate,
      transportLabel: TRANSPORT_KO[course.transportModeCode] || course.transportModeCode,
      totalTravelMinutes: course.totalTravelMinutes,
      days: hydrateDays(course).map(d => ({
        day: d.day,
        date: d.date,
        weather: d.weather,
        stops: d.stops.map(s => ({
          time: s.time,
          kind: s.kind,
          name: s.kind === 'meal' ? s.name : s.place?.name,
          menu: s.menu || null,
          category: s.place?.category || null,
          addr: s.place?.addr || null,
          stayMinutes: s.place?.stayMinutes || null,
          travelMinutes: s.travelMinutes || 0,
          crowd: s.crowd
        }))
      }))
    }
  })
}


/* ────────────────────────── MY_008 예보 변경 알림 ────────────────────────── */

function alertView (db, a) {
  const course = db.courses.find(c => c.courseId === a.courseId)
  return {
    alertId: a.alertId,
    courseId: a.courseId,
    courseName: course ? course.courseName : '(삭제된 코스)',
    affectedDate: a.affectedDate,
    places: (a.placeIds || []).map(id => PLACE_BY_ID[id]?.name).filter(Boolean),
    placeIds: a.placeIds || [],
    before: a.before,
    after: a.after,
    severity: a.severity,
    read: a.read,
    createdAt: a.createdAt,
    resolvedAt: a.resolvedAt
  }
}

/* ── 보관 기간 ───────────────────────────────────────────────
   "일주일이나 여행 끝날 때까지는 유지" — 둘 중 **하나라도** 해당하면 남긴다.
   즉 만들어진 지 7일이 지났고 **동시에** 여행도 끝났을 때만 사라진다.
   읽었는지 여부는 보관과 무관하다 — 읽었다고 바로 지우면
   "그때 무슨 알림이었지"를 다시 볼 수 없다. 지우는 건 X 버튼(사용자 의사)뿐이다. */

export const ALERT_RETENTION_DAYS = 7

/** 여행이 끝나는 날 — 코스 종료일, 없으면 영향받는 날짜로 갈음한다 */
function alertTripEnd (db, a) {
  const course = db.courses.find(c => c.courseId === a.courseId)
  return course?.endDate || a.affectedDate || null
}

/** 아직 보관해야 하는 알림인가 */
export function isAlertLive (db, a, now = new Date()) {
  if (a.dismissedAt) return false

  const ageMs = now - toDate(a.createdAt)
  const withinWeek = ageMs < ALERT_RETENTION_DAYS * 24 * 60 * 60 * 1000
  if (withinWeek) return true

  const end = alertTripEnd(db, a)
  if (!end) return false
  // Date-only strings stay date-only. The last day includes KST 23:59:59.
  return end >= todayKst(now)
}

export function listAlerts ({ onlyUnread = false } = {}) {
  return call(({ userId, db }) => {
    const now = new Date()
    const mine = db.alerts.filter(a => a.userId === userId && isAlertLive(db, a, now))
    const rows = mine
      .filter(a => (onlyUnread ? !a.read : true))
      // 중요도 높은 것 먼저, 그 다음 최신순 (경미한 변화는 아래로)
      .sort((a, b) => {
        const s = (b.severity === 'HIGH') - (a.severity === 'HIGH')
        return s || toDate(b.createdAt) - toDate(a.createdAt)
      })
      .map(a => alertView(db, a))
    const unread = mine.filter(a => !a.read).length
    return { items: rows, total: rows.length, unread }
  }, { auth: true })
}

/**
 * 알림 닫기 (카드 오른쪽 위 X).
 *
 * 레코드를 지우지 않고 `dismissedAt` 만 남긴다 — 되돌릴 여지를 남기고,
 * dedupeKey 가 살아 있어야 같은 예보 변경으로 알림이 다시 생기지 않는다.
 * 이미 닫힌 알림에 다시 요청해도 오류가 아니다 (멱등).
 */
export function dismissAlert (alertId) {
  return call(({ userId, db }) => {
    const a = db.alerts.find(x => x.alertId === alertId && x.userId === userId)
    if (!a) throw new ApiError(404, 'ALERT_NOT_FOUND', '알림을 찾을 수 없어요')
    a.dismissedAt = a.dismissedAt || new Date().toISOString()
    // 닫은 알림이 "안 읽음"으로 남아 배지 숫자만 올리는 걸 막는다
    a.read = true
    return { ok: true, alertId }
  }, { auth: true })
}

export function setAlertRead (alertId, read = true) {
  return call(({ userId, db }) => {
    const a = db.alerts.find(x => x.alertId === alertId && x.userId === userId)
    if (!a) throw new ApiError(404, 'ALERT_NOT_FOUND', '알림을 찾을 수 없어요')
    a.read = !!read
    return alertView(db, a)
  }, { auth: true })
}

export function markAllAlertsRead () {
  return call(({ userId, db }) => {
    let n = 0
    db.alerts.forEach(a => {
      if (a.userId === userId && !a.read) {
        a.read = true
        n++
      }
    })
    return { ok: true, updated: n }
  }, { auth: true })
}

/**
 * 부분 재구성 (MY_008).
 * 영향받는 날짜의 일정만 다시 짠다.
 *   - 사용자가 고정한 일정(fixed)은 건드리지 않는다
 *   - 예산·인원·이동수단 등 기존 조건은 그대로 둔다
 *   - 실내 대안이 없으면 교체하지 않고 이유를 남긴다
 */
export function regenerateAffectedDay (alertId) {
  return call(({ userId, db }) => {
    const alert = db.alerts.find(a => a.alertId === alertId && a.userId === userId)
    if (!alert) throw new ApiError(404, 'ALERT_NOT_FOUND', '알림을 찾을 수 없어요')

    const course = db.courses.find(c => c.courseId === alert.courseId && !c.deletedAt)
    if (!course) throw new ApiError(404, 'COURSE_NOT_FOUND', '코스를 찾을 수 없어요')
    if (course.ownerUserId !== userId) throw new ApiError(403, 'FORBIDDEN', '내 코스만 다시 만들 수 있어요')

    const day = course.days.find(d => d.date === alert.affectedDate)
    if (!day) throw new ApiError(404, 'DAY_NOT_FOUND', '해당 날짜 일정을 찾을 수 없어요')

    const usedIds = new Set(course.days.flatMap(d => d.stops.map(s => s.placeId).filter(Boolean)))
    const changes = []

    day.stops.forEach(stop => {
      if (stop.kind === 'meal' || !stop.placeId) return
      const cur = PLACE_BY_ID[stop.placeId]
      if (!cur) return

      if (stop.fixed) {
        changes.push({ from: cur.name, to: null, reason: '직접 지정한 일정이라 그대로 뒀어요' })
        return
      }
      if (cur.indoor) {
        changes.push({ from: cur.name, to: null, reason: '이미 실내라 바꾸지 않았어요' })
        return
      }

      // 같은 권역의 실내 후보를 거리·혼잡 순으로 고른다
      const candidates = Object.values(PLACE_BY_ID)
        .filter(p => p.indoor && p.region === cur.region && !usedIds.has(p.id))
        .map(p => ({ p, move: drive(cur, p), crowd: crowdOn(p, day.date) }))
        .sort((a, b) => a.crowd - b.crowd || a.move - b.move)

      if (!candidates.length) {
        changes.push({
          from: cur.name,
          to: null,
          reason: `${cur.region}에 바꿔 넣을 실내 장소가 없어 그대로 뒀어요`
        })
        return
      }
      const pick = candidates[0]
      usedIds.delete(cur.id)
      usedIds.add(pick.p.id)
      stop.placeId = pick.p.id
      stop.placeName = pick.p.name
      stop.why = `${alert.after.kind} 예보로 바뀌어 실내인 ${pick.p.name}으로 옮겼어요`
      changes.push({
        from: cur.name,
        to: pick.p.name,
        reason: `${alert.after.warning || alert.after.kind} 예보 · 실내 · 이동 ${pick.move}분`
      })
    })

    recomputeCourse(course)
    alert.read = true
    alert.resolvedAt = new Date().toISOString()

    return {
      ok: true,
      courseId: course.courseId,
      date: day.date,
      changes,
      replacedCount: changes.filter(c => c.to).length,
      estimatedCost: course.estimatedCost,
      totalTravelMinutes: course.totalTravelMinutes,
      keptConditions: {
        partySize: course.partySize,
        budgetAmount: course.budgetAmount,
        transportModeCode: course.transportModeCode,
        transportLabel: TRANSPORT_KO[course.transportModeCode] || course.transportModeCode,
        fixedPlaces: day.stops.filter(s => s.fixed && s.placeId).map(s => PLACE_BY_ID[s.placeId]?.name).filter(Boolean)
      }
    }
  }, { auth: true })
}
