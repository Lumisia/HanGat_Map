/**
 * 지도 페이지 장소 데이터 (MAP-01 ~ MAP-03).
 *
 * 백엔드 `GET /places?type=` (KTO 관광정보 실적재 2,138곳 + 소상공인 상가).
 * 실패 처리: 레이어별로 따로 받아 하나가 실패해도 나머지는 살린다(failed 목록으로 알림).
 * 전부 실패하면 live=false - 화면이 "불러오지 못했어요 · 새로고침" 안내를 띄운다.
 * ⚠️ 예전엔 실패 시 하드코딩 목업(data/placesMap)으로 바꿔치기했는데 2026-09-07 제거했다 -
 *    운영에서 가짜 장소가 진짜처럼 보이고, id가 없어 후기·공유·찜이 전부 죽는 문제.
 *
 * ★ 백엔드는 전체 필드명(`name`/`latitude`/…)을 쓰고 화면은 축약 키(`n`/`y`/…)를 쓴다.
 *   변환을 여기 한 곳에 모아 두면 MapCanvas·SearchBox·PlaceDetail이 지금 코드 그대로 돈다.
 *   ⚠️ `x`가 경도, `y`가 위도다 - 뒤집으면 제주 전역 핀이 통째로 엉뚱한 곳에 찍힌다.
 */
import { apiGet } from '../apiClient'
import { inferUnits, placeUnit } from '../../utils/emd'

/** 화면이 쓰는 장소 한 건. 기존 placesMap.js 한 줄과 같은 모양이다. */
export interface MapPlace {
  /** 백엔드 placeId. 혼잡 예보(`/crowd/forecast`)와 잇는 키다. 목업은 null */
  id: number | null
  n: string
  /** 경도 */
  x: number
  /** 위도 */
  y: number
  /** 권역 표시명 (동부/서부/남부/북부) */
  r: string
  /** 세부분류 표시명 (오름/해수욕장/박물관…). 태그가 없으면 대분류(카페/편의점/마트/음식점), 그것도 없으면 '정보 없음' */
  c: string
  /** 세부분류 코드 (관광공사 NA010100 등). 관광지 핀 아이콘 묶음을 앞자리로 정한다 - 없으면 null */
  tc: string | null
  /** 묶음 단위(읍·면 / 제주시내 / 서귀포시내 / 중문) - 주소에서 뽑고, 없으면 가장 가까운 장소의 단위(inferUnits). 섬 전체 뷰 묶음 핀용 */
  unit: string | null
  /** 카테고리 코드 (TOURIST/FOOD/CAFE/…) - 검색 결과의 핀 색 구분용 */
  cat: string
  addr: string | null
  tel: string | null
  hours: string | null
  /** 착한가격 지정 여부 - 상세 메뉴 섹션의 '착한가격' 뱃지 조건 (일반 식당 메뉴엔 안 붙인다) */
  good: boolean
  /** 숨은 명소 판정(백엔드 HiddenGemRule) - 테마 페이지 '한갓지도가 고른 명소' 묶음과 소개 페이지 해시태그가 쓴다 */
  hg: boolean
  park: boolean | null
  wc: boolean | null
  /** 그 장소의 날짜별 집중률. CrowdService가 채운다. 예보 없으면 null */
  series: (number | null)[] | null
  /**
   * 폴백 전용 기준 집중률. 백엔드가 죽었을 때만 값이 있다.
   * 실데이터에는 없다 - 근거 없는 샘플값이라 실서비스 화면에 올리지 않는다.
   */
  b: number | null
  /** 아직 수집 전인 값들 - 0으로 채우지 않는다(없는 정보를 있는 것처럼 만들지 않기 위해) */
  fee: number | null
  d: number | null
  in: number | null
  /** 폐업(businessStatus CLOSED). 목록·검색엔 안 나오고 찜·공유 링크로 열릴 때만 true - 패널이 '폐업' 배지를 단다 */
  closed: boolean
}

