<script setup>
import { ref, onMounted, onBeforeUnmount, watch, nextTick } from 'vue'
import { loadKakaoMap } from '@/composables/useKakaoLoader'
import { mapBridge } from '@/composables/mapBridge'
import { state, inFilter, inRegion, placeKey, isFav } from '@/stores/mapStore'
import MapPlaceService, { hasCoords } from '@/services/map/MapPlaceService'

import { crowd, tier } from '@/utils/crowd'
import { cssVar } from '@/utils/geo'
import { POI_MARKER_CLASS, POI_GROUPS, spotPinSpec, spotIconGroup, shouldShowMapLabels } from './mapPresentation'
import { clusterModeFor, hideDimFor, clusterByUnit, clusterPins, dominantTier, clusterSize } from '@/utils/cluster'
import { JEJU_MAX_LEVEL, clampToJeju } from '@/utils/jejuBounds'
import { goodPriceSourceLine } from '@/utils/dataSources'
import { escapeHtml } from '@/utils/html'   // 오버레이에 넣는 장소명·메뉴·읍면 이름은 전부 이걸로 감싼다(최종점검 #32)

const emit = defineEmits(['select', 'blank-click'])

const el = ref(null)
const failed = ref('')
const origin = location.origin
const reload = () => location.reload()
let map = null

/* ── 핀 풀 + 자체 레이어 (성능: 오버레이 재사용) ──
   관광지·업종 핀은 장소당 노드 하나를 한 번만 만들어 두고, 상태가 바뀌면 클래스·크기·표시 여부만 바꾼다.
   예전엔 날짜·선택·필터가 바뀔 때마다 841~1,558개를 전부 지우고 새로 만들어 메인 스레드가 2~6초 멈췄다(9/7 실측).

   핀마다 CustomOverlay 를 만들지 않고 CustomOverlay 하나(레이어) 안에 절대배치로 넣는 이유:
   카카오 setMap 은 핀마다 노드 크기를 읽어 강제 레이아웃을 일으키는데, 그 비용이 이미 붙어 있는 핀 수에 비례한다
   (실측: 붙은 핀 3,800개 상태에서 핀 하나 넣고 크기 읽기 15ms, 읽기 없이 넣기만 하면 0.05ms).
   카페 3,039개를 핀마다 setMap 으로 넣으면 15초, 숨겼다 다시 setMap 해도 12초였다. 레이어 하나에 자식으로 넣으면
   크기 읽기가 없어 수백 ms 안에 끝나고, 지도 이동 때 카카오가 옮기는 오버레이도 하나뿐이다.
   핀 위치 = 앵커(제주 중심) 기준 픽셀 오프셋. 같은 레벨에선 이동해도 안 변하고, 줌이 바뀌면 전부 다시 계산한다.

   키 = 그룹:장소id. 항목 = { node(.pw), lb(이름표), dot(점), data(현재 장소 객체), sig(마지막 적용 모습), shown, group } */
const pool = new Map()
/* anchorLL = 레이어 오버레이의 현재 위치, originPt = 핀 오프셋의 기준 픽셀(현재 레벨). 처음엔 둘이 같은 지점이다.
   카카오는 위치(앵커)가 화면 밖으로 나간 CustomOverlay 를 DOM 에서 떼어 버린다(실측: 확대해서 앵커가 화면을 벗어나자 핀 전부 소실).
   그래서 이동 중 앵커가 화면 가운데를 벗어나면 앵커만 현재 중심으로 옮기고, 핀은 그대로 둔 채 레이어를 반대로 밀어 상쇄한다 */
let layerOv = null, layerNode = null, proj = null, anchorLL = null, originPt = null
/* 선택 핀·코스 번호·경로선은 몇 개 안 돼(20개 남짓) 카카오 오버레이로 매번 다시 만든다. 대신 모습이 지난번과 같으면 건너뛴다 */
const EX = { route: [], num: [], sel: [] }
let exSig = ''

/** 선택 핀의 업종색 - 지도 마커와 같은 팔레트 */
const CAT_MARKER = { FOOD: 'mk-dine', CAFE: 'mk-cafe', CONVENIENCE: 'mk-cvs', LODGING: 'mk-stay', MART: 'mk-mart' }

const LL = (lat, lng) => new kakao.maps.LatLng(lat, lng)

function clearExtras() {
  for (const k in EX) { EX[k].forEach(o => o.setMap(null)); EX[k].length = 0 }
  exSig = ''
}

/** 전부 내린다 - 언마운트 때만. 상태 변경 때는 draw() 가 차분으로 적용한다 */
function clearOverlays() {
  closeTip()
  clearExtras()
  if (layerOv) { layerOv.setMap(null); layerOv = null; layerNode = null }
  clusterNodes = []
  pool.clear()
}

