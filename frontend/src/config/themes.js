/**
 * 테마 정의 - 테마 페이지(/themes)와 장소 소개 페이지의 해시태그가 함께 읽는다.
 *
 * 구석구석(visitkorea) 테마 페이지 구조를 우리 데이터로 옮긴 것(2026-09-17).
 * 처음엔 손으로 고른 8개였는데 "지도 종류 목록에 있는 세부분류를 전부, 예보 없는 곳도" 로 바꿨다(사용자 결정).
 * 혼잡도가 아니라 장소 자체를 소개하는 페이지라 예보 유무는 보지 않는다.
 *
 * 묶음은 관광공사 분류 코드(tagCode) 앞 두 글자로 정한다 - NA 자연 · VE 문화와 시설 · HS 역사 · EX 체험 ·
 * LS 레저 · EV 축제와 행사 · AC 캠핑. 운영 관광지 812곳·세부분류 110종이 이 일곱 코드에 전부 들어간다(2026-09-17 실측).
 * 이름 표를 110개 손으로 쓰지 않는 이유: 관광공사가 세부분류를 추가해도 자동으로 제자리에 들어가게.
 * 세부분류 이름은 코드표 그대로라 부호가 특이하다("산, 고개, 오름, 봉우리", "해변. 해수욕장") - DISPLAY 로 보기 좋게 바꾼다.
 *
 * 관광지 밖 묶음 셋은 레이어·표시로 고른다: 식당(착한가격·관광식당), 숙소(숙소 세부분류), 한갓지도가 고른 명소(숨은 명소).
 */

/** 코드 앞 두 글자 → 묶음. 순서가 화면 순서. tint 는 묶음 아이콘 동그라미의 옅은 배경색 */
export const GROUPS = [
  { key: 'NA', tint: '#dff3e6', title: '자연', emoji: '🌿', desc: '오름과 바다, 숲과 계곡. 제주가 원래 가진 것들' },
  { key: 'VE', tint: '#e3ecfb', title: '문화와 시설', emoji: '🏛️', desc: '박물관·미술관·공원처럼 사람이 지어 둔 볼거리' },
  { key: 'HS', tint: '#f6ebdc', title: '역사', emoji: '🏯', desc: '절과 사당, 성곽과 옛집' },
  { key: 'EX', tint: '#fff0d6', title: '체험', emoji: '🧺', desc: '농장·목장·마을에서 직접 해 보는 여행' },
  { key: 'LS', tint: '#e0eff8', title: '레저와 스포츠', emoji: '🏇', desc: '승마·골프·카트·바다 위 놀이' },
  { key: 'EV', tint: '#fbe6ee', title: '축제와 행사', emoji: '🎉', desc: '때를 맞춰 가야 볼 수 있는 것들' },
  { key: 'AC', tint: '#e6f3dc', title: '캠핑', emoji: '⛺', desc: '야영장·카라반·글램핑' },
  { key: 'FD', tint: '#fde9e3', title: '식당', emoji: '🍽️', desc: '가격이 정직한 집과 관광공사가 고른 식당' },
  { key: 'ST', tint: '#ece7f9', title: '숙소', emoji: '🛏️', desc: '호텔·펜션·게스트하우스' },
  { key: 'HG', tint: '#e2f2ef', title: '한갓지도가 고른 명소', emoji: '🔍', desc: '등록돼 있지만 아직 덜 알려진 곳' }
]

/** 코드표 이름 → 화면 이름. 없는 건 그대로 쓴다 */
export const DISPLAY = {
  '산, 고개, 오름, 봉우리': '오름·산',
  '해변. 해수욕장': '해변·해수욕장',
  '항구/포구': '항구·포구',
  '수목원ㆍ정원': '수목원·정원',
  '기타자연관광': '자연 관광',
  '기타자연생태': '자연 생태',
  '희귀동.식물': '희귀 동식물',
  '미술관/화랑': '미술관·화랑',
  '골목길, 문화거리': '골목길·문화거리',
  '기타문화시설': '문화시설',
  '타워 / 전망대': '타워·전망대',
  '다리 / 대교': '다리·대교',
  '스포츠센터, 수련시설': '스포츠센터',
  '수족관 / 아쿠라리움': '수족관',
  '기타역사유적지': '역사 유적',
  '성ㆍ산성ㆍ성곽': '성·산성',
  '탑ㆍ비석ㆍ기념탑': '탑·비석',
  '기타체험관광': '체험 관광',
  '기타산업관광지': '산업 관광',
  '유람선/잠수함관광': '유람선·잠수함',
  '화장품/주류/먹거리': '특산 먹거리·주류',
  '기타웰니스': '웰니스',
  '기타공예체험': '공예 체험',
  '친환경/신재생에너지': '신재생 에너지',
  '게임 등 첨단IT산업': '첨단 IT',
  '온천 / 사우나 / 스파': '온천·사우나·스파',
  '기타육상레저스포츠': '육상 레저',
  '기타수상레저스포츠': '수상 레저',
  '카약/카누': '카약·카누',
  '스노쿨링/스킨스쿠버다이빙': '스노클링·다이빙',
  '윈드서핑/제트스키': '윈드서핑·제트스키',
  '기타행사': '행사'
}

