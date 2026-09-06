<script setup>
import { ref, onMounted, onBeforeUnmount, watch } from 'vue'
import { loadKakaoMap } from '@/composables/useKakaoLoader'
import { mapBridge } from '@/composables/mapBridge'
import { state, inFilter, inRegion } from '@/stores/mapStore'
import MapPlaceService, { hasCoords } from '@/services/map/MapPlaceService'

import { crowd, tier } from '@/utils/crowd'
import { cssVar } from '@/utils/geo'
import { POI_MARKER_CLASS, shouldShowMapLabels } from './mapPresentation'

const emit = defineEmits(['select', 'blank-click'])

const el = ref(null)
const failed = ref('')
const origin = location.origin
let map = null
/* 오버레이는 반응형일 필요가 없어 ref 바깥에 둔다 */
const OV = { spot: [], food: [], dine: [], cafe: [], cvs: [], stay: [], mart: [], route: [], num: [], sel: [] }

/** 선택 핀의 업종색 - 지도 마커와 같은 팔레트 */
const CAT_MARKER = { FOOD: 'mk-dine', CAFE: 'mk-cafe', CONVENIENCE: 'mk-cvs', LODGING: 'mk-stay', MART: 'mk-mart' }

const LL = (lat, lng) => new kakao.maps.LatLng(lat, lng)

function clearOverlays() {
  closeTip()
  for (const k in OV) { OV[k].forEach(o => o.setMap(null)); OV[k].length = 0 }
}

/* MAP_001 착한가격 클릭 툴팁 - 한 번에 하나만 띄운다 */
let tip = null

function closeTip() {
  if (tip) { tip.setMap(null); tip = null }
}

/** 메뉴·가격은 목록엔 없고 상세 응답(overview)에만 있어 클릭 시점에 받아온다 */
async function showGoodPriceTip(f) {
  closeTip()
  const node = document.createElement('div')
  node.className = 'gp-tip'
  const render = body => {
    node.innerHTML = `<b>${f.n}</b>${body}<button class="gp-more">상세 보기</button>`
    node.querySelector('.gp-more').addEventListener('click', () => { closeTip(); emit('select', f) })
  }
  render('<span>메뉴 불러오는 중…</span>')
  node.addEventListener('click', e => e.stopPropagation())
  const my = new kakao.maps.CustomOverlay({
    position: LL(f.y, f.x), content: node, yAnchor: 1.3, zIndex: 500, clickable: true,
  })
  my.setMap(map)
  tip = my
  const d = f.id != null ? await MapPlaceService.getDetail(f.id) : null
  if (tip !== my) return   // 기다리는 사이 닫혔거나 다른 핀으로 바뀜
  render(d?.overview
    ? d.overview.replace(/^대표메뉴:\s*/, '').split(' · ').map(m => `<span>${m}</span>`).join('')
    : '<span>메뉴 정보 없음</span>')
}

function syncLabelVisibility() {
  if (!map || !el.value) return
  el.value.classList.toggle('labels-visible', shouldShowMapLabels(map.getLevel()))
}

const onZoomChanged = () => syncLabelVisibility()

/** 핀 하나 = CustomOverlay 하나 (라벨은 같은 오버레이 안에 겹쳐 넣는다) */
function addPin(group, lat, lng, html, onClick, z) {
  const node = document.createElement('div')
  node.className = 'pw'
  node.innerHTML = html
  const ov = new kakao.maps.CustomOverlay({
    position: LL(lat, lng), content: node,
    yAnchor: 0.5, xAnchor: 0.5, zIndex: z || 0, clickable: !!onClick,
  })
  ov.setMap(map)
  OV[group].push(ov)
  if (onClick) node.addEventListener('click', e => { e.stopPropagation(); onClick() })
}

function draw() {
  if (!map) return
  clearOverlays()
  const { di, sel, course, courseDay, L } = state
  const inCourse = n => course && course.stops.some(s => s.o && s.o.n === n)

  // 좌표 없는 장소는 지도에 못 찍는다 - KTO 원본에 좌표 오류가 있어 null로 저장된 건이 있다
  if (L.spot) state.layers.spot.filter(hasCoords).forEach(s => {
    const c = crowd(s, di), on = inFilter(s), t = tier(c)
    const pick = inCourse(s.n) || (sel && sel.n === s.n)
    const sz = pick ? 20 : on ? 15 : 9
    const cls = `pn ${L.crowd ? t : 'calm'}${pick ? ' pick' : ''}${on ? '' : ' dim'}`
    addPin('spot', s.y, s.x,
      `${on ? `<div class="lb-t">${s.n}</div>` : ''}
       <div class="${cls}" style="width:${sz}px;height:${sz}px"></div>`,
      () => emit('select', s), pick ? 400 : on ? 200 : 100)
  })

  const poi = (g, list) => {
    if (!L[g]) return
    // 착한가격은 정의서(MAP_001)대로 툴팁, 나머지 업종은 관광지처럼 상세 패널
    list.filter(f => inRegion(f) && hasCoords(f)).forEach(f => addPin(g, f.y, f.x,
      `<div class="lb-t">${f.n}</div><div class="poi-marker ${POI_MARKER_CLASS[g]}"></div>`,
      g === 'food' ? () => showGoodPriceTip(f) : () => emit('select', f), 60))
  }
  poi('food', state.layers.food)
  poi('dine', state.layers.dine)
  poi('cafe', state.layers.cafe)
  poi('cvs', state.layers.cvs)
  poi('stay', state.layers.stay)
  poi('mart', state.layers.mart)

  // 검색 등으로 연 장소는 레이어가 꺼져 있어도 선택 핀을 띄운다 (MAP_002) -
  // 지도가 이동만 하고 아무것도 안 보이면 고장으로 느껴진다. 이름표는 줌 무관 항상 표시.
  // 코스 정류지는 제외 - 번호 핀이 이미 그 자리를 표시하고, 겹치면 이름표가 두 장 뜬다
  if (sel && hasCoords(sel) && !(L.spot && state.layers.spot.includes(sel))
      && !(course && course.stops.some(cs => cs.o === sel))) {
    const pin = sel.cat === 'TOURIST'
      ? `<div class="pn ${L.crowd ? tier(crowd(sel, di)) : 'calm'} pick" style="width:20px;height:20px"></div>`
      : `<div class="poi-marker sel-pick ${sel.good ? 'mk-food' : (CAT_MARKER[sel.cat] ?? 'mk-dine')}"></div>`
    addPin('sel', sel.y, sel.x, `<div class="lb-t sel-on">${sel.n}</div>` + pin, () => emit('select', sel), 500)
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
        OV.route.push(line)
      }
      // 코스 핀도 눌러서 이름·상세를 본다 - 코스가 화면의 주인공이라 이름표는 줌 무관 상시 표시
      if (on) g[d].forEach((stop, i) => addPin('num', stop.o.y, stop.o.x,
        `<div class="lb-t sel-on">${stop.o.n}</div><div class="mk-num${sel === stop.o ? ' pick' : ''}">${i + 1}</div>`,
        () => emit('select', stop.o), 600))
    })
  }
}

