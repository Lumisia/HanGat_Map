/* 축소 뷰 핀 묶기 (2026-09-12 결정, A안).
   섬 전체 뷰(레벨 10 이상)는 행정 단위(읍·면 / 제주시내 / 서귀포시내 / 중문) 하나에 묶음 하나 - 이름이 겹치지 않는다.
   픽셀 거리로만 묶으면 같은 읍면 안에 묶음이 여러 개 생겨 "서귀포시 ×3"처럼 이름이 겹쳤다(실측 52묶음 중 유일한 이름 21개).
   한 단계 확대(레벨 9 이하)부터는 화면 픽셀 거리(40px)로 묶고 이름 없이 개수만 - 확대하면 거리가 2배가 되므로 저절로 갈라진다.
   어느 레벨까지 묶을지는 레이어별로 다르다(clusterModeFor). 그 아래는 안 묶는다.
   실측(관광지 812, 40px): 레벨 9 묶음 119+개별 18 · 8 → 184+119 · 7 → 154+319 */

export interface ClusterItem { px: number; py: number }
export interface Cluster<T extends ClusterItem> { x: number; y: number; members: T[]; name?: string }

/** 레벨별·레이어별 묶음 방식. unit=true 면 행정 단위 묶기, 아니면 radius(px) 픽셀 묶기(0 이면 안 묶음).
    관광지·착한가격·숙소(각 200~812개)는 레벨 9(4km 뷰)까지만 묶고 8부터 전부 개별 핀 - 사용자가 "여기까지만 모아서" 결정(2026-09-12).
    카페·편의점·마트·식당(698~3,039개)은 레벨 6(2km·1km 뷰)까지 묶고 5(1km 뷰)부터 개별 - 2026-09-12 저녁 결정.
    처음엔 8까지였는데 카페를 켠 2km 뷰(레벨 7) 제주시에서 개별 핀 1,443개가 떠 드래그마다 멈췄다(사용자 "끊겨").
    이름표를 빼도 핀 수 자체가 비용이라(드래그당 250ms, CSS 격리·아이콘·그림자 제거 전부 효과 0) 묶음으로만 줄일 수 있다.
    실측(카페만, 제주시): 개별 핀 레벨 7 = 1,649개 250ms/회 · 6 = 1,225개 110ms · 5 = 676개 15ms → 5부터 개별.
    묶음은 2곳 이상일 때만 만들고 혼자인 장소는 핀 그대로 둔다.
    그 아래는 아예 안 묶고 전부 개별 핀 - 겹치는 핀은 그 동네 뷰에선 같은 자리라는 뜻이라 그대로 둔다 */
const DENSE_GROUPS = new Set(['cafe', 'cvs', 'mart', 'dine'])
export function clusterModeFor (level: number, group: string = 'spot'): { unit: boolean; radius: number } {
  if (level >= 10) return { unit: true, radius: 0 }
  const minPixelLevel = DENSE_GROUPS.has(group) ? 6 : 9
  return { unit: false, radius: level >= minPixelLevel ? 40 : 0 }
}

/** 관광지가 묶이는 축소 뷰(레벨 9 이상)에선 필터 밖 흐린 핀을 숨긴다 - 섬 전체 뷰의 회색 점 600개는 정보가 아니라 잡음 */
export const hideDimFor = (level: number): boolean => level >= 9

/** 행정 단위 하나 = 묶음 하나. 위치는 구성원 평균. 단위가 없는 항목(inferUnits 뒤엔 없어야 함)은 '기타'로 */
export function clusterByUnit<T extends ClusterItem> (items: T[], unitOf: (it: T) => string | null | undefined): Cluster<T>[] {
  const by = new Map<string, T[]>()
  for (const it of items) {
    const u = unitOf(it) || '기타'
    if (!by.has(u)) by.set(u, [])
    by.get(u)!.push(it)
  }
  return [...by].map(([name, members]) => ({
    name, members,
    x: members.reduce((a, m) => a + m.px, 0) / members.length,
    y: members.reduce((a, m) => a + m.py, 0) / members.length,
  }))
}

