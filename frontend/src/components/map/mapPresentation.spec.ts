import { describe, expect, it } from 'vitest'
import { readFileSync } from 'node:fs'

import { MAP_LABEL_MAX_LEVEL, POI_GROUPS, POI_MARKER_CLASS, SPOT_ICON_GROUPS, shouldShowMapLabels, spotIconGroup, spotPinSpec } from './mapPresentation'

const mapCss = readFileSync(new URL('../../assets/styles/hangat.css', import.meta.url), 'utf8')
const sharedCss = readFileSync(new URL('../../assets/styles.css', import.meta.url), 'utf8')
const filterPanelSource = readFileSync(new URL('./FilterPanel.vue', import.meta.url), 'utf8')
const kakaoMapSource = readFileSync(new URL('./KakaoMap.vue', import.meta.url), 'utf8')
const placeDetailSource = readFileSync(new URL('./PlaceDetail.vue', import.meta.url), 'utf8')

const mobileCss = mapCss.slice(mapCss.lastIndexOf('@media(max-width:768px)'))

function declarations(selector: string) {
  const escaped = selector.replace(/[.*+?^${}()|[\]\\]/g, '\\$&')
  return mobileCss.match(new RegExp(`${escaped}\\s*\\{([^}]*)\\}`))?.[1] ?? ''
}

