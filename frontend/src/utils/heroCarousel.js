export function nextHeroIndex (currentIndex, total, readyIndices) {
  if (total <= 0) return 0
  // 준비된 사진이 없으면 지금 사진을 유지한다.
  for (let offset = 1; offset < total; offset++) {
    const next = (currentIndex + offset) % total
    if (!readyIndices || readyIndices.has(next)) return next
  }
  return currentIndex
}