/* MAP_001 착한가격 미리보기 말풍선 - PC 에서 핀에 마우스를 올리면 뜨고 벗어나면 사라진다. 한 번에 하나만.
   전엔 클릭 시 말풍선 → 안의 '상세 보기'를 다시 눌러야 패널이 열려 다른 핀과 동작이 달랐다(사용자 피드백, 2026-09-19).
   이제 클릭은 모든 핀이 곧 상세 패널이고, 말풍선은 마우스가 있는 PC 에서만 미리보기로 남는다 */
let tip = null
let tipTimer = 0

function closeTip() {
  if (tipTimer) { clearTimeout(tipTimer); tipTimer = 0 }
  if (tip) { tip.setMap(null); tip = null }
}

/** 마우스가 있는 환경인가 - 폰·태블릿은 올림 자체가 없어 미리보기를 만들지 않는다(클릭 = 패널) */
const canHover = () => typeof window !== 'undefined' && !!window.matchMedia?.('(hover: hover) and (pointer: fine)').matches

/* 지금 마우스가 올라가 있는 핀 키. 판단은 mousemove 로만 한다 - mouseover/mouseout 은 카카오가 오버레이(말풍선)를 붙일 때
   핀 레이어를 뗐다 붙이면서 헛발이 나서, 말풍선이 뜨는 순간 mouseout → 닫힘 → 다시 mouseover → 300ms 뒤 또 뜸을 반복했다 */
let hoverKey = null
function onPinHover(ev) {
  const key = ev.target.closest?.('.pw')?.dataset.k ?? null
  if (key === hoverKey) return   // 같은 핀 안(또는 빈 지도 안)에서 움직인 것
  hoverKey = key
  closeTip()
  const e = key && pool.get(key)
  if (!e || e.group !== 'food') return
  tipTimer = setTimeout(() => { tipTimer = 0; showGoodPriceTip(e.data) }, 300)   // 스쳐 가는 동안엔 안 뜨게
}

/** 메뉴·가격은 목록엔 없고 상세 응답(overview)에만 있어 올린 시점에 받아온다 */
async function showGoodPriceTip(f) {
  closeTip()
  const node = document.createElement('div')
  node.className = 'gp-tip'
  node.style.pointerEvents = 'none'   // 옆 핀을 가려도 올림·클릭이 그 핀으로 가게
  const render = body => { node.innerHTML = `<b>${escapeHtml(f.n)}</b>${body}` }
  render('<span>메뉴 불러오는 중…</span>')
  const my = new kakao.maps.CustomOverlay({
    position: LL(f.y, f.x), content: node, yAnchor: 1.3, zIndex: 500, clickable: false,
  })
  my.setMap(map)
  tip = my
  const d = f.id != null ? await MapPlaceService.getDetail(f.id) : null
  if (tip !== my) return   // 기다리는 사이 닫혔거나 다른 핀으로 바뀜
  // 응답이 아예 없으면(연결 끊김·서버 재시작·타임아웃) "메뉴가 없다"가 아니라 "못 받았다"고 말한다 - 클릭하면 패널이 다시 받아온다(최종점검 #33)
  const menu = d?.overview
    ? d.overview.replace(/^대표메뉴:\s*/, '').split(' · ').map(m => `<span>${escapeHtml(m)}</span>`).join('')
    : d ? '<span>메뉴 정보 없음</span>'
    : '<span>메뉴를 불러오지 못했어요 · 눌러서 상세에서 확인해 주세요</span>'
  // 가격표 밑에 출처·기준일 - 상세 패널과 같은 문장 (MAP_003)
  render(menu + (f.good ? `<i class="gp-src">${goodPriceSourceLine(d?.goodPriceBaseDate)}</i>` : ''))
}

function syncLabelVisibility() {
  if (!map || !el.value) return
  el.value.classList.toggle('labels-visible', shouldShowMapLabels(map.getLevel()))
}

/** 줌이 바뀌면 픽셀 오프셋의 척도가 달라진다 - 앵커를 현재 중심으로 옮기고 풀 핀 전부 다시 놓는다(스타일 쓰기만이라 수십 ms) */
function relayoutPins() {
  if (!map || !layerOv) return
  proj = map.getProjection()
  anchorLL = map.getCenter()
  originPt = proj.pointFromCoords(anchorLL)
  layerOv.setPosition(anchorLL)
  layerNode.style.transform = ''
  pool.forEach(place)
}

/** 지도가 움직여 앵커가 화면 가운데 절반을 벗어나면 앵커를 중심으로 옮긴다. 핀 오프셋은 originPt 기준 그대로 두고
    레이어를 (originPt - 새 앵커) 만큼 밀어 화면 위치를 유지한다 - 핀 수와 무관한 O(1) */