describe('mapPresentation', () => {
  it('상세 지도 수준에서만 장소 이름을 표시한다', () => {
    expect(MAP_LABEL_MAX_LEVEL).toBe(7)
    expect(shouldShowMapLabels(7)).toBe(true)
    expect(shouldShowMapLabels(8)).toBe(false)
  })

  it('각 POI 카테고리에 고유한 마커 클래스를 제공한다', () => {
    expect(POI_MARKER_CLASS).toEqual({
      food: 'mk-food',
      dine: 'mk-dine',
      cafe: 'mk-cafe',
      cvs: 'mk-cvs',
      stay: 'mk-stay',
      mart: 'mk-mart',
    })
  })

  it('업종 레이어 키는 마커 클래스 표와 같은 순서다', () => {
    expect(POI_GROUPS).toEqual(['food', 'dine', 'cafe', 'cvs', 'stay', 'mart'])
  })

  describe('spotPinSpec - 관광지 핀 모습(핀 재사용의 변경 감지 기준)', () => {
    it('선택·코스 정류지는 24px 최상단, 필터 안은 18px, 필터 밖은 9px 흐림 (2026-09-12 20/15 → 24/18)', () => {
      expect(spotPinSpec('busy', true, true)).toMatchObject({ cls: 'pn busy ic-mt pick', size: 24, z: 400 })
      expect(spotPinSpec('calm', false, true)).toMatchObject({ cls: 'pn calm ic-mt', size: 18, z: 200 })
      expect(spotPinSpec('mid', false, false)).toMatchObject({ cls: 'pn mid ic-mt dim', size: 9, z: 100 })
    })

    it('업종 묶음은 필터 안 관광지 핀(200)보다 아래, 흐린 관광지(100)·업종 낱개 핀(60)보다 위 - 겹치면 관광지 우선(MAP-04, #31)', () => {
      const z = +(mapCss.match(/\.cl\.poi\{z-index:(\d+)/)?.[1] ?? 0)
      expect(z).toBeLessThan(spotPinSpec('calm', false, true).z)
      expect(z).toBeGreaterThan(spotPinSpec('mid', false, false).z)
    })

    it('아이콘 묶음이 클래스에 들어가고 서명에도 반영된다', () => {
      expect(spotPinSpec('calm', false, true, 'sea').cls).toBe('pn calm ic-sea')
      expect(spotPinSpec('calm', false, true, 'sea').sig).not.toBe(spotPinSpec('calm', false, true, 'mt').sig)
    })

    it('찜한 장소는 fav 클래스가 붙고(아이콘만 ♥, 색·크기는 그대로) 찜/해제가 서명에 반영된다 (MAP_009, 2026-09-14)', () => {
      const fav = spotPinSpec('mid', false, true, 'mt', true), plain = spotPinSpec('mid', false, true, 'mt', false)
      expect(fav.cls).toBe('pn mid ic-mt fav')
      expect(fav).toMatchObject({ size: 18, z: 200 })          // 크기·층은 찜과 무관
      expect(fav.sig).not.toBe(plain.sig)                       // 찜 토글 때 핀이 다시 그려지는 근거
      expect(spotPinSpec('busy', true, true, 'sea', true).cls).toBe('pn busy ic-sea pick fav')
    })

    it('혼잡 단계만 달라져도 서명이 달라진다 - 날짜 이동 때 색이 갱신되는 근거', () => {
      expect(spotPinSpec('calm', false, true).sig).not.toBe(spotPinSpec('busy', false, true).sig)
      expect(spotPinSpec('calm', false, true).sig).toBe(spotPinSpec('calm', false, true).sig)
    })
  })

  it('핀 래퍼는 크기가 고정이고 풀 핀은 레이어 안 절대배치, 숨김·이름표 숨김은 클래스로 - 핀 재사용 전제', () => {
    expect(mapCss).toMatch(/\.pw\{[^}]*width:20px;height:20px/)
    expect(mapCss).toContain('#map .pw .lb-t.off{display:none}')
    // 카페·편의점·숙소·마트는 이름표 없음(2026-09-12), 선택 핀 이름표는 예외
    expect(mapCss).toContain('#map .pw:has(.mk-cafe,.mk-cvs,.mk-stay,.mk-mart) .lb-t:not(.sel-on){display:none}')
    expect(mapCss).toMatch(/\.pl-layer\{[^}]*width:0;height:0/)
    expect(mapCss).toMatch(/\.pw\.pl\{position:absolute;margin:-10px 0 0 -10px/)
    expect(mapCss).toContain('.pw.hid{display:none}')
  })

  describe('spotIconGroup - 관광지 아이콘 7묶음(관광공사 분류 코드 앞자리)', () => {
    it('앞 4자리 우선, 없으면 앞 2자리, 모르면 산 - 미분류가 없다', () => {
      expect(spotIconGroup('NA010100')).toBe('mt')       // 산·오름
      expect(spotIconGroup('NA020700')).toBe('sea')      // 항구
      expect(spotIconGroup('NA040700')).toBe('park')     // 수목원
      expect(spotIconGroup('VE030100')).toBe('park')     // 시민공원
      expect(spotIconGroup('VE070100')).toBe('culture')  // 박물관
      expect(spotIconGroup('VE990000')).toBe('culture')  // 처음 보는 문화시설 하위
      expect(spotIconGroup('HS030100')).toBe('history')  // 불교
      expect(spotIconGroup('LS010700')).toBe('leisure')  // 승마
      expect(spotIconGroup('EX030300')).toBe('play')     // 체험농장
      expect(spotIconGroup('VE020100')).toBe('play')     // 테마파크
      expect(spotIconGroup('EV010200')).toBe('play')     // 축제
      expect(spotIconGroup('AC050100')).toBe('play')     // 야영장
      expect(spotIconGroup(null)).toBe('mt')
      expect(spotIconGroup('ZZ000000')).toBe('mt')
    })

    it('산을 뺀 6묶음은 CSS 에 마스크 아이콘 규칙이 있다(산은 .pn 기본값)', () => {
      expect(SPOT_ICON_GROUPS).toHaveLength(7)
      for (const g of SPOT_ICON_GROUPS.filter(g => g !== 'mt')) {
        expect(mapCss).toMatch(new RegExp(`\\.pn\\.ic-${g}\\{--ico:url\\("data:image/svg\\+xml,`))
      }
    })
  })

  it('핀 아이콘: 관광지(.pn)와 업종 마커 전부 마스크 아이콘이 있고, 흐린 9px 핀엔 없다', () => {
    expect(mapCss).toContain('.pn.dim::after{display:none}')
    for (const cls of ['pn', ...Object.values(POI_MARKER_CLASS)]) {
      expect(mapCss).toMatch(new RegExp(`\\.${cls}\\{--ico:url\\("data:image/svg\\+xml,`))
    }
  })

  it('축소 상태를 우회해 이름을 표시하는 hover 규칙이 없다', () => {
    expect(mapCss).not.toContain('.pw:hover .poi-label')
  })

  it('모바일 지도에서 캘린더는 위에 있고 장소 찾기는 원형 버튼에서 가운데 모달로 열린다 (2026-09-14)', () => {
    expect(declarations('.slid')).toContain('top:12px')
    expect(declarations('.slid')).toContain('bottom:auto')
    // 모달: 위아래·좌우 여백으로 높이가 확정돼야 목록(flex:1)이 남는 공간을 채운다 - max-height 만 있으면 88px 로 굳는다(#58)
    expect(declarations('.cond')).toContain('top:24px')
    expect(declarations('.cond')).toContain('bottom:24px')
    expect(declarations('.cond')).toContain('left:14px')
    expect(declarations('.cond')).not.toContain('max-height')
    expect(declarations('.cond')).toContain('translateY(')
    expect(declarations('.cond.mobile-open')).toContain('translateY(0)')
    expect(declarations('.cond-dim')).toContain('backdrop-filter')
    // 상세(.pop)도 같은 가운데 모달 + 뒤 배경(.pop-dim). 코스 패널(.panel)만 아래 시트(손잡이 바)
    expect(declarations('.pop')).toContain('top:24px')
    expect(declarations('.pop')).not.toContain('max-height')
    expect(declarations('.pop-dim')).toContain('backdrop-filter')
    expect(mobileCss).not.toContain('.pop::before')
    expect(declarations('.panel::before')).toContain('width:42px')
    expect(mapCss).toContain('.cond-dim,.pop-dim{display:none}')   // 데스크톱은 배경 없음
    expect(declarations('.filter-fab')).toContain('display:flex')
    expect(mobileCss).not.toContain('.stage.sheet-open .slid')
  })

  it('모바일 모달은 목록이 먼저 보이고 조건은 칩 4개를 눌러 하나씩 펼친다 (시안 A)', () => {
    expect(filterPanelSource).toContain('class="mdrop" role="group"')
    for (const k of ['sort', 'reg', 'layer', 'cat']) expect(filterPanelSource).toContain(`toggleDrop('${k}')`)
    expect(filterPanelSource.indexOf('<SearchBox')).toBeLessThan(filterPanelSource.indexOf('class="mdrop"'))   // 검색창 → 칩 → 목록
    expect(declarations('#cond-body :is(.seg,.chips,.ftr-wrap,.catsel)')).toContain('display:none')          // 펼치기 전엔 조건 UI 없음
    expect(declarations('#cond-body.open-layer .ftr-wrap')).toContain('display:flex')
    expect(declarations('#cond-body.open-cat .catsel')).toContain('display:block')
    expect(mobileCss).not.toContain('.mtabs')                                                                  // 탭은 없다
    expect(mapCss).toContain('.filter-fab,.mobile-filter-head,.mdrop,.sect .cnt,.cond-dim,.pop-dim{display:none}')      // 데스크톱에선 폰 전용 요소가 안 보인다
  })

  it('모바일에서 토스트·장소 데이터 배너·닫기 버튼이 탭바·날짜 버튼·손가락과 겹치지 않는다 (#53·#55·#56·#57)', () => {
    expect(declarations('.toast')).toContain('bottom:calc(var(--mobile-tabbar-h) + 84px)')   // 탭바 + 돋보기 버튼 위
    expect(declarations('.map-offline')).toContain('top:62px')                              // 날짜 버튼(12+42) 아래 8px
    expect(declarations('.stage.sheet-open .map-offline')).toContain('display:none')
    expect(declarations('.pox::before,.ph .x::before')).toContain('inset:-6px')                  // 20×21 → 32×33, 자리는 그대로
    expect(declarations('.pi .cp::before,.intro-more::before')).toContain('inset:-8px -6px')
    expect(mobileCss).not.toContain('.pox,.ph .x{min-width')                                     // 제목 칸을 줄이는 방식은 쓰지 않는다
    expect(mapCss).toContain('.wxc{flex:0 0 auto;width:58px;')                              // 헤더 높이 토큰에 묶이지 않는다
    expect(mapCss).not.toContain('width:var(--nav-h)')
  })

  it('상세 공유 버튼은 아이콘이지만 낭독기·툴팁엔 "공유하기"로 읽힌다 (2026-09-15)', () => {
    expect(placeDetailSource).toContain('class="share" :aria-expanded="shareOpen" aria-label="공유하기" title="공유하기"')
    expect(placeDetailSource).not.toMatch(/toggleShare">공유하기</)
    expect(mapCss).toContain('.share{display:inline-flex')
  })

  it('모바일 검색 버튼과 바텀시트 닫기 버튼이 접근 가능한 상태를 알린다', () => {
    expect(filterPanelSource).toContain('class="filter-fab"')
    expect(filterPanelSource).toContain(':aria-expanded="mobileOpen"')
    expect(filterPanelSource).toContain('aria-controls="mobile-map-filter-sheet"')
    expect(filterPanelSource).toContain('id="mobile-map-filter-sheet"')
    expect(filterPanelSource).toContain('class="mobile-filter-close"')
  })

  it('선택한 찜 장소를 이름 말풍선과 강조 아이콘으로 지도 위에 표시한다', () => {
    expect(kakaoMapSource).toContain("'kakao-selected-place'")
    expect(kakaoMapSource).toContain("'kakao-selected-place__label'")
    expect(kakaoMapSource).toContain("'kakao-selected-place__pin'")
    expect(kakaoMapSource).toContain('map.panTo(selectedPosition)')
    expect(sharedCss).toContain('.kakao-selected-place__label')
    expect(sharedCss).toContain('.kakao-selected-place__pin')
    expect(sharedCss).toMatch(/\.kakao-selected-place__pin\{[^}]*display:block/)
  })
})