/** 백엔드 PlaceListResponse (map/model/dto/PlaceListResponse.java 와 동일 모양) */
interface BackendPlace {
  id: number
  name: string
  regionCode: string
  regionName: string
  categoryCode: string
  categoryName: string
  tagCode: string | null
  tagName: string | null
  roadAddress: string | null
  lotAddress: string | null
  latitude: number | null
  longitude: number | null
  phone: string | null
  operatingHoursText: string | null
  parkingAvailable: boolean | null
  toiletAvailable: boolean | null
  businessStatus: string
  goodPrice: boolean
  hiddenGem: boolean
}

/**
 * 장소 상세에서만 오는 값 (MAP-07). 목록 응답에는 없다 -
 * 입장료 원문이 최대 1,000자인데 값이 있는 곳은 27곳뿐이라 목록에 싣지 않았다.
 */
export interface PlaceDetail {
  /** 휴무일 원문. null = 정보 없음(연중무휴가 아니다) */
  rest: string | null
  /** 입장료 원문. "[개인]- 일반 1,500원..." 처럼 요금표가 통째로 온다 */
  feeText: string | null
  /** '무료'만 뜻할 때 true. 조건부 무료("무료※ 단, 특별전시 제외")는 false다 */
  free: boolean
  /** 별점 평균. 별점 후기가 없으면 null - 0.0 으로 그리지 않는다 */
  ratingAvg: number | null
  reviewCount: number
  /** 장소 사진(순서대로). 스트립은 thumb, 확대는 url 을 쓴다 */
  images: PlaceImage[]
  /** 사진 출처 표기 문구 - 사진이 있으면 화면에 반드시 보여준다(공공누리) */
  imageAttribution: string | null
  /** 소개 원문. 착한가격업소는 "대표메뉴: ○○ 9,000원 · …" 형태 - 핀 툴팁이 쓴다 */
  overview: string | null
  /** 착한가격 명단 기준일(행안부 CSV 발행일, ISO 날짜). 착한가격이 아니거나 모르면 null - 가격표 밑 출처 줄이 쓴다 */
  goodPriceBaseDate: string | null
  /** 같은 응답에서 뽑은 장소 기본 정보(업종·권역·주소…). 코스 경유지처럼 {id,n,x,y}만 든 대체 객체로 열렸을 때 빈 칸을 채운다(최종점검 #13) */
  place: MapPlace
}

export interface PlaceImage {
  url: string
  thumb: string
  caption: string | null
}

/** 백엔드 PlaceDetailResponse 중 이 화면이 쓰는 부분 */
/** 상세 응답은 목록 응답(BackendPlace)의 상위 집합이다 - 업종·권역·주소가 그대로 들어 있다 */
interface BackendPlaceDetail extends BackendPlace {
  restDayText: string | null
  useFeeText: string | null
  free: boolean
  ratingAvg: number | null
  reviewCount: number
  images: BackendPlaceImage[]
  overview: string | null
  goodPriceBaseDate?: string | null
}

interface BackendPlaceImage {
  url: string
  thumbnailUrl: string | null
  caption: string | null
  attribution: string | null
}

/** 화면 레이어 키 → 백엔드 type 파라미터 */
export type LayerKey = 'spot' | 'food' | 'dine' | 'cafe' | 'cvs' | 'stay' | 'mart'

/**
 * 첫 진입에 싣지 않는 레이어 = 관광지 빼고 전부.
 * 첫 화면은 관광지 칩만 켜져 있어(state.L 기본값) 나머지는 받아 놓아도 쓰이지 않는데,
 * 착한가격·식당·숙소 571KB 를 첫 진입마다 받고 있었다(2026-09-12 실측: Slow 4G 첫 진입 8.8초 중 약 3초).
 * 칩을 처음 켤 때 getLayer()로 그때 받아온다(toggleLayer) - 안 켜는 사람은 다운로드 비용 0.
 * 딥링크(findPlaceById)는 이 목록을 차례로 받아 보며 찾고, 없으면 단건 조회로 떨어진다.
 */
export const LAZY_LAYERS: LayerKey[] = ['food', 'dine', 'stay', 'cafe', 'cvs', 'mart']

