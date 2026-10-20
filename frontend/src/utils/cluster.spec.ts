import { describe, expect, it } from 'vitest'
import { clusterByUnit, clusterModeFor, clusterPins, clusterSize, dominantTier, hideDimFor } from './cluster'

describe('cluster - 축소 뷰 핀 묶기', () => {
  it('방식은 레벨·레이어로: 10 이상 행정 단위, 관광지·착한가격·숙소는 9까지 픽셀 40px·8부터 개별, 카페·편의점·마트·식당은 6까지 픽셀·5부터 개별', () => {
    expect(clusterModeFor(11)).toEqual({ unit: true, radius: 0 })
    expect(clusterModeFor(10, 'cafe')).toEqual({ unit: true, radius: 0 })
    expect(clusterModeFor(9)).toEqual({ unit: false, radius: 40 })
    for (const g of ['spot', 'food', 'stay']) expect(clusterModeFor(8, g)).toEqual({ unit: false, radius: 0 })
    for (const g of ['cafe', 'cvs', 'mart', 'dine']) {
      for (const lv of [8, 7, 6]) expect(clusterModeFor(lv, g)).toEqual({ unit: false, radius: 40 })   // 4km~1km 뷰 - 개별이면 끊기던 구간
      expect(clusterModeFor(5, g)).toEqual({ unit: false, radius: 0 })                                 // 1km 뷰(676개)부터 개별
    }
    expect(clusterModeFor(4)).toEqual({ unit: false, radius: 0 })
  })

  it('탐욕 순서 탓에 붙어 버린 묶음 둘은 중심이 반경 안이면 합친다', () => {
    // a 가 먼저 묶음이 되고 b 는 a 에서 50px 라 새 묶음이 되지만, 이후 구성원이 붙어 두 중심이 30px 로 가까워진다
    const pts = [{ px: 0, py: 0 }, { px: 50, py: 0 }, { px: 25, py: 0 }, { px: 30, py: 0 }, { px: 20, py: 0 }]
    const cl = clusterPins(pts, 40)
    expect(cl).toHaveLength(1)
    expect(cl[0].members).toHaveLength(5)
  })

  it('흐린 핀 숨김은 관광지가 묶이는 레벨 9 이상', () => {
    expect(hideDimFor(9)).toBe(true)
    expect(hideDimFor(8)).toBe(false)
  })

  it('행정 단위 묶기: 단위 하나에 묶음 하나, 위치는 평균, 이름은 단위', () => {
    const pts = [
      { px: 0, py: 0, unit: '애월읍' }, { px: 10, py: 20, unit: '애월읍' },
      { px: 500, py: 0, unit: '성산읍' },
      { px: 300, py: 300, unit: null },
    ]
    const cl = clusterByUnit(pts, p => p.unit)
    expect(cl.map(c => [c.name, c.members.length])).toEqual([['애월읍', 2], ['성산읍', 1], ['기타', 1]])
    expect(cl[0].x).toBe(5)
    expect(cl[0].y).toBe(10)
    expect(cl.reduce((a, c) => a + c.members.length, 0)).toBe(pts.length)
  })

  it('픽셀 묶기: 반경 안 핀은 한 묶음, 밖은 따로 - 개수 합은 항상 입력 수', () => {
    const pts = [
      { px: 0, py: 0, id: 'a' }, { px: 10, py: 0, id: 'b' }, { px: 0, py: 15, id: 'c' },   // 서로 40 안
      { px: 200, py: 200, id: 'd' },                                                       // 혼자
      { px: 500, py: 0, id: 'e' }, { px: 530, py: 0, id: 'f' },                             // 둘
    ]
    const cl = clusterPins(pts, 40).sort((p, q) => p.members[0].id.localeCompare(q.members[0].id))   // 큰 묶음이 앞에 오므로 정렬해 비교
    expect(cl.map(c => c.members.map(m => m.id))).toEqual([['a', 'b', 'c'], ['d'], ['e', 'f']])
    expect(cl.reduce((a, c) => a + c.members.length, 0)).toBe(pts.length)
    expect(cl[0].x).toBeCloseTo(10 / 3)
    expect(cl[0].y).toBeCloseTo(5)
  })

  it('확대해서 거리가 2배가 되면 같은 핀들이 저절로 갈라진다', () => {
    const pts = [{ px: 0, py: 0 }, { px: 30, py: 0 }]
    expect(clusterPins(pts, 40)).toHaveLength(1)
    expect(clusterPins(pts.map(p => ({ px: p.px * 2, py: p.py * 2 })), 40)).toHaveLength(2)
  })

  it('반경 0 이면 전부 개별, 격자 경계를 넘어도 반경 안이면 묶인다', () => {
    expect(clusterPins([{ px: 0, py: 0 }, { px: 1, py: 1 }], 0)).toHaveLength(2)
    expect(clusterPins([{ px: 39, py: 39 }, { px: 41, py: 41 }], 40)).toHaveLength(1)
  })

  it('큰 입력도 빠르다 (카페 3천 개 수준)', () => {
    const pts = Array.from({ length: 3000 }, (_, i) => ({ px: (i * 37) % 1200, py: (i * 91) % 800 }))
    const t0 = performance.now()
    const cl = clusterPins(pts, 40)
    expect(performance.now() - t0).toBeLessThan(200)
    expect(cl.reduce((a, c) => a + c.members.length, 0)).toBe(3000)
  })

  it('대표 색은 예보 있는 장소 중 다수, 예보 없음은 셈에서 빼고, 동률이면 붐비는 쪽', () => {
    expect(dominantTier({ calm: 5, mid: 1, busy: 0, none: 40 })).toBe('calm')   // 회색 40이어도 한산
    expect(dominantTier({ calm: 1, mid: 3, busy: 2 })).toBe('mid')
    expect(dominantTier({ calm: 2, mid: 2, busy: 0 })).toBe('mid')              // 동률 → 붐비는 쪽
    expect(dominantTier({ calm: 0, mid: 1, busy: 1 })).toBe('busy')
    expect(dominantTier({ none: 7 })).toBe('none')
    expect(dominantTier({})).toBe('none')
  })

  it('지름은 2곳 31px, 개수가 늘수록 커지되 42px 에서 멈춘다', () => {
    expect(clusterSize(2)).toBe(31)
    expect(clusterSize(8)).toBe(37)
    expect(clusterSize(1000)).toBe(42)
  })
})
