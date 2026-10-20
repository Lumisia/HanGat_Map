import { describe, expect, it } from 'vitest'
import { cropGeometry } from './profileCrop.js'

describe('프로필 사진 자르기 좌표', () => {
  it('가로 사진을 가운데 정사각형으로 채운다', () => {
    expect(cropGeometry(800, 400, 1, 0, 0)).toMatchObject({ sx: 200, sy: 0, side: 400, x: 0, y: 0 })
  })
  it('세로 사진도 가운데를 기준으로 채운다', () => {
    expect(cropGeometry(400, 800, 1, 0, 0)).toMatchObject({ sx: 0, sy: 200, side: 400 })
  })
  it('확대 후 이동한 영역을 원본 픽셀로 환산한다', () => {
    expect(cropGeometry(800, 400, 2, 64, -64)).toMatchObject({ sx: 250, sy: 150, side: 200 })
  })
  it('과도한 이동에도 이미지 밖을 자르지 않는다', () => {
    expect(cropGeometry(800, 400, 1, 9999, -9999)).toMatchObject({ sx: 0, sy: 0, side: 400, x: 128, y: 0 })
    expect(cropGeometry(400, 800, 1, -9999, 9999)).toMatchObject({ sx: 0, sy: 0, side: 400, x: 0, y: 128 })
  })
  it('축소 한계는 원을 채우는 크기이고 확대는 4배까지다', () => {
    expect(cropGeometry(400, 400, 0, 0, 0).side).toBe(400)
    expect(cropGeometry(400, 400, 99, 0, 0).side).toBe(100)
  })
  it.each([[0, 400], [400, 0], [NaN, 400], [5000, 5000]])('잘못되거나 너무 큰 이미지 크기는 거부한다: %s×%s', (w, h) => {
    expect(() => cropGeometry(w, h, 1, 0, 0)).toThrow()
  })
})
