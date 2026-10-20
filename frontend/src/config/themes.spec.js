import { describe, expect, it } from 'vitest'
import { buildThemes, displayName, tileCopy, themeNamesFor, placesOfTile, findTile, GROUPS } from './themes.js'

let seq = 0
const spot = (c, tc, extra = {}) => ({ id: ++seq, n: c, c, tc, cat: 'TOURIST', good: false, hg: false, closed: false, ...extra })

describe('테마 타일 (구석구석 구조, 세부분류 전부)', () => {
  it('관광공사 코드 앞 두 글자로 묶고 세부분류마다 타일 하나, 곳수는 장소 수', () => {
    const layers = { spot: [
      spot('산, 고개, 오름, 봉우리', 'NA010100'), spot('산, 고개, 오름, 봉우리', 'NA010100'),
      spot('해변. 해수욕장', 'NA010300'), spot('박물관', 'VE040100'), spot('불교', 'HS010100')
    ] }
    const groups = buildThemes(layers)
    expect(groups.map(g => g.key)).toEqual(['NA', 'VE', 'HS'])   // 비어 있는 묶음은 안 그린다
    const na = groups[0]
    expect(na.total).toBe(3)
    expect(na.tiles.map(t => [t.title, t.count])).toEqual([['오름·산', 2], ['해변·해수욕장', 1]])   // 곳수 많은 순
    expect(na.tiles[0].key).toBe('NA010100')
    expect(na.tiles[0].sample).toEqual({ id: layers.spot[0].id, layer: 'spot' })   // 묶음 머리 사진은 이 장소에서
  })

  it('예보 유무는 보지 않는다 - series 가 없어도 센다. 폐업만 뺀다', () => {
    const groups = buildThemes({ spot: [spot('폭포', 'NA010700', { series: null }), spot('폭포', 'NA010700', { closed: true })] })
    expect(groups[0].tiles[0].count).toBe(1)
  })

  it('코드가 없는 세부분류는 문화와 시설로, 이름으로 키를 만든다', () => {
    const groups = buildThemes({ spot: [spot('터널', null)] })
    expect(groups[0].key).toBe('VE')
    expect(groups[0].tiles[0].key).toBe('n-터널')
  })

  it('식당·숙소·숨은 명소는 레이어와 표시로 고른다', () => {
    const layers = {
      spot: [spot('오름', 'NA010100', { hg: true }), spot('오름', 'NA010100')],
      food: [{ c: '한식', good: true, closed: false }, { c: '분식', good: true, closed: false }, { c: '한식', good: true, closed: true }],
      dine: [{ c: '관광식당', good: false, closed: false }, { c: '일식', good: false, closed: false }],
      stay: [{ c: '호텔', tc: 'AC110100', closed: false }, { c: '펜션', tc: 'AC110300', closed: false }, { c: '정보 없음', tc: null, closed: false }]
    }
    const byKey = Object.fromEntries(buildThemes(layers).map(g => [g.key, g]))
    expect(byKey.FD.tiles.map(t => [t.title, t.count])).toEqual([['착한가격 맛집', 2], ['관광식당', 1]])   // 폐업 1곳 제외, 곳수 많은 순
    expect(byKey.ST.tiles.map(t => t.title)).toEqual(['펜션', '호텔'])   // 같은 곳수면 가나다
    expect(byKey.HG.tiles[0]).toMatchObject({ title: '숨은 명소', count: 1, special: 'hidden' })
  })

  it('화면 이름 표 - 코드표의 특이한 부호를 정리하고, 없는 이름은 그대로', () => {
    expect(displayName('산, 고개, 오름, 봉우리')).toBe('오름·산')
    expect(displayName('수목원ㆍ정원')).toBe('수목원·정원')
    expect(displayName('동굴')).toBe('동굴')
  })

  it('소개 글은 손으로 쓴 게 없으면 기본 문장', () => {
    const [na] = buildThemes({ spot: [spot('동굴', 'NA010600')] })
    const copy = tileCopy(na.tiles[0])
    expect(copy.tagline).toBe('제주의 동굴 1곳')
    expect(copy.desc).toContain('동굴')
    expect(copy.tags).toEqual(['동굴'])
    const [oreum] = buildThemes({ spot: [spot('산, 고개, 오름, 봉우리', 'NA010100')] })
    expect(tileCopy(oreum.tiles[0]).tags).toContain('오름')
  })

  it('장소의 해시태그용 테마 이름 - 세부분류 + 착한가격 + 숨은 명소', () => {
    expect(themeNamesFor({ c: '산, 고개, 오름, 봉우리', hg: true, good: false })).toEqual(['오름·산', '숨은명소'])
    expect(themeNamesFor({ c: '정보 없음', good: true })).toEqual(['착한가격'])
    expect(themeNamesFor(null)).toEqual([])
  })

  it('타일의 장소 목록 - 세부분류로 고르고 이름순, 폐업 제외. 특별 테마는 규칙으로', () => {
    const layers = {
      spot: [spot('산, 고개, 오름, 봉우리', 'NA010100', { n: '용눈이오름' }), spot('산, 고개, 오름, 봉우리', 'NA010100', { n: '다랑쉬오름' }),
        spot('산, 고개, 오름, 봉우리', 'NA010100', { n: '폐업오름', closed: true }), spot('해변. 해수욕장', 'NA010300', { n: '협재해수욕장' })],
      food: [{ id: 9, n: '착한집', c: '한식', good: true, closed: false }, { id: 10, n: '일반집', c: '한식', good: false, closed: false }]
    }
    const groups = buildThemes(layers)
    const oreum = findTile(groups, 'NA010100')
    expect(oreum.group.key).toBe('NA')
    expect(placesOfTile(layers, oreum.tile).map(p => p.n)).toEqual(['다랑쉬오름', '용눈이오름'])
    expect(placesOfTile(layers, findTile(groups, 'goodprice').tile).map(p => p.n)).toEqual(['착한집'])
    expect(findTile(groups, '없는키')).toBeNull()
    expect(placesOfTile(layers, null)).toEqual([])
  })

  it('묶음 순서는 자연부터 한갓지도가 고른 명소까지 열 개', () => {
    expect(GROUPS.map(g => g.key)).toEqual(['NA', 'VE', 'HS', 'EX', 'LS', 'EV', 'AC', 'FD', 'ST', 'HG'])
  })
})
