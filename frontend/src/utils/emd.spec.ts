import { describe, expect, it } from 'vitest'
import { inferUnits, placeUnit } from './emd'

describe('placeUnit - 주소에서 묶음 단위', () => {
  it('읍·면은 그대로', () => {
    expect(placeUnit('제주특별자치도 제주시 애월읍 하귀1리 1234', null)).toBe('애월읍')
    expect(placeUnit('제주특별자치도 서귀포시 안덕면 사계리 1', '제주특별자치도 서귀포시 사계남로 1')).toBe('안덕면')
    expect(placeUnit(null, '제주특별자치도 제주시 조천읍 교래로 12')).toBe('조천읍')
  })

  it('동 지역은 시내로, 중문 7개 동은 중문으로', () => {
    expect(placeUnit('제주특별자치도 제주시 노형동 123', null)).toBe('제주시내')
    expect(placeUnit('제주특별자치도 서귀포시 서홍동 1', null)).toBe('서귀포시내')
    expect(placeUnit(null, '제주특별자치도 서귀포시 중문관광로 42 (색달동)')).toBe('중문')
    expect(placeUnit('제주특별자치도 서귀포시 대포동 5', null)).toBe('중문')
  })

  it('"일주동로"처럼 동으로 끝나는 길 이름에 속지 않고, 동이 없는 도로명 주소는 null', () => {
    expect(placeUnit(null, '제주특별자치도 제주시 일주동로 123')).toBeNull()
    expect(placeUnit(null, '제주특별자치도 서귀포시 1100로 823')).toBeNull()
    expect(placeUnit(null, null)).toBeNull()
  })
})

describe('inferUnits - 단위 없는 장소는 가장 가까운 장소의 단위', () => {
  it('가까운 쪽을 따르고, 있는 단위는 건드리지 않는다', () => {
    const items = [
      { x: 126.30, y: 33.45, unit: '애월읍' },
      { x: 126.90, y: 33.45, unit: '성산읍' },
      { x: 126.32, y: 33.46, unit: null },
      { x: 126.88, y: 33.44, unit: null },
    ]
    inferUnits(items)
    expect(items.map(i => i.unit)).toEqual(['애월읍', '성산읍', '애월읍', '성산읍'])
  })

  it('단위 있는 장소가 없으면 그대로 둔다', () => {
    const items = [{ x: 1, y: 1, unit: null }]
    inferUnits(items)
    expect(items[0].unit).toBeNull()
  })
})
