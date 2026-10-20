/** 256 논리 좌표의 미리보기와 원본 픽셀의 자르기 좌표를 같은 계산으로 맞춘다. */
export function cropGeometry (width, height, zoom, x, y) {
  if (![width, height].every(n => Number.isFinite(n) && n > 0) || width * height > 20_000_000) {
    throw new Error('사진은 2천만 픽셀 이하로 선택해 주세요.')
  }
  const scale = 256 / Math.min(width, height) * Math.max(1, Math.min(4, zoom))
  const limitX = (width * scale - 256) / 2
  const limitY = (height * scale - 256) / 2
  x = Math.max(-limitX, Math.min(limitX, x)) || 0
  y = Math.max(-limitY, Math.min(limitY, y)) || 0
  const side = 256 / scale
  return { x, y, scale, side, sx: (width - side) / 2 - x / scale, sy: (height - side) / 2 - y / scale }
}