export interface MapPlaces {
  /** true = 첫 진입 레이어(관광지)를 받았다. false = 못 받았다(백엔드 다운) - 화면이 '새로고침' 안내를 띄운다 */
  live: boolean
  layers: Record<LayerKey, MapPlace[]>
  /** 이번 진입에서 받으려고 한 레이어(성공·실패 포함). loadPlaces 는 이것만 갈아끼우고 나머지(칩으로 받아 둔 지연 레이어)는 둔다(최종점검 #28) */
  fetched: LayerKey[]
  /** 이번 진입에서 못 받아온 레이어. 화면이 안내하고, 칩을 다시 켜면 그 레이어만 재시도한다 */
  failed: LayerKey[]
}

/**
 * 상세 사진 띠(높이 96px)에 쓸 축소본 주소.
 * 관광공사가 사진 절반은 축소본 주소를 원본과 똑같이 준다(2026-09-12 DB: 9,354장 중 4,547장) - 그대로 쓰면
 * 96px 칸에 940×627 원본(장당 40~104KB)이 들어간다. 공사 URL 규칙(원본 `_image2_`, 축소본 `_image3_`, 장당 6~14KB)으로
 * 바꿔 쓴다 - 무지개해안도로 10장 592KB → 87KB. 축소본이 없는 사진(표본 40장 중 2장)은 화면의 onerror 가 원본으로 되돌린다.
 * 다른 도메인 주소는 규칙이 없어 replace 가 아무것도 안 바꾸고 원본 그대로다. 크게 보기(라이트박스)는 원본 url 을 쓴다.
 */
export function thumbOf (url: string, thumbnailUrl?: string | null): string {
  if (thumbnailUrl && thumbnailUrl !== url) return thumbnailUrl
  return url.replace('_image2_', '_image3_')
}

export const MapPlaceService = {
  /**
   * 첫 진입 레이어를 받아온다 - 지금은 관광지 하나. 나머지는 칩을 켤 때(LAZY_LAYERS).
   * 목록·부분 실패 구조는 그대로 둔다 - 첫 진입 레이어가 다시 늘어도 호출부(loadPlaces)가 안 바뀌게.
   */
  async getAll (): Promise<MapPlaces> {
    const keys: LayerKey[] = ['spot']
    const results = await Promise.allSettled(keys.map(k => apiGet<BackendPlace[]>(`/places?type=${k}`)))
    const layers = emptyLayers()
    const failed: LayerKey[] = []
    results.forEach((r, i) => {
      if (r.status === 'fulfilled') layers[keys[i]] = withUnits(r.value.map(toMapPlace))
      else failed.push(keys[i])
    })
    return { live: failed.length < keys.length, layers, fetched: keys, failed }
  },

  /** 칩을 처음 켤 때 한 레이어만 받아온다. 실패하면 null - 호출부가 토스트로 알린다. */
  async getLayer (key: LayerKey): Promise<MapPlace[] | null> {
    try {
      const rows = await apiGet<BackendPlace[]>(`/places?type=${key}`)
      return withUnits(rows.map(toMapPlace))
    } catch {
      return null
    }
  },

  /** 통합 검색 (MAP_002) - 이름·메뉴 부분 일치 상위 20건. 화면 필터(권역·업종) 범위를 함께 보낸다. 실패하면 빈 배열. */
  /** 통합 검색 - 정렬·범위는 서버가 정한다(2026-09-14 단일화). 실패는 null - 빈 배열(0건)과 구분해 화면이 "연결 실패"를 보여준다(최종점검 #26) */
  async search (q: string, opts?: { region?: string | null, categories?: string[] }): Promise<MapPlace[] | null> {
    try {
      const params = new URLSearchParams({ q })
      if (opts?.region) params.set('region', opts.region)
      if (opts?.categories?.length) params.set('categories', opts.categories.join(','))
      const rows = await apiGet<BackendPlace[]>(`/places/search?${params}`)
      return withUnits(rows.map(toMapPlace))
    } catch {
      return null
    }
  },

  /**
   * 상세 패널을 열 때만 부른다. 실패하면 null - 패널은 목록 데이터로 계속 그려진다.
   * 목업 모드는 id 가 null 이라 호출부에서 걸러진다.
   */
  async getDetail (id: number): Promise<PlaceDetail | null> {
    try {
      const row = await apiGet<BackendPlaceDetail>(`/places/${id}`)
      const images = (row.images ?? []).map(i => ({
        url: i.url,
        thumb: thumbOf(i.url, i.thumbnailUrl),
        caption: i.caption
      }))
      return {
        rest: row.restDayText,
        feeText: row.useFeeText,
        free: row.free,
        ratingAvg: row.ratingAvg,
        reviewCount: row.reviewCount ?? 0,
        images,
        imageAttribution: row.images?.[0]?.attribution ?? null,
        overview: row.overview ?? null,
        goodPriceBaseDate: row.goodPriceBaseDate ?? null,
        place: toMapPlace(row)
      }
    } catch {
      return null
    }
  },

  /**
   * 목록에 없는 장소를 id 로 받는다 - 폐업(CLOSED) 장소는 목록·검색에서 빠지지만 찜·공유 링크로는 들어온다.
   * 상세 응답은 목록 응답의 상위 집합이라 같은 변환을 쓴다. 없거나 실패하면 null.
   */
  /**
   * 상세 한 건. missing = 백엔드가 "없는 장소"라고 답함(4xx) / place null + missing false = 못 받음(연결·서버).
   * 공유 링크 복원이 지연 레이어 6개(3MB)를 다 받기 전에 이 한 건으로 먼저 확인한다(최종점검 #49)
   */
  async getById (id: number): Promise<{ place: MapPlace | null, missing: boolean }> {
    try {
      return { place: toMapPlace(await apiGet<BackendPlace>(`/places/${id}`)), missing: false }
    } catch (e) {
      return { place: null, missing: /^HTTP 4\d\d$/.test((e as Error)?.message ?? '') }
    }
  }
}