function keepAnchorOnScreen() {
  if (!map || !layerOv || !el.value) return
  const p = proj.containerPointFromCoords(anchorLL)
  const W = el.value.clientWidth, H = el.value.clientHeight
  if (p.x > W * 0.25 && p.x < W * 0.75 && p.y > H * 0.25 && p.y < H * 0.75) return
  anchorLL = map.getCenter()
  // setPosition 은 카카오가 레이어를 떼었다 붙이는데, 그 비용이 보이는 핀 수에 비례한다(실측 300~770개 보일 때 250~430ms 멈춤).
  // 붙이는 동안 레이어를 display:none 으로 두면 자식을 그리지 않아 30ms 안(실측)이고, 같은 프레임 안에서 되돌리므로 깜빡임은 없다
  layerNode.style.display = 'none'
  layerOv.setPosition(anchorLL)
  const a = proj.pointFromCoords(anchorLL)
  layerNode.style.transform = `translate(${originPt.x - a.x}px,${originPt.y - a.y}px)`
  layerNode.style.display = ''
}

/* 줌이 바뀌면 핀을 다시 놓고(relayout) 묶음도 다시 계산한다(draw - 핀 서명은 같아 싸다) */
const onZoomChanged = () => { syncLabelVisibility(); relayoutPins(); draw() }

function place(e) {
  const pt = proj.pointFromCoords(LL(e.data.y, e.data.x))
  e.px = pt.x - originPt.x   // 묶음 계산도 이 값을 쓴다
  e.py = pt.y - originPt.y
  e.node.style.left = e.px + 'px'
  e.node.style.top = e.py + 'px'
}

/* ── 묶음 핀 (축소 뷰) ──
   레벨 10 이상은 행정 단위(읍면·시내·중문) 하나에 묶음 하나(이름표 = 단위), 그 아래는 픽셀 40px 로 이름 없이 - 관광지·착한가격·숙소는
   9까지, 카페·편의점·마트·식당은 6까지(2km 뷰) - 그 다음부터 전부 개별 핀 (utils/cluster).
   레이어별로 따로 묶고(관광지는 관광지끼리) 묶인 핀은 숨긴다. 매 draw 마다 다시 계산한다 -
   묶음은 많아야 300개라 노드를 새로 만들어도 수 ms 이고, 날짜가 바뀌면 테두리 비율이 달라져 어차피 다시 그려야 한다 */
let clusterNodes = []

function clusterPass(lv) {
  clusterNodes.forEach(n => n.remove())
  clusterNodes = []
  for (const e of pool.values()) e.node.classList.remove('cl-in')
  const byGroup = new Map()
  for (const e of pool.values()) {
    if (!e.wanted || e.pick) continue   // 선택·코스 핀은 묶지 않고 위에 남긴다. 화면 밖(컬링) 핀도 개수엔 들어간다
    if (!byGroup.has(e.group)) byGroup.set(e.group, [])
    byGroup.get(e.group).push(e)
  }
  for (const [group, list] of byGroup) {
    const mode = clusterModeFor(lv, group)   // 관광지·착한가격·숙소는 9까지, 카페·편의점·마트·식당은 6까지(2km 뷰) 묶는다
    if (!mode.unit && !mode.radius) continue
    const clusters = mode.unit ? clusterByUnit(list, e => e.data.unit) : clusterPins(list, mode.radius)
    // 혼자인 장소는 묶지 않고 핀 그대로 보여준다(2026-09-12 결정) - 근처에 아무도 없는 곳은 "1" 묶음보다 핀이 더 말이 된다
    for (const c of clusters) {
      if (c.members.length < 2) continue
      c.members.forEach(e => e.node.classList.add('cl-in'))
      clusterNodes.push(makeCluster(group, c, mode.unit ? c.name : null))
    }
  }
}

/** 묶음 노드: 색 원 + 흰 숫자(디자인 A안). 관광지는 대표 혼잡 색(선택한 날짜 기준, 예보 있는 곳만 세서 다수), 업종은 업종 색.
    이름표는 행정 단위(섬 전체 뷰에서만). 클릭하면 그 자리로 2단계 확대 - 픽셀 묶음으로 바뀌어 안에 뭐가 어디 있는지 보인다 */
function makeCluster(group, c, name) {
  const n = c.members.length
  const node = document.createElement('div')
  let cls = group === 'spot' ? 'cl' : `cl poi ${POI_MARKER_CLASS[group]}`
  if (group === 'spot') {
    const cnt = { calm: 0, mid: 0, busy: 0, none: 0 }
    for (const e of c.members) cnt[state.L.crowd ? tier(crowd(e.data, state.di)) : 'calm']++
    cls += ' ' + dominantTier(cnt)
  }
  node.className = cls
  const size = clusterSize(n)
  node.style.cssText = `left:${c.x}px;top:${c.y}px;width:${size}px;height:${size}px`
  node.innerHTML = `<b>${n}</b>` + (name ? `<div class="lb-t">${escapeHtml(name)}</div>` : '')
  const lat = c.members.reduce((a, e) => a + e.data.y, 0) / n
  const lng = c.members.reduce((a, e) => a + e.data.x, 0) / n
  node.addEventListener('click', ev => {
    ev.stopPropagation()
    map.setLevel(Math.max(1, map.getLevel() - 2), { anchor: LL(lat, lng) })
  })
  layerNode.appendChild(node)
  return node
}

