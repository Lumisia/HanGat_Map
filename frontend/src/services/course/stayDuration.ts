export function stayDuration(start?: string | null, end?: string | null) {
  if (!start || !end) return null
  const minutes = (value: string) => {
    if (!/^\d{2}:\d{2}(:\d{2})?$/.test(value)) return NaN
    const [h, m] = value.split(':').map(Number)
    return h < 24 && m < 60 ? h * 60 + m : NaN
  }
  const duration = minutes(end) - minutes(start)
  if (!Number.isFinite(duration) || duration <= 0) return null
  return [duration >= 60 ? `${Math.floor(duration / 60)}시간` : '', duration % 60 ? `${duration % 60}분` : ''].filter(Boolean).join(' ')
}