/** 자주 열리는 테마의 소개 글. 없는 테마는 기본 문장을 쓴다 */
export const COPY = {
  '산, 고개, 오름, 봉우리': { tagline: '제주의 능선을 걷는 가장 쉬운 방법', desc: '제주에는 360개가 넘는 오름이 있어요. 정상까지 30분이면 닿는 곳이 대부분이라 아침 산책처럼 오를 수 있고, 위에서 보는 밭담과 바다는 어느 오름이든 다릅니다.', tags: ['오름', '능선', '일출', '억새', '산책'] },
  '해변. 해수욕장': { tagline: '물놀이가 아니어도 충분한 물가', desc: '에메랄드빛 해수욕장부터 검은 모래 해변까지. 걷고, 앉고, 바라보는 것만으로도 좋은 곳들이에요.', tags: ['해변', '해수욕장', '노을', '물놀이'] },
  '항구/포구': { tagline: '배가 드나드는 작은 마을의 앞마당', desc: '큰 항구보다 작은 포구가 많아요. 등대와 방파제, 갓 잡은 생선을 파는 가게가 같이 있습니다.', tags: ['포구', '등대', '방파제', '어촌'] },
  '수목원ㆍ정원': { tagline: '천천히 걷고 오래 머무는 초록', desc: '비가 와도 괜찮고, 아이와 어르신이 함께 걷기에도 무리가 없는 곳들이에요.', tags: ['수목원', '정원', '숲길', '산책'] },
  '박물관': { tagline: '비 오는 날의 제주', desc: '제주 역사와 자연을 다루는 박물관들이에요. 날씨가 도와주지 않는 날에 좋습니다.', tags: ['박물관', '실내', '비오는날', '아이와함께'] },
  '미술관/화랑': { tagline: '작은 전시가 많은 섬', desc: '제주에 터를 잡은 작가들의 작은 미술관과 화랑이 곳곳에 있어요.', tags: ['미술관', '전시', '실내'] },
  '테마파크': { tagline: '하루를 통째로 쓰는 곳', desc: '입장료가 있는 대신 볼거리와 놀 거리가 한곳에 모여 있어요. 방문 정보의 요금과 운영시간을 먼저 확인하세요.', tags: ['테마파크', '아이와함께', '체험'] },
  '체험농장': { tagline: '감귤 따기부터 말 먹이 주기까지', desc: '보는 여행 말고 하는 여행이에요. 예약이 필요한 곳은 방문 정보의 전화번호로 먼저 확인하세요.', tags: ['체험', '농장', '감귤', '아이와함께'] },
  '승마': { tagline: '제주 조랑말 등에서 보는 오름', desc: '초보자도 탈 수 있는 짧은 코스가 많아요.', tags: ['승마', '조랑말', '체험'] },
  '불교': { tagline: '오름 아래 조용한 절', desc: '제주의 절은 산속보다 오름 자락과 바닷가에 많아요. 관람보다 산책에 가깝습니다.', tags: ['사찰', '불교', '고요한'] },
  '일반야영장': { tagline: '바다 앞에 텐트 치기', desc: '해변과 오름 옆 야영장이에요. 예약제와 선착순이 섞여 있으니 방문 정보를 확인하세요.', tags: ['캠핑', '야영장', '바다'] }
}

/** 관광지 밖 특별 테마 - 세부분류가 아니라 레이어·표시로 고른다 */
export const SPECIAL = [
  { key: 'goodprice', group: 'FD', title: '착한가격 맛집', layer: 'food', pick: p => !!p.good,
    tagline: '가격표가 정직한 집', desc: '행정안전부가 지정한 착한가격업소예요. 대표 메뉴와 가격이 함께 실려 있어 들어가기 전에 얼마인지 알 수 있습니다.', tags: ['착한가격', '맛집', '가성비'] },
  { key: 'tourfood', group: 'FD', title: '관광식당', layer: 'dine', pick: p => p.c === '관광식당',
    tagline: '관광공사가 관광정보로 등록한 식당', desc: '사진과 소개가 있는 식당이에요. 착한가격은 아니지만 관광객이 찾기 쉬운 곳들입니다.', tags: ['식당', '관광식당'] },
  { key: 'hidden', group: 'HG', title: '숨은 명소', layer: 'spot', pick: p => !!p.hg,
    tagline: '아직 덜 알려진 곳부터', desc: '관광공사에 등록돼 있지만 집중률 집계 대상에는 들지 않는, 아직 덜 알려진 관광지예요. 사진과 소개가 갖춰진 곳만 골랐습니다.', tags: ['숨은명소', '한적한', '분산여행'] }
]

/** 화면 이름 */
export const displayName = raw => DISPLAY[raw] ?? raw