/** 풀 핀이 들어갈 레이어(CustomOverlay 1개). 클릭은 레이어에서 한 번만 받아 핀 키로 찾는다 -
    핀마다 리스너를 붙이지 않고, 재진입·재요청으로 장소 객체가 새것으로 바뀌어도 e.data 가 항상 현재 객체다 */
function ensureLayer() {
  if (layerOv) return
  layerNode = document.createElement('div')
  layerNode.className = 'pl-layer'
  layerNode.addEventListener('click', ev => {
    const pw = ev.target.closest('.pw')
    const e = pw && pool.get(pw.dataset.k)
    if (!e) return
    ev.stopPropagation()
    closeTip()
    emit('select', e.data)   // 핀 종류와 무관하게 클릭 = 상세 패널(착한가격도, 2026-09-19)
  })
  layerOv = new kakao.maps.CustomOverlay({
    position: map.getCenter(), content: layerNode, xAnchor: 0, yAnchor: 0, zIndex: 50, clickable: true,
  })
  layerOv.setMap(map)
  relayoutPins()
}

/** 풀에서 핀을 꺼내고, 없으면 만든다 */
function ensurePin(key, group, p) {
  let e = pool.get(key)
  if (e) return e
  const node = document.createElement('div')
  node.className = 'pw pl'
  node.dataset.k = key
  node.innerHTML = group === 'spot'
    ? '<div class="lb-t"></div><div class="pn"></div>'
    : `<div class="lb-t"></div><div class="poi-marker ${POI_MARKER_CLASS[group]}"></div>`
  e = { node, lb: node.firstElementChild, dot: node.lastElementChild, data: p, sig: '', shown: true, wanted: true, group, pick: false, px: 0, py: 0 }
  if (group !== 'spot') node.style.zIndex = 60
  place(e)
  layerNode.appendChild(node)
  pool.set(key, e)
  return e
}

/** 숨김·표시는 클래스로만 - DOM 을 떼었다 붙이면 붙은 핀 수만큼 비용이 다시 든다 */
function show(e, on) {
  if (e.shown === on) return
  e.shown = on
  e.node.classList.toggle('hid', !on)
}

/* ── 뷰포트 컬링 ──
   화면 밖 핀은 숨긴다(display:none). 카카오가 레이어를 다시 붙이거나(앵커 재설정 setPosition, 선택 핀 setMap) 브라우저가 다시 칠할 때
   비용이 "보이는 핀 수"에 비례한다 - 실측: 3,851개 보이면 재부착 232ms + 다시 칠하기 240~460ms(카페 켜고 핀 클릭·드래그마다 렉),
   같은 3,851개를 숨기면 35ms, 300개 보이면 14ms. 여백은 화면의 1/4(사방) - 1km 뷰에서 여백을 화면 크기만큼 주면 섬 대부분이
   들어와 2,896개가 남았다. 다시 계산은 이동이 끝났을 때(idle)만 - 이동 중에 0.2초마다 하면 새로 보이는 핀 수백 개를
   그리는 비용이 애니메이션 동안 여러 번 든다(실측). 핀을 눌러 이동하는 거리는 반 화면 안이라 여백이 감당하고,
   그보다 멀리 끌면 손을 뗄 때 채워진다. 묶음은 컬링과 무관하게 wanted 기준으로 세므로 개수는 그대로다 */
let view = null   // 레이어 좌표계(originPt 기준 px)의 보이는 사각형, 여백 포함

function updateView() {
  if (!map || !proj || !originPt) { view = null; return }
  const b = map.getBounds()
  const sw = proj.pointFromCoords(b.getSouthWest()), ne = proj.pointFromCoords(b.getNorthEast())
  const x1 = Math.min(sw.x, ne.x) - originPt.x, x2 = Math.max(sw.x, ne.x) - originPt.x
  const y1 = Math.min(sw.y, ne.y) - originPt.y, y2 = Math.max(sw.y, ne.y) - originPt.y
  // 여백 = 화면의 1/4(사방). 절반이면 북부 해안 1km 뷰에서 1,864개가 보여 idle 때 그리는 데 350ms, 1/4 이면 그 절반 아래
  const mx = (x2 - x1) / 4, my = (y2 - y1) / 4
  view = { x1: x1 - mx, x2: x2 + mx, y1: y1 - my, y2: y2 + my }
}

