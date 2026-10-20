import { describe, expect, it } from 'vitest'
import { isWideInfo, parseInfoText } from './infoText'

describe('parseInfoText - 운영시간·휴무·이용요금 원문 정리', () => {
  it('태그가 벗겨져 한 줄로 붙은 운영시간을 항목과 비고로 되살린다 (운영 실데이터 형태)', () => {
    const info = parseInfoText('- 본관 09:00~18:00- 공공수장고 09:15~17:30※ 매표시간은 관람시간 종료 30분전까지')

    expect(info.blocks).toHaveLength(1)
    expect(info.blocks[0].title).toBeNull()
    expect(info.blocks[0].lines).toEqual([
      { text: '본관 09:00~18:00', bullet: true },
      { text: '공공수장고 09:15~17:30', bullet: true },
    ])
    expect(info.notes).toEqual(['매표시간은 관람시간 종료 30분전까지'])
  })

  it('줄바꿈이 살아 있는 원문도 같은 모양이 된다', () => {
    const info = parseInfoText('- 화요일~금요일 09:00~19:00\n- 토요일~월요일 09:00~21:00')

    expect(info.blocks[0].lines.map(l => l.text)).toEqual(['화요일~금요일 09:00~19:00', '토요일~월요일 09:00~21:00'])
    expect(info.notes).toEqual([])
  })

  it('이용요금은 [개인]·[단체] 묶음으로 나뉘고 비고는 따로 남는다', () => {
    const info = parseInfoText(
      '[개인]- 어른 2,000원- 청소년/군인 1,000원- 어린이 500원 [단체 (10인 이상)]- 어른 1,400원- 청소년/군인 700원- 어린이 300원※ 무료 - 6세 이하 / 65세 이상 / 장애인 등')

    expect(info.blocks.map(b => b.title)).toEqual(['개인', '단체 (10인 이상)'])
    expect(info.blocks[0].lines.map(l => l.text)).toEqual(['어른 2,000원', '청소년/군인 1,000원', '어린이 500원'])
    expect(info.blocks[1].lines.map(l => l.text)).toEqual(['어른 1,400원', '청소년/군인 700원', '어린이 300원'])
    expect(info.notes).toEqual(['무료 - 6세 이하 / 65세 이상 / 장애인 등'])   // 숫자 앞 '-'는 항목 표식이 아니다
    expect(isWideInfo(info)).toBe(true)
  })

  it('한 줄짜리 값과 시간 범위는 자르지 않는다', () => {
    expect(parseInfoText('상시 개방')).toEqual({ blocks: [{ title: null, lines: [{ text: '상시 개방', bullet: false }] }], notes: [] })
    expect(parseInfoText('10:00 - 18:00').blocks[0].lines).toEqual([{ text: '10:00 - 18:00', bullet: false }])
    expect(isWideInfo(parseInfoText('상시 개방'))).toBe(false)
  })

  it('휴무 문구의 ※ 부연은 비고로 내려간다', () => {
    const info = parseInfoText('매주 월요일 / 1월 1일 / 설·추석 연휴 ※ 월요일이 공휴일(대체공휴일 포함)인 경우에는 그 다음의 첫 번째 비공휴일')

    expect(info.blocks[0].lines).toEqual([{ text: '매주 월요일 / 1월 1일 / 설·추석 연휴', bullet: false }])
    expect(info.notes).toEqual(['월요일이 공휴일(대체공휴일 포함)인 경우에는 그 다음의 첫 번째 비공휴일'])
  })

  it('비고 안의 대시는 항목으로 자르지 않는다 - 무료 대상이 요금 항목으로 들어가면 거짓 정보다', () => {
    const info = parseInfoText('[개인]- 어른 2,000원※ 무료 - 국가유공자 및 동반 1인')

    expect(info.blocks[0].lines.map(l => l.text)).toEqual(['어른 2,000원'])
    expect(info.notes).toEqual(['무료 - 국가유공자 및 동반 1인'])
  })

  it('앞에 공백이 있는 대시는 부연이라 한 줄로 둔다', () => {
    expect(parseInfoText('연중무휴 - 단, 명절 휴무').blocks[0].lines).toEqual([{ text: '연중무휴 - 단, 명절 휴무', bullet: false }])
  })

  it('문장 속 대괄호는 소제목이 아니다', () => {
    const info = parseInfoText('예약은 [네이버 예약] 에서 가능합니다')

    expect(info.blocks).toEqual([{ title: null, lines: [{ text: '예약은 [네이버 예약] 에서 가능합니다', bullet: false }] }])
  })

  it('비고만 있는 값은 비어 있지 않다', () => {
    const info = parseInfoText('※ 연중무휴')

    expect(info.blocks).toEqual([])
    expect(info.notes).toEqual(['연중무휴'])
  })

  it('빈 값과 <br> 태그를 처리한다', () => {
    expect(parseInfoText(null)).toEqual({ blocks: [], notes: [] })
    expect(parseInfoText('  ')).toEqual({ blocks: [], notes: [] })
    expect(parseInfoText('평일 09:00<br>주말 10:00').blocks[0].lines.map(l => l.text)).toEqual(['평일 09:00', '주말 10:00'])
  })
})