/** URL 에 쓰는 짧은 키 - 코드가 있으면 코드, 없으면 이름 */
const keyOf = (code, name) => code || `n-${name}`

/**
 * 레이어 데이터로 테마 타일을 만든다. 예보 유무는 보지 않는다.
 * @param {{ spot?: object[], dine?: object[], food?: object[], stay?: object[] }} layers MapPlace 배열들(없는 레이어는 건너뜀)
 * @returns {{ key: string, title: string, emoji: string, desc: string, total: number, tiles: Tile[] }[]}
 *   Tile = { key, title, raw, code, group, count, tagline, desc, tags, layer, special?, sample }
 *   sample = { id, layer } 그 타일의 첫 장소 - 묶음 머리 사진용(가장 곳수 많은 타일의 첫 장소 상세를 받아 사진을 깐다)
 */
export function buildThemes (layers) {
  const byGroup = new Map(GROUPS.map(g => [g.key, new Map()]))
  const add = (groupKey, tile) => {
    const m = byGroup.get(groupKey); if (!m) return
    const cur = m.get(tile.key)
    if (cur) cur.count += tile.count; else m.set(tile.key, tile)
  }
  // 관광지: 코드 앞 두 글자로 묶고 세부분류마다 타일 하나
  for (const p of layers.spot ?? []) {
    if (p.closed || !p.c) continue
    const code = p.tc ?? null, group = code ? code.slice(0, 2) : 'VE'
    add(group, { key: keyOf(code, p.c), title: displayName(p.c), raw: p.c, code, group, count: 1, layer: 'spot', sample: { id: p.id, layer: 'spot' }, ...(COPY[p.c] ?? {}) })
  }
  // 숙소: 세부분류(호텔·펜션…)마다 타일 하나
  for (const p of layers.stay ?? []) {
    if (p.closed || !p.c || p.c === '정보 없음') continue
    add('ST', { key: keyOf(p.tc, p.c), title: displayName(p.c), raw: p.c, code: p.tc ?? null, group: 'ST', count: 1, layer: 'stay', sample: { id: p.id, layer: 'stay' } })
  }
  // 특별 테마
  for (const s of SPECIAL) {
    const rows = (layers[s.layer] ?? []).filter(p => !p.closed && s.pick(p))
    if (rows.length) add(s.group, { key: s.key, title: s.title, raw: s.title, code: null, group: s.group, count: rows.length, layer: s.layer, special: s.key, sample: { id: rows[0].id, layer: s.layer }, tagline: s.tagline, desc: s.desc, tags: s.tags })
  }
  return GROUPS.map(g => {
    const tiles = [...byGroup.get(g.key).values()].sort((a, b) => b.count - a.count || a.title.localeCompare(b.title, 'ko'))
    return { ...g, total: tiles.reduce((n, t) => n + t.count, 0), tiles }
  }).filter(g => g.tiles.length)
}

/**
 * 타일에 속한 장소들 - 이름순. 관광지·숙소는 세부분류 이름으로, 특별 테마는 그 규칙으로 고른다. 폐업은 뺀다.
 * 정렬이 이름순인 이유: 장소 자체를 소개하는 페이지라 혼잡·인기 순위를 매기지 않는다(2026-09-17 사용자 결정)
 */
export function placesOfTile (layers, tile) {
  if (!tile) return []
  let rows
  if (tile.special) {
    const s = SPECIAL.find(x => x.key === tile.special)
    rows = s ? (layers[s.layer] ?? []).filter(s.pick) : []
  } else {
    rows = (layers[tile.layer] ?? []).filter(p => p.c === tile.raw)
  }
  return rows.filter(p => !p.closed).sort((a, b) => a.n.localeCompare(b.n, 'ko'))
}

/** URL 키로 타일 찾기 - buildThemes 결과에서. 없으면 null */
export function findTile (groups, key) {
  for (const group of groups) {
    const tile = group.tiles.find(t => t.key === key)
    if (tile) return { group, tile }
  }
  return null
}

/** 타일의 소개 글 - 손으로 쓴 게 없으면 기본 문장 */
export function tileCopy (tile) {
  return {
    tagline: tile.tagline ?? `제주의 ${tile.title} ${tile.count}곳`,
    desc: tile.desc ?? `관광공사 관광정보에 ${tile.title}(으)로 등록된 장소 ${tile.count}곳이에요. 사진과 소개, 운영시간을 한 곳씩 볼 수 있습니다.`,
    tags: tile.tags ?? [tile.title.replace(/[·\s]/g, '')]
  }
}

/**
 * 장소가 속한 테마 타일 이름들(소개 페이지 해시태그용). 세부분류 하나 + 특별 테마.
 * @param {{ c?: string, hg?: boolean, good?: boolean }} place MapPlace 모양
 */
export function themeNamesFor (place) {
  const out = []
  if (place?.c && place.c !== '정보 없음') out.push(displayName(place.c))
  if (place?.good) out.push('착한가격')
  if (place?.hg) out.push('숨은명소')
  return out
}