const inView = e => !view || (e.px >= view.x1 && e.px <= view.x2 && e.py >= view.y1 && e.py <= view.y2)

/** 필터·레이어가 원하는(wanted) 핀 중 화면(여백 포함) 안의 것만 보인다 */
function cullPass() {
  updateView()
  for (const e of pool.values()) show(e, e.wanted && inView(e))
}

/** 이동 중엔 앵커만 화면 안에 유지한다(컬링은 idle 에서) */
function onCenterChanged() {
  keepAnchorOnScreen()
}

/** 레이어 배열이 새로 왔을 때(재진입·칩 재요청) 목록에서 사라진 장소의 핀을 풀에서 뺀다 - 폐업 등.
    배열이 같은 객체면 아무것도 안 한다(매 draw 마다 2천 건을 대조하지 않게) */
const lastLists = {}
function prune(group, list) {
  if (lastLists[group] === list) return
  lastLists[group] = list
  const ids = new Set(list.map(p => p.id ?? p.n))
  for (const [key, e] of pool) {
    if (e.group === group && !ids.has(e.data.id ?? e.data.n)) { e.node.remove(); pool.delete(key) }
  }
}

/** 선택 핀·코스 번호용 - 카카오 오버레이로 매번 새로 만드는 쪽 */
function addPin(group, lat, lng, html, onClick, z) {
  const node = document.createElement('div')
  node.className = 'pw'
  node.innerHTML = html
  const ov = new kakao.maps.CustomOverlay({
    position: LL(lat, lng), content: node,
    yAnchor: 0.5, xAnchor: 0.5, zIndex: z || 0, clickable: !!onClick,
  })
  ov.setMap(map)
  EX[group].push(ov)
  if (onClick) node.addEventListener('click', e => { e.stopPropagation(); onClick() })
}

/** 지금 상태를 핀에 적용한다. 만드는 건 처음 보는 장소뿐이고, 나머지는 서명이 달라진 핀만 손댄다 */
function draw() {
  if (!map) return
  closeTip()
  ensureLayer()
  const { di, sel, course, courseDay, L } = state
  const inCourse = p => course && course.stops.some(st => st.o && placeKey(st.o) === placeKey(p))   // id 기준 - 동명 장소가 같이 커지지 않게
  const seen = new Set()
  const lv = map.getLevel()
  // 관광지가 묶이는 축소 뷰(레벨 9 이상)에선 필터 밖 흐린 핀을 아예 숨긴다 - 섬 전체 뷰의 회색 점 600개는 정보가 아니라 잡음.
  // 묶음 개수에도 안 들어가 왼쪽 목록과 기준이 같다. 확대하면(레벨 8 이하) 다시 보인다
  const hideDim = hideDimFor(lv)

  // 관광지 - 날짜가 바뀌면 달라지는 건 색·크기뿐. 좌표 없는 장소는 못 찍는다(KTO 원본 좌표 오류로 null 인 건)
  prune('spot', state.layers.spot)
  if (L.spot) for (const s of state.layers.spot) {
    if (!hasCoords(s)) continue
    const on = inFilter(s)
    if (!on && hideDim) continue
    const key = 'spot:' + (s.id ?? s.n)
    seen.add(key)
    const e = ensurePin(key, 'spot', s)
    e.data = s
    const pick = !!(inCourse(s) || (sel && placeKey(sel) === placeKey(s)))
    const spec = spotPinSpec(L.crowd ? tier(crowd(s, di)) : 'calm', pick, on, spotIconGroup(s.tc), isFav(s))
    const sig = spec.sig + '|' + s.n
    if (sig !== e.sig) {
      e.sig = sig
      e.dot.className = spec.cls
      e.dot.style.width = e.dot.style.height = spec.size + 'px'
      e.lb.textContent = s.n
      e.lb.classList.toggle('off', !on)   // 이름표는 필터 안 장소만
      e.node.style.zIndex = spec.z
    }
    e.pick = pick
    e.wanted = true
  }

  // 업종 - 켜진 레이어의 권역 안 장소만. 끈 레이어는 숨길 뿐 풀에 남겨 다시 켤 때 즉시 보인다
  for (const g of POI_GROUPS) {
    prune(g, state.layers[g])
    if (!L[g]) continue
    for (const f of state.layers[g]) {
      if (!hasCoords(f) || !inRegion(f)) continue
      const key = g + ':' + (f.id ?? f.n)
      seen.add(key)
      const e = ensurePin(key, g, f)
      e.data = f
      const fav = isFav(f)
      const sig = f.n + (fav ? '|fav' : '')
      if (e.sig !== sig) { e.sig = sig; e.lb.textContent = f.n; e.dot.classList.toggle('fav', fav) }
      e.wanted = true
    }
  }

  // 이번 패스에 없는 핀(끈 레이어·권역 밖·축소 뷰의 흐린 핀)은 원하지 않는 핀
  for (const [key, e] of pool) if (!seen.has(key)) e.wanted = false

  cullPass()
  clusterPass(lv)
  drawExtras(di, sel, course, courseDay, L)
}

