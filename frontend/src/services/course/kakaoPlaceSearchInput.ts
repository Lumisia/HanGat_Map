import { isSearchableKakaoQuery } from '../kakaoPlaceSearchService'

export function createKakaoPlaceSearchInput(
  onChange: (value: string) => void,
  onSearch: (value: string) => void,
  delayMs = 280,
) {
  let timer: ReturnType<typeof setTimeout> | undefined

  const cancel = () => {
    if (timer) clearTimeout(timer)
    timer = undefined
  }
  const schedule = (value: string) => {
    onChange(value)
    cancel()
    if (!isSearchableKakaoQuery(value)) return
    timer = setTimeout(() => onSearch(value), delayMs)
  }

  return {
    input(value: string) { schedule(value) },
    compositionStart() { cancel() },
    compositionEnd(value: string) { schedule(value) },
    dispose: cancel,
  }
}
