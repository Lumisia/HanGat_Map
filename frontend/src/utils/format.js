/** 표시 포맷 유틸 — 원본 index.html:496~501 의 포맷 규칙을 그대로 따른다 */

const DOW = '일월화수목금토'

/** The user's current date is Korean, never the browser's local/UTC date. */
export function todayKst (now = new Date()) {
  const parts = new Intl.DateTimeFormat('en', {
    timeZone: 'Asia/Seoul', year: 'numeric', month: '2-digit', day: '2-digit'
  }).formatToParts(now)
  const part = type => parts.find(p => p.type === type).value
  return `${part('year')}-${part('month')}-${part('day')}`
}

// Internal calendar arithmetic only: never serialize this temporary Date as a trip timestamp.
function calendarValue (value) {
  if (!/^\d{4}-\d{2}-\d{2}$/.test(value)) throw new RangeError('Invalid calendar date')
  const [year, month, day] = value.split('-').map(Number)
  const date = new Date(Date.UTC(year, month - 1, day))
  if (date.getUTCFullYear() !== year || date.getUTCMonth() !== month - 1 || date.getUTCDate() !== day) {
    throw new RangeError('Invalid calendar date')
  }
  return date
}

export function addCalendarDays (value, days) {
  const date = calendarValue(value)
  date.setUTCDate(date.getUTCDate() + days)
  return `${date.getUTCFullYear()}-${String(date.getUTCMonth() + 1).padStart(2, '0')}-${String(date.getUTCDate()).padStart(2, '0')}`
}

export const calendarDayOffset = (start, end) => (calendarValue(end) - calendarValue(start)) / 86400000
export const tripPhase = (start, end, now = new Date()) =>
  end < todayKst(now) ? 'PAST' : start > todayKst(now) ? 'UPCOMING' : 'ONGOING'

/**
 * @param {string} value
 * @param {Intl.DateTimeFormatOptions} [options]
 */
export function formatCalendarDate (value, options = { month: 'long', day: 'numeric', weekday: 'short' }) {
  return new Intl.DateTimeFormat('ko-KR', { ...options, timeZone: 'UTC' }).format(calendarValue(value))
}

export function toDate (v) {
  if (v instanceof Date) return new Date(v.getTime())
  if (typeof v === 'string') {
    // 'YYYY-MM-DD' 는 로컬 자정으로 (UTC 파싱 시 하루 밀리는 문제 방지)
    if (/^\d{4}-\d{2}-\d{2}$/.test(v)) return new Date(`${v}T00:00:00`)
    return new Date(v)
  }
  return new Date(v)
}

/** 2026-07-20 */
export function iso (v) {
  const d = toDate(v)
  return `${d.getFullYear()}-${String(d.getMonth() + 1).padStart(2, '0')}-${String(d.getDate()).padStart(2, '0')}`
}

/** 7/20 (월)  — 원본 fmt() */
export function fmt (v) {
  const d = toDate(v)
  return `${d.getMonth() + 1}/${d.getDate()} (${DOW[d.getDay()]})`
}

/** 7월 20일 — 원본 fmtK() */
export function fmtK (v) {
  const d = toDate(v)
  return `${d.getMonth() + 1}월 ${d.getDate()}일`
}

/** 2026년 7월 20일 */
export function fmtFull (v) {
  const d = toDate(v)
  return `${d.getFullYear()}년 ${d.getMonth() + 1}월 ${d.getDate()}일`
}

/** 7/20 14:32 */
export function fmtDateTime (v) {
  const d = toDate(v)
  return `${d.getMonth() + 1}/${d.getDate()} ${String(d.getHours()).padStart(2, '0')}:${String(d.getMinutes()).padStart(2, '0')}`
}

/** 방금 전 / 3분 전 / 2시간 전 / 3일 전 / 날짜 */
export function fmtRelative (v, now = Date.now()) {
  const t = toDate(v).getTime()
  const diff = Math.floor((now - t) / 1000)
  if (diff < 60) return '방금 전'
  if (diff < 3600) return `${Math.floor(diff / 60)}분 전`
  if (diff < 86400) return `${Math.floor(diff / 3600)}시간 전`
  if (diff < 86400 * 7) return `${Math.floor(diff / 86400)}일 전`
  return fmtK(v)
}

/**
 * 날짜 + 최근이면 상대 표기.
 * 7일 이내면 '8월 12일 · 1일 전', 그보다 오래됐으면 '8월 1일'만 쓴다.
 * (fmtRelative 는 7일이 넘으면 fmtK 와 같은 값을 내므로 그대로 붙이면 같은 날짜가 두 번 나온다)
 */
export function fmtDateWithRelative (v, now = Date.now()) {
  const withinWeek = now - toDate(v).getTime() < 86400 * 7 * 1000
  return withinWeek ? `${fmtK(v)} · ${fmtRelative(v, now)}` : fmtK(v)
}

/** 여행 기간: 7/20 (월) ~ 7/21 (화) · 1박 2일 */
export function fmtPeriod (start, end) {
  const s = toDate(start)
  const e = toDate(end)
  const days = Math.round((e - s) / 86400000) + 1
  const nights = Math.max(0, days - 1)
  const label = nights === 0 ? '당일' : `${nights}박 ${days}일`
  return `${fmt(s)} ~ ${fmt(e)} · ${label}`
}

export function daysBetween (start, end) {
  return Math.round((toDate(end) - toDate(start)) / 86400000) + 1
}

/** 원본 won() — index.html:501 */
export function won (n) {
  return Number(n || 0).toLocaleString('ko-KR')
}

/** 120 -> '2시간 0분' / 45 -> '45분' — 원본 :776 표기 방식 */
export function fmtMinutes (m) {
  const v = Math.max(0, Math.round(m || 0))
  const h = Math.floor(v / 60)
  return h ? `${h}시간 ${v % 60}분` : `${v}분`
}

export function truncate (s, n) {
  const str = String(s || '')
  return str.length > n ? str.slice(0, n) + '…' : str
}