/** 선택 핀 + 코스(경로선·번호 핀). 모습 서명이 지난번과 같으면 그대로 둔다 */
function drawExtras(di, sel, course, courseDay, L) {
  // 검색 등으로 연 장소는 레이어가 꺼져 있어도 선택 핀을 띄운다 (MAP_002) -
  // 지도가 이동만 하고 아무것도 안 보이면 고장으로 느껴진다. 이름표는 줌 무관 항상 표시.
  // 코스 정류지는 제외 - 번호 핀이 이미 그 자리를 표시하고, 겹치면 이름표가 두 장 뜬다
  const selPin = !!(sel && hasCoords(sel) && !(L.spot && state.layers.spot.includes(sel))
    && !(course && course.stops.some(cs => cs.o === sel)))
  const selTier = sel?.cat === 'TOURIST' ? (L.crowd ? tier(crowd(sel, di)) : 'calm') : ''
  const sig = [
    selPin ? `${sel.id ?? sel.n}|${selTier}|${isFav(sel) ? 'fav' : ''}` : '',
    course ? course.stops.map(s => (s.o ? `${s.o.id ?? s.o.n}@${s.d}` : '')).join(',') : '',
    courseDay,
    course && sel ? (sel.id ?? sel.n) : '',   // 번호 핀의 pick 강조
  ].join('#')
  if (sig === exSig) return
  clearExtras()
  exSig = sig

  if (selPin) {
    // 관광지 선택 핀의 클래스·지름은 spotPinSpec 한 곳에서 - 풀 핀(pick)과 같은 모습이어야 한다
    const sp = sel.cat === 'TOURIST' ? spotPinSpec(selTier, true, true, spotIconGroup(sel.tc), isFav(sel)) : null
    const pin = sp
      ? `<div class="${sp.cls}" style="width:${sp.size}px;height:${sp.size}px"></div>`
      : `<div class="poi-marker sel-pick ${sel.good ? 'mk-food' : (CAT_MARKER[sel.cat] ?? 'mk-dine')}${isFav(sel) ? ' fav' : ''}"></div>`
    addPin('sel', sel.y, sel.x, `<div class="lb-t sel-on">${escapeHtml(sel.n)}</div>` + pin, () => emit('select', sel), 500)
  }

  if (course) {
    /* MAP_006: 일차 전환 시 해당 일차 경로만 강조 (번호는 일차 내 방문 순서) */
    const g = {}
    course.stops.filter(s => s.o).forEach(s => (g[s.d] = g[s.d] || []).push(s))
    Object.keys(g).forEach(d => {
      const pts = g[d].map(s => [s.o.y, s.o.x])
      const on = courseDay === 'all' || +courseDay === +d
      if (pts.length > 1) {
        const line = new kakao.maps.Polyline({
          path: pts.map(p => LL(p[0], p[1])),
          strokeWeight: on ? 4 : 2, strokeColor: cssVar('ac'),
          strokeOpacity: on ? 0.9 : 0.25, strokeStyle: 'shortdash',
        })
        line.setMap(map)
        EX.route.push(line)
      }
      // 코스 핀도 눌러서 이름·상세를 본다 - 코스가 화면의 주인공이라 이름표는 줌 무관 상시 표시
      if (on) g[d].forEach((stop, i) => addPin('num', stop.o.y, stop.o.x,
        `<div class="lb-t sel-on">${escapeHtml(stop.o.n)}</div><div class="mk-num${sel === stop.o ? ' pick' : ''}">${i + 1}</div>`,
        () => emit('select', stop.o), 600))
    })
  }
}

/** 지도 위에 떠 있는 카드(필터·상세·코스)가 덮는 영역 - 데스크톱은 왼쪽에 나란히, 모바일은 아래 시트.
    전엔 "왼쪽 340 + 오른쪽 380" 고정값이었는데 코스 패널은 오른쪽이 아니라 왼쪽 두 번째 자리라 코스 핀이 패널 뒤에 숨었다(최종점검 #12) */
