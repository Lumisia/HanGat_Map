/**
 * 장소 상세 운영시간·휴무·이용요금 텍스트를 줄 단위 구조로 편다.
 *
 * TourAPI 원문은 `<br>`로 줄을 나누는데, 적재된 값은 줄바꿈이 남은 것('...19:00\n- 토요일...')과
 * 태그만 벗겨져 한 줄로 붙은 것('- 본관 09:00~18:00- 공공수장고 09:15~17:30※ 매표시간은...')이 섞여 있다.
 * 둘 다 같은 모양으로 보이도록 표식(- 항목, [묶음], ※ 비고)을 기준으로 다시 자른다. 내용은 바꾸지 않는다.
 */
export interface InfoLine {
  text: string
  /** '- '로 시작한 항목 - 점을 찍어 보여준다 */
  bullet: boolean
}

export interface InfoBlock {
  /** '[개인]'처럼 대괄호로 묶인 소제목. 없으면 null */
  title: string | null
  lines: InfoLine[]
}

export interface InfoText {
  blocks: InfoBlock[]
  /** '※'로 시작한 비고 - 본문 아래 작게 */
  notes: string[]
}

/** 대괄호 묶음 뒤에 항목('- ')·비고·줄 끝이 오면 소제목이다. '[네이버 예약] 에서'처럼 문장 속 괄호는 그대로 둔다 */
const HEADING = /\s*(\[[^\]]+\])(?=\s*(?:-\s|※|\n|$))/g
/**
 * 앞 글자에 바로 붙은 '- '(태그가 벗겨진 자리) 뒤에 숫자가 아닌 글자가 오면 항목 표식이다.
 * '10:00 - 18:00'(양쪽 공백)과 '연중무휴 - 단, 명절 휴무'(앞에 공백)는 한 줄로 둔다.
 * lookbehind는 Safari 16.3 이하가 모듈 로드부터 실패하므로 캡처로 쓴다.
 */
const GLUED_BULLET = /(\S)-\s+(?=[^\d\s])/g

/** 붙어 버린 표식 앞에 줄바꿈을 넣는다. 비고(※) 줄은 자르지 않는다 - '※ 무료 - 국가유공자'의 '-'는 항목이 아니다 */
function restoreBreaks (raw: string): string {
  return raw
    .replace(/\r/g, '')
    .replace(/<br\s*\/?>/gi, '\n')
    .replace(/\s*※/g, '\n※')
    .replace(HEADING, '\n$1')
    .split('\n')
    .map(line => (line.trimStart().startsWith('※') ? line : line.replace(GLUED_BULLET, '$1\n- ')))
    .join('\n')
}

export function parseInfoText (raw: string | null | undefined): InfoText {
  if (!raw || !raw.trim()) return { blocks: [], notes: [] }
  const blocks: InfoBlock[] = []
  const notes: string[] = []
  let current: InfoBlock | null = null

  for (const piece of restoreBreaks(raw).split('\n')) {
    const line = piece.trim()
    if (!line) continue
    if (line.startsWith('※')) {
      notes.push(line.replace(/^※\s*/, ''))
      continue
    }
    const heading = /^\[([^\]]+)\]\s*(.*)$/.exec(line)
    if (heading) {
      current = { title: heading[1].trim(), lines: [] }
      blocks.push(current)
      if (heading[2]) current.lines.push({ text: heading[2].trim(), bullet: false })
      continue
    }
    if (!current) {
      current = { title: null, lines: [] }
      blocks.push(current)
    }
    const bullet = /^-\s+/.test(line)
    current.lines.push({ text: line.replace(/^-\s+/, ''), bullet })
  }
  return { blocks, notes }
}

/** 소제목이 둘 이상이거나 줄이 많으면 한 칸에 안 들어간다 - 그리드 한 줄을 통째로 쓴다 */
export function isWideInfo (info: InfoText): boolean {
  const lineCount = info.blocks.reduce((sum, block) => sum + block.lines.length, 0)
  return info.blocks.length > 1 || lineCount > 3
}
