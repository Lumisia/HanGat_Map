/**
 * 상세 패널 '소개' 문단 (MAP_008).
 * overview 컬럼은 관광지 소개글과 착한가격·식당 메뉴 문단("대표메뉴: …")이 같이 쓴다 -
 * 메뉴 문단은 메뉴 섹션이 표로 그리므로 소개로는 내지 않는다.
 */
const MENU_PREFIX = '대표메뉴:'

/**
 * 착한가격 메뉴 문단("대표메뉴: 흑돼지 9,000원 · 갈치조림 12,000원")의 첫 메뉴 - { n: 이름, p: '9,000원' }.
 * 테마 카드가 사진 대신 보여준다(사진 없는 착한가격 식당 271곳). 메뉴 문단이 아니면 null. 규칙은 지도 패널 menuRows 와 같다.
 */
export function firstMenu (overview) {
  const o = (overview ?? '').trim()
  if (!o.startsWith(MENU_PREFIX)) return null
  const item = o.slice(MENU_PREFIX.length).trim().split(' · ')[0]
  if (!item) return null
  const m = item.match(/^(.*?)\s*([\d,]+원)$/)
  return m ? { n: m[1], p: m[2] } : { n: item, p: '' }
}

/** 소개로 보여줄 글. 없거나 메뉴 문단이면 null */
export function introOf (overview) {
  const t = (overview ?? '').trim()
  if (!t || t.startsWith(MENU_PREFIX)) return null
  return t
}

/**
 * 소개글을 표시용 문단으로 나눈다 - 글자는 하나도 건드리지 않고(공사 원문 무수정 규정) 원문 줄바꿈과
 * 문장 끝("다." "요." "!" "?") 뒤 공백에서만 끊어 per 문장씩 묶는다. 936자가 한 덩어리로 오는 원문을
 * 읽기 쉽게 하기 위한 것(2026-09-17). 뒤를 돌아보는 정규식(lookbehind)은 iOS 16.3 이하 사파리에서
 * 문법 오류로 파일 전체가 죽으므로 쓰지 않는다. 로컬 1,783건 실측: 8자 미만 조각 0, 3문장 묶음 문단 1~5개.
 */
export function paragraphsOf (text, per = 3) {
  const t = (text ?? '').trim()
  if (!t) return []
  const out = []
  for (const block of t.split(/\r?\n+/)) {
    const sents = block.replace(/([다요]\.|[!?])\s+/g, '$1\u0000').split('\u0000').map(s => s.trim()).filter(Boolean)
    for (let i = 0; i < sents.length; i += per) out.push(sents.slice(i, i + per).join(' '))
  }
  return out
}