function coveredInsets() {
  const box = el.value.getBoundingClientRect()
  let left = 0, bottom = 0
  for (const c of document.querySelectorAll('.cond, .pop, .panel')) {
    const r = c.getBoundingClientRect()
    if (!r.width || !r.height) continue
    // 폰의 닫힌 장소 찾기(.cond)는 pointer-events:none 인 채 자리만 차지하고, 가운데 모달(장소 찾기·상세 = 화면 폭에 위 24px 부터)은
    // 지도를 통째로 덮는다 - 둘 다 덮는 카드로 세지 않는다. 닫힌 시트를 세면 권역 복원 때 남부가 상단 100px 띠에 몰렸고(최종점검 #60),
    // 모달을 세면 맞출 자리가 없다. 모달 안에서 고르면 전체 화면 기준으로 맞추고, 모달을 닫으면 그 화면(핀은 가운데)이 보인다
    if (getComputedStyle(c).pointerEvents === 'none') continue
    // offsetTop/offsetWidth: 열림 애니메이션(translateY 16px·scale .98) 중이라 getBoundingClientRect 는 아래로 밀려 있다 - 레이아웃 값으로 본다
    if (c.offsetWidth >= box.width * 0.9 && c.offsetTop < 40) continue
    if (r.width >= box.width * 0.9) {
      // 화면 폭을 다 쓰는 아래 시트(모바일). 사진·소개가 늦게 도착하면 시트가 max-height(68vh)까지 자라므로 지금 높이가 아니라 최대 높이로 잡는다 -
      // 처음 잰 높이로 맞추면 잠시 뒤 시트가 커져 핀을 덮었다
      const maxH = parseFloat(getComputedStyle(c).maxHeight)
      bottom = Math.max(bottom, box.bottom - r.top, Number.isFinite(maxH) ? maxH : 0)
    } else left = Math.max(left, r.right - box.left)
  }
  return { left, bottom }
}

/** 카드에 가리지 않도록 여백을 주고 맞춘다. 패널은 다음 틱에 그려지므로(v-if) 한 틱 뒤에 잰다 */
function fitPoints(pts, minLevel) {
  if (!map || !pts?.length) return
  if (pts.length === 1) {
    map.setCenter(LL(pts[0][0], pts[0][1]))
    if (map.getLevel() > (minLevel || 6)) map.setLevel(minLevel || 6)
    return
  }
  nextTick(() => {
    if (!map || !el.value) return
    const W = el.value.clientWidth, H = el.value.clientHeight
    if (!W || !H) return
    const b = new kakao.maps.LatLngBounds()
    pts.forEach(p => b.extend(LL(p[0], p[1])))
    const ins = coveredInsets()
    // 여백은 카드 끝 + 24px. 카드가 화면의 2/3를 넘게 덮으면(좁은 창) 그 이상은 양보하지 않는다 - 지도가 우표만큼 남는 것보단 낫다
    // 덮는 카드가 없으면(폰) 24px 만 - 전엔 카드가 없어도 폭의 26% 를 비워 390px 에서 한 단계 더 축소됐다(#60)
    const left = Math.min(Math.round(W * 0.66), ins.left ? ins.left + 24 : 24)
    const right = 56   // 오른쪽엔 줌 컨트롤뿐
    const top = Math.min(90, Math.round(H * 0.14))
    // 모바일 시트는 화면 2/3를 덮는다 - 남은 띠(최소 100px)에 맞추면 줌이 한두 단계 더 빠질 뿐, 시트 뒤에 핀을 숨기는 것보단 낫다
    const bottom = Math.min(H - top - 100, Math.max(Math.min(130, Math.round(H * 0.18)), ins.bottom + 24))
    map.setBounds(b, top, right, bottom, left)
  })
}

function fitRegion() {
  if (!map) return
  const ss = state.layers.spot.filter(s => (state.F.reg === '전체' || s.r === state.F.reg) && hasCoords(s))
  if (!ss.length) return
  if (state.F.reg === '전체') { map.setCenter(LL(33.383, 126.55)); map.setLevel(10); return }
  fitPoints(ss.map(s => [s.y, s.x]))
}

const onResize = () => map && map.relayout()

/* 제주 밖으로 나가면 되돌린다 - 카카오엔 maxBounds 가 없어 이동이 끝날 때 중심을 상자 안으로 민다 (MAP_001).
   dragend 가 아니라 idle 인 이유: 손을 뗀 뒤 관성으로 더 미끄러지는데 dragend 는 그 전에 발생해 되돌린 자리를 관성이 덮어쓴다(실측).
   idle 은 관성까지 끝난 뒤 한 번 오고, 되돌린 뒤의 idle 은 상자 안이라 아무것도 안 해 되풀이되지 않는다 */
function keepInJeju() {
  const c = map.getCenter()
  const { lat, lng } = clampToJeju(c.getLat(), c.getLng())
  // panTo 가 아니라 setCenter: 먼 거리 panTo 는 1초 넘게 천천히 움직여(실측) 그 사이 다음 드래그와 겹친다. 경계는 즉시 되돌린다
  if (lat !== c.getLat() || lng !== c.getLng()) map.setCenter(LL(lat, lng))
}

/** 이동이 끝나면 제주 안으로 되돌리고, 새 화면 기준으로 핀 컬링을 다시 한다 */
function onIdle() {
  keepInJeju()
  cullPass()
}