/** 좌측 카드·우측 패널에 가리지 않도록 여백을 주고 맞춘다 (화면이 좁으면 비율로 축소) */
function fitPoints(pts, minLevel) {
  if (!map || !pts?.length) return
  const W = el.value.clientWidth, H = el.value.clientHeight
  if (!W || !H) return
  if (pts.length === 1) {
    map.setCenter(LL(pts[0][0], pts[0][1]))
    if (map.getLevel() > (minLevel || 6)) map.setLevel(minLevel || 6)
    return
  }
  const b = new kakao.maps.LatLngBounds()
  pts.forEach(p => b.extend(LL(p[0], p[1])))
  const left = Math.min(340, Math.round(W * 0.26)), right = Math.min(380, Math.round(W * 0.28))
  const top = Math.min(90, Math.round(H * 0.14)), bottom = Math.min(130, Math.round(H * 0.18))
  map.setBounds(b, top, right, bottom, left)
}

function fitRegion() {
  if (!map) return
  const ss = state.layers.spot.filter(s => (state.F.reg === '전체' || s.r === state.F.reg) && hasCoords(s))
  if (!ss.length) return
  if (state.F.reg === '전체') { map.setCenter(LL(33.383, 126.55)); map.setLevel(10); return }
  fitPoints(ss.map(s => [s.y, s.x]))
}

const onResize = () => map && map.relayout()

onMounted(async () => {
  try {
    await loadKakaoMap()
  } catch (e) {
    failed.value = e.message
    return
  }
  map = new kakao.maps.Map(el.value, { center: LL(33.383, 126.55), level: 10 })
  map.setMinLevel(1)
  map.setMaxLevel(13)
  map.addControl(new kakao.maps.ZoomControl(), kakao.maps.ControlPosition.BOTTOMRIGHT)
  kakao.maps.event.addListener(map, 'click', () => { closeTip(); emit('blank-click') })
  kakao.maps.event.addListener(map, 'zoom_changed', onZoomChanged)
  addEventListener('resize', onResize)

  Object.assign(mapBridge, {
    ready: true,
    panTo: (lat, lng) => map.panTo(LL(lat, lng)),
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
  if (map) kakao.maps.event.removeListener(map, 'zoom_changed', onZoomChanged)
  clearOverlays()
  mapBridge.ready = false
})

/* 상태가 바뀌면 다시 그린다. 지도 자체는 새로 만들지 않는다 */
/* state.layers 를 함께 본다 - 장소는 API로 비동기로 오므로 지도가 먼저 뜨고 데이터가 나중에 도착한다.
   이걸 빼면 첫 렌더 때 빈 배열로 그린 뒤 다시 그리지 않아 지도에 핀이 하나도 안 찍힌다.
   레이어별 길이만 보면 되므로(내용 비교는 2천 건이라 비싸다) deep 감시 대상에서 분리한다 */
watch(() => [state.di, state.sel, state.course, state.courseDay, state.F.reg, state.F.cat,
  ...Object.values(state.L)], draw, { deep: true })
/* forecastDays 도 함께 본다 - 예보는 장소보다 늦게 도착해 series 를 뒤늦게 채운다.
   배열 길이는 그대로라 이걸 빼면 핀이 첫 렌더 상태(전부 회색 '정보 없음')로 굳는다.
   좌측 목록은 computed 라 알아서 갱신되므로 목록만 색이 맞고 지도는 회색인 상태가 되어 알아채기 어렵다 */
watch(() => [Object.values(state.layers).map(list => list.length).join(','), state.forecastDays], draw)
</script>

<template>
  <div id="map" ref="el">
    <div v-if="failed" class="map-fail">
      지도를 불러오지 못했어요.
      <span>{{ failed }}<br>
        카카오 개발자 콘솔에 <b>{{ origin }}</b> 도메인이 등록됐는지,
        제품 설정 &gt; 카카오맵이 켜져 있는지 확인해 주세요.</span>
    </div>
  </div>
</template>
