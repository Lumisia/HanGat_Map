/**
 * 클립보드 복사 — 성공 여부를 정직하게 돌려준다.
 *
 * Clipboard API는 포커스·권한 문제로 거부될 수 있는데, 이전 코드는
 * 거부돼도 성공 토스트를 띄웠다(실제로 붙여넣기가 안 돼서 발견된 버그).
 * 거부되면 임시 textarea + execCommand 폴백을 한 번 더 시도하고,
 * 그래도 안 되면 false — 호출부가 실패를 안내한다.
 */
export async function copyText (text) {
  try {
    if (navigator.clipboard?.writeText) {
      await navigator.clipboard.writeText(text)
      return true
    }
  } catch { /* 거부됨 - 아래 폴백으로 */ }
  try {
    const ta = document.createElement('textarea')
    ta.value = text
    ta.setAttribute('readonly', '')
    ta.style.cssText = 'position:fixed;top:-9999px;opacity:0'
    document.body.appendChild(ta)
    ta.select()
    const ok = document.execCommand('copy')
    ta.remove()
    return !!ok
  } catch {
    return false
  }
}