onMounted(async () => {
  try {
    await loadKakaoMap()
  } catch (e) {
    failed.value = e.message
    return
  }
  map = new kakao.maps.Map(el.value, { center: LL(33.383, 126.55), level: 10 })
  map.setMinLevel(1)
  map.setMaxLevel(JEJU_MAX_LEVEL)   // 제주 밖(남해안)까지 축소되지 않게 (MAP_001)
  map.addControl(new kakao.maps.ZoomControl(), kakao.maps.ControlPosition.BOTTOMRIGHT)
  kakao.maps.event.addListener(map, 'click', () => { closeTip(); emit('blank-click') })
  if (canHover()) {   // 착한가격 미리보기(PC) - 리스너는 지도 상자에 두 개뿐, 핀 수천 개에 하나씩 붙이지 않는다
    el.value.addEventListener('mousemove', onPinHover)
    el.value.addEventListener('mouseleave', () => { hoverKey = null; closeTip() })
  }
  kakao.maps.event.addListener(map, 'zoom_changed', onZoomChanged)
  kakao.maps.event.addListener(map, 'center_changed', onCenterChanged)
  kakao.maps.event.addListener(map, 'idle', onIdle)
  addEventListener('resize', onResize)

  Object.assign(mapBridge, {
    ready: true,
    // 카드(필터·상세·코스)가 덮지 않는 영역의 가운데로 옮긴다 - 화면 정중앙에 두면 1280px 폭에선 중앙(640)이 상세 패널
    // 오른쪽 가장자리(664) 뒤라 선택 핀이 가려졌다. 상세 패널은 다음 틱에 그려지므로 한 틱 뒤에 잰다(2026-09-14)
    panTo: (lat, lng) => nextTick(() => {
      const ins = coveredInsets()
      const proj = map.getProjection()
      const p = proj.containerPointFromCoords(LL(lat, lng))
      map.panTo(proj.coordsFromContainerPoint(new kakao.maps.Point(p.x - ins.left / 2, p.y + ins.bottom / 2)))
    }),
    zoomTo: lv => { if (map.getLevel() > lv) map.setLevel(lv) },
    fitRegion, fitPoints,
    relayout: onResize,
  })
  fitRegion()
  syncLabelVisibility()
  draw()
})

onBeforeUnmount(() => {
  removeEventListener('resize', onResize)
  if (map) {
    kakao.maps.event.removeListener(map, 'zoom_changed', onZoomChanged)
    kakao.maps.event.removeListener(map, 'center_changed', onCenterChanged)
    kakao.maps.event.removeListener(map, 'idle', onIdle)
  }
  clearOverlays()
  mapBridge.ready = false
})

/* 상태가 바뀌면 다시 적용한다. 지도 자체는 새로 만들지 않는다 */
watch(() => [state.di, state.sel, state.course, state.courseDay, state.F.reg, state.F.cat,
  ...Object.values(state.L), state.favIds], draw, { deep: true })   // favIds: 찜/해제 즉시 핀 아이콘이 ♥ 로 바뀌게
/* 레이어 배열 자체가 바뀔 때(진입·칩 재요청·재진입 재적재)도 본다 - 장소는 API로 비동기로 오므로 지도가 먼저 뜨고
   데이터가 나중에 도착한다. 이걸 빼면 첫 렌더 때 빈 배열로 그린 뒤 다시 그리지 않아 지도에 핀이 하나도 안 찍힌다.
   forecastVersion 도 함께 본다 - 예보는 장소보다 늦게 도착해 series 를 뒤늦게 채운다.
   예전엔 forecastDays(일수)를 봤는데 재진입 땐 22→22 로 값이 같아 안 깨어났고, 그 사이 새로 받은 장소 객체로
   그린 핀은 예보가 붙은 뒤에도 회색으로 굳었다(클릭해야 색이 돌아오던 버그) */
watch(() => [state.layers, ...Object.values(state.layers), state.forecastVersion], draw)
</script>

<template>
  <div id="map" ref="el">
    <!-- 카카오 SDK 실패 안내. 사용자 행동(새로고침)을 먼저, 개발자용 단서(도메인·제품 설정)는 아래 작게 -
         배포 직후 도메인 미등록 때 제일 먼저 보는 화면이라 origin 은 남긴다(최종점검 #50·#52) -->
    <div v-if="failed" class="map-fail" role="alert">
      <b>지도를 불러오지 못했어요</b>
      <span>{{ failed }} · 인터넷 연결을 확인하고 다시 시도해 주세요</span>
      <button type="button" @click="reload">새로고침</button>
      <small>계속 안 되면 카카오 개발자 콘솔에 <b>{{ origin }}</b> 도메인이 등록됐는지,
        제품 설정 &gt; 카카오맵이 켜져 있는지 확인해 주세요.</small>
    </div>
  </div>
</template>
