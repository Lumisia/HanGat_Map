import { describe, expect, it } from 'vitest'
import { JEJU_BOUNDS, clampToJeju } from './jejuBounds'

describe('clampToJeju', () => {
  it('leaves a point inside Jeju untouched', () => {
    expect(clampToJeju(33.383, 126.55)).toEqual({ lat: 33.383, lng: 126.55 })
  })

  it('pulls a far-away center back to the nearest edge', () => {
    expect(clampToJeju(35.1, 129.0)).toEqual({ lat: JEJU_BOUNDS.north, lng: JEJU_BOUNDS.east })   // 부산 쪽
    expect(clampToJeju(32.0, 125.0)).toEqual({ lat: JEJU_BOUNDS.south, lng: JEJU_BOUNDS.west })   // 남서 바다
  })

  it('keeps the outlying islands reachable but not Chuja', () => {
    expect(clampToJeju(33.12, 126.27)).toEqual({ lat: 33.12, lng: 126.27 })   // 마라도
    expect(clampToJeju(33.50, 126.95)).toEqual({ lat: 33.50, lng: 126.95 })   // 우도
    expect(clampToJeju(33.95, 126.30).lat).toBe(JEJU_BOUNDS.north)            // 추자도는 권역 밖
  })
})