/**
 * 픽셀 탐욕 묶기: 순서대로 보며 반경 안에 이미 묶음이 있으면 가장 가까운 데 합류(중심은 평균), 없으면 새 묶음.
 * 격자 색인(칸 = 반경)으로 근처 묶음만 대조한다 - 카페 3천 개도 수 ms. 반경 0 이면 전부 개별.
 * 1차가 끝나면 중심끼리 반경 안인 묶음을 합친다 - 탐욕 순서 탓에 서로 붙은 묶음 둘이 남는 걸 없앤다.
 */
export function clusterPins<T extends ClusterItem> (items: T[], radius: number): Cluster<T>[] {
  if (!radius) return items.map(it => ({ x: it.px, y: it.py, members: [it] }))
  const out: Cluster<T>[] = []
  const grid = new Map<string, Cluster<T>[]>()
  for (const it of items) {
    const gx = Math.floor(it.px / radius), gy = Math.floor(it.py / radius)
    let best: Cluster<T> | null = null, bd = radius
    for (let dx = -2; dx <= 2; dx++) {
      for (let dy = -2; dy <= 2; dy++) {
        const bucket = grid.get(`${gx + dx},${gy + dy}`)
        if (!bucket) continue
        for (const c of bucket) {
          const d = Math.hypot(c.x - it.px, c.y - it.py)
          if (d < bd) { bd = d; best = c }
        }
      }
    }
    if (best) {
      best.members.push(it)
      const n = best.members.length
      best.x += (it.px - best.x) / n
      best.y += (it.py - best.y) / n
    } else {
      const c: Cluster<T> = { x: it.px, y: it.py, members: [it] }
      out.push(c)
      const k = `${gx},${gy}`
      if (!grid.has(k)) grid.set(k, [])
      grid.get(k)!.push(c)
    }
  }
  return mergeClose(out, radius)
}

/** 중심 거리가 반경 안인 묶음끼리 합친다(큰 묶음이 작은 묶음을 흡수, 중심은 가중 평균). 한 번 훑어 끝 */
function mergeClose<T extends ClusterItem> (clusters: Cluster<T>[], radius: number): Cluster<T>[] {
  const sorted = [...clusters].sort((a, b) => b.members.length - a.members.length)
  const kept: Cluster<T>[] = []
  for (const c of sorted) {
    const host = kept.find(k => Math.hypot(k.x - c.x, k.y - c.y) < radius)
    if (!host) { kept.push(c); continue }
    const n = host.members.length + c.members.length
    host.x = (host.x * host.members.length + c.x * c.members.length) / n
    host.y = (host.y * host.members.length + c.y * c.members.length) / n
    host.members.push(...c.members)
  }
  return kept
}

export interface TierCounts { calm?: number; mid?: number; busy?: number; none?: number }

/** 묶음의 대표 혼잡 단계 - 예보 있는 장소(한산·보통·혼잡)만 세서 가장 많은 단계. 예보 없음은 셈에서 뺀다(끼우면 지도가 온통 회색).
    동률이면 더 붐비는 쪽 - "한산"을 과장하지 않게. 예보가 하나도 없으면 'none' (2026-09-12 디자인 A안: 단색 채움) */
export function dominantTier (counts: TierCounts): 'calm' | 'mid' | 'busy' | 'none' {
  const calm = counts.calm ?? 0, mid = counts.mid ?? 0, busy = counts.busy ?? 0
  if (!calm && !mid && !busy) return 'none'
  if (busy >= mid && busy >= calm) return 'busy'
  if (mid >= calm) return 'mid'
  return 'calm'
}

/** 묶음 지름(px): 2곳 31px 부터 개수의 로그로 커져 42px 에서 멈춘다 */
export const clusterSize = (n: number): number => Math.round(28 + Math.min(14, Math.log2(Math.max(2, n)) * 3))