/** 백엔드 응답 → 화면 형식. 좌표가 없는 장소는 지도에 못 그리므로 호출부에서 걸러진다. */
/** 한 레이어를 통째로 받은 뒤 단위 없는 장소를 이웃 장소로 채운다 - 묶음 핀에서 빠지는 곳이 없게 */
function withUnits (rows: MapPlace[]): MapPlace[] {
  inferUnits(rows)
  return rows
}

function toMapPlace (row: BackendPlace): MapPlace {
  return {
    id: row.id,
    n: row.name,
    x: row.longitude ?? 0,
    y: row.latitude ?? 0,
    r: row.regionName,
    // 세부분류가 없는 장소가 있다 - 빈 문자열로 두면 드롭다운에 빈 항목이 생긴다
    // 세부 태그(오름·호텔·관광식당)가 없으면 대분류(카페·편의점·마트·음식점)로 - 소상공인 상가 5,419곳과 착한가격 268곳은 태그가 없어
    // '정보 없음 · 서부'로 보였다(2026-09-13). 찜 탭 매퍼(api/favorites.js)와 같은 규칙
    c: row.tagName ?? row.categoryName ?? '정보 없음',
    tc: row.tagCode ?? null,   // 관광공사 분류 코드 - 관광지 핀 아이콘 묶음(spotIconGroup)이 앞자리로 나눈다
    unit: placeUnit(row.lotAddress, row.roadAddress),
    cat: row.categoryCode,
    addr: row.roadAddress ?? row.lotAddress,
    tel: row.phone,
    hours: row.operatingHoursText,
    good: row.goodPrice,
    hg: !!row.hiddenGem,
    park: row.parkingAvailable,
    wc: row.toiletAvailable,
    series: null,
    b: null,
    fee: null,
    d: null,
    in: null,
    closed: row.businessStatus === 'CLOSED'
  }
}

/** 좌표가 없으면 지도에 찍을 수 없다. 목록·검색에는 남기고 싶으면 호출부에서 따로 거른다. */
export function hasCoords (p: MapPlace): boolean {
  return p.x !== 0 && p.y !== 0
}

function emptyLayers (): Record<LayerKey, MapPlace[]> {
  return { spot: [], food: [], dine: [], cafe: [], cvs: [], stay: [], mart: [] }
}

export default MapPlaceService
