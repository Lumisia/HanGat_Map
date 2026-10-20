import type { CongestionLevel } from '../assets/types'

// 집중률 -> 등급 변환. 팀 표준: 한산 <40 / 보통 <70 / 혼잡 >=70
// 단일 출처는 백엔드 map CongestionLevel.from - 여기와 어긋나면 배지와 저장 스냅숏이 달라진다
export function levelOf(score: number): CongestionLevel
export function levelOf(score: number | null | undefined): CongestionLevel | null
export function levelOf(score: number | null | undefined): CongestionLevel | null {
  if (score == null || !Number.isFinite(score)) return null
  if (score < 40) return 'QUIET'
  if (score < 70) return 'NORMAL'
  return 'CROWDED'
}

export const congestionLabel = (score: number | null | undefined): string => {
  const level = levelOf(score)
  return level == null ? '정보 없음' : { QUIET: '한산', NORMAL: '보통', CROWDED: '혼잡' }[level]
}
