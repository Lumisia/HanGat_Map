export const MAP_LABEL_MAX_LEVEL = 7

export const shouldShowMapLabels = (level: number) => level <= MAP_LABEL_MAX_LEVEL

export const POI_MARKER_CLASS = {
  food: 'mk-food',
  dine: 'mk-dine',
  cafe: 'mk-cafe',
  cvs: 'mk-cvs',
  stay: 'mk-stay',
  mart: 'mk-mart',
} as const

export type PoiGroup = keyof typeof POI_MARKER_CLASS

/** 장소 업종 코드 → 지도 업종 핀 클래스. 착한가격 식당은 분홍(mk-food), 관광지·모르는 업종은 null(혼잡 색 점을 쓴다).
    코스 패널·검색 결과가 지도 핀과 같은 모양으로 업종을 보여줄 때 쓴다(2026-09-14) */
const CAT_MARKER_CLASS: Record<string, string> = { FOOD: 'mk-dine', CAFE: 'mk-cafe', LODGING: 'mk-stay', CONVENIENCE: 'mk-cvs', MART: 'mk-mart' }
export function poiMarkerClass (o?: { cat?: string | null, good?: boolean | null } | null): string | null {
  if (!o?.cat) return null
  return o.cat === 'FOOD' && o.good ? 'mk-food' : (CAT_MARKER_CLASS[o.cat] ?? null)
}

/** 업종 레이어 키 - MapCanvas 가 이 순서로 핀을 적용한다 */
export const POI_GROUPS = Object.keys(POI_MARKER_CLASS) as PoiGroup[]

/**
 * 관광지 핀 아이콘 묶음 7개 (2026-09-12 결정). 관광공사 분류 코드(태그 코드) 앞자리로 나눈다.
 * 대분류(앞 2자리): NA 자연 · VE 문화시설 · HS 역사 · EX 체험 · LS 레저스포츠 · EV 행사 · AC 숙박(야영).
 * 자연과 문화시설은 하위 4자리로 더 나누고, 처음 보는 VE 하위는 문화·전시, 그 밖의 모르는 코드는 산으로 보낸다 -
 * 코드상 미분류가 없다(실측 2026-09-12: 활성 관광지 812곳 전부 배정, 산 118·바다 100·공원 113·문화 139·역사 60·레저 109·체험 173).
 */
export const SPOT_ICON_GROUPS = ['mt', 'sea', 'park', 'culture', 'history', 'leisure', 'play'] as const
export type SpotIconGroup = typeof SPOT_ICON_GROUPS[number]

const SPOT_ICON_BY_PREFIX4: Record<string, SpotIconGroup> = {
  NA01: 'mt',        // 산·고개·오름·숲·계곡·폭포
  NA02: 'sea',       // 항구·해변·해안절경·섬·호수
  NA03: 'park',      // 동굴·기암괴석
  NA04: 'park',      // 수목원·생태관광지·휴양림·도립공원
  NA05: 'park',      // 기타자연관광
  VE03: 'park',      // 시민공원·주제공원
  VE02: 'play',      // 테마파크·워터파크·동물원
}
const SPOT_ICON_BY_PREFIX2: Record<string, SpotIconGroup> = {
  NA: 'mt', VE: 'culture', HS: 'history', LS: 'leisure', EX: 'play', EV: 'play', AC: 'play',
}

export function spotIconGroup (tagCode: string | null | undefined): SpotIconGroup {
  if (!tagCode) return 'mt'
  return SPOT_ICON_BY_PREFIX4[tagCode.slice(0, 4)] ?? SPOT_ICON_BY_PREFIX2[tagCode.slice(0, 2)] ?? 'mt'
}

/**
 * 관광지 핀의 모습 - 클래스·지름·z 를 상태(혼잡 단계·선택/코스·필터 안·아이콘 묶음)에서 정한다.
 * 순수 함수로 뺀 이유: MapCanvas 는 `sig` 가 지난번과 같으면 DOM 을 건드리지 않는다(핀 재사용).
 * 그래서 "어떤 상태 조합이 같은 모습인가"를 스펙으로 고정해 둔다.
 */
/** fav: 찜한 장소 - 원 색·크기는 그대로 두고 안쪽 아이콘만 ♥ 로(CSS .fav 가 --ico 를 덮어쓴다). 혼잡 색을 잃지 않으면서 내가 찜한 곳만 구분된다(2026-09-14) */
export function spotPinSpec (tier: string, pick: boolean, on: boolean, icon: SpotIconGroup = 'mt', fav = false) {
  const cls = `pn ${tier} ic-${icon}${pick ? ' pick' : ''}${on ? '' : ' dim'}${fav ? ' fav' : ''}`
  // 지름: 선택 24 · 필터 안 18 · 필터 밖 9. 2026-09-12 사용자 요청으로 20/15 → 24/18(아이콘 72% = 13px, 4km 뷰에서 그림이 읽힘).
  // 흐린 핀은 배경이라 그대로. 래퍼 .pw 는 20px 고정(앵커 기준) - 24px 선택 핀은 가운데 정렬로 양쪽 2px 넘칠 뿐 위치는 같다
  const size = pick ? 24 : on ? 18 : 9
  const z = pick ? 400 : on ? 200 : 100
  return { cls, size, z, sig: `${cls}|${size}|${z}` }
}
