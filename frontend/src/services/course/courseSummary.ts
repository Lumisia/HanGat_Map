/** Display only measured route facts. Missing/partial totals are not zero. */
export type RouteTotals = { total_duration_seconds?: number | null; total_distance_meters?: number | null; legs?: { distance_meters: number | null; duration_seconds: number | null }[] }

export function routeSummary(days?: RouteTotals[] | null, loading = false): string {
  if (loading) return '계산 중'
  if (days?.some(d => d.legs?.some(l => !valid(l.distance_meters) || !valid(l.duration_seconds)))) return '일부 구간 정보 없음'
  if (!days?.length || days.some(d => !valid(d.total_duration_seconds) || !valid(d.total_distance_meters))) return '정보 없음'
  const seconds = days.reduce((sum, d) => sum + d.total_duration_seconds!, 0)
  const meters = days.reduce((sum, d) => sum + d.total_distance_meters!, 0)
  return `${Math.round(seconds / 60)}분 · ${(meters / 1000).toFixed(1)}km`
}

function valid(value: number | null | undefined): value is number {
  return value != null && Number.isFinite(value) && value >= 0
}
/** Notices are separate from road totals and original sightseeing markers. */
export function accessNotices(days?: { legs?: { from: { access_point?: { notice: string } | null }; to: { access_point?: { notice: string } | null } }[] }[]): string[] {
  return [...new Set(days?.flatMap(d => d.legs?.flatMap(l => [l.from.access_point?.notice, l.to.access_point?.notice]) ?? []).filter((s): s is string => !!s) ?? [])]
}
