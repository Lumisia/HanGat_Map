/* 주소 → 묶음 단위. 섬 전체 뷰(레벨 10 이상) 묶음 핀은 "행정 단위 하나 = 묶음 하나"다 (2026-09-12 A안 - 픽셀 묶음은 같은 읍면 안에
   묶음이 여러 개 생겨 이름이 겹쳤다). 읍·면은 그대로(애월읍…), 동 지역은 시내 생활권으로 합친다 - 시내 동은 1~6곳씩 25개라
   그대로 두면 작은 묶음이 시내에 겹치고, 서귀포 중문(관광단지)은 시내에서 15km 떨어져 있어 따로 뗀다.
   이 중문 7개 동만 손으로 정한 목록이고 나머지는 주소에서 나온다.
   지번 주소를 먼저 보고(읍면동이 있다), 도로명 주소는 읍면이 적혀 있거나 괄호 안 동("(색달동)")이 있을 때만 믿는다.
   둘 다 없으면 null - 그런 곳(관광지 221)은 inferUnits 가 가장 가까운 장소의 단위를 준다.
   실측 2026-09-12 관광지 812: 제주시내 131 · 서귀포시내 91 · 애월읍 76 · 조천읍 74 · 구좌읍 64 · 안덕면 61 · 성산읍 57 · 중문 47 ·
   표선면 44 · 한경면 41 · 한림읍 40 · 남원읍 38 · 대정읍 33 · 우도면 15 (14단위, 빠짐 0) */
const EMD = /(제주시|서귀포시)\s+([^\s(]+?(?:읍|면|동))(?=\s|$)/
const PAREN = /\(([^)\s]*?동)\)/
const JUNGMUN = new Set(['색달동', '중문동', '대포동', '하원동', '회수동', '상예동', '하예동'])

export function placeUnit (lot: string | null | undefined, road: string | null | undefined): string | null {
  for (const a of [lot, road]) {
    if (!a) continue
    const m = a.match(EMD)
    const dong = m ? m[2] : a.match(PAREN)?.[1]
    if (!dong) continue
    if (/[읍면]$/.test(dong)) return dong
    if (JUNGMUN.has(dong)) return '중문'
    return a.includes('서귀포시') ? '서귀포시내' : '제주시내'
  }
  return null
}

/** 단위가 없는 장소는 가장 가까운(위경도) 단위 있는 장소의 단위를 따른다 - 제자리에서 채운다.
    경도 차에 0.83(cos 33.4°)을 곱해 실제 거리에 가깝게. 단위 있는 장소가 하나도 없으면 그대로 둔다 */
export function inferUnits<T extends { x: number; y: number; unit: string | null }> (items: T[]): void {
  const known = items.filter(p => p.unit)
  if (!known.length) return
  for (const p of items) {
    if (p.unit) continue
    let best = known[0], bd = Infinity
    for (const k of known) {
      const dx = (k.x - p.x) * 0.83, dy = k.y - p.y
      const d = dx * dx + dy * dy
      if (d < bd) { bd = d; best = k }
    }
    p.unit = best.unit
  }
}
