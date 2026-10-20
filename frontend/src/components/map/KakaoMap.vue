<script setup lang="ts">
import { onBeforeUnmount, onMounted, ref, watch } from 'vue'
import type { Place } from '../../assets/types'
import { loadKakaoMap } from '../../services/map/KakaoMapLoader'
import type { KakaoCustomOverlay, KakaoLatLng, KakaoMapInstance, KakaoMaps, KakaoPolyline } from '../../services/map/kakao.types'

const props = withDefaults(defineProps<{ places: Place[]; selectedId?: string; showRoute?: boolean }>(), { showRoute: false })
const emit = defineEmits<{ select: [place: Place]; error: [] }>()
const container = ref<HTMLElement>()
let active = true
let map: KakaoMapInstance | undefined
let maps: KakaoMaps | undefined
let overlays: KakaoCustomOverlay[] = []
let polyline: KakaoPolyline | undefined
let resizeObserver: ResizeObserver | undefined

const valid = (place: Place): place is Place & { latitude: number; longitude: number } => (
  Boolean(place.latitude && place.longitude && Number.isFinite(place.latitude) && Number.isFinite(place.longitude))
)

function clear () {
  overlays.forEach(overlay => overlay.setMap(null))
  overlays = []
  polyline?.setMap(null)
  polyline = undefined
}

function selectedContent (place: Place) {
  const content = document.createElement('button')
  content.type = 'button'
  content.className = 'kakao-selected-place'
  content.setAttribute('aria-label', `${place.name} 선택됨`)

  const label = document.createElement('span')
  label.className = 'kakao-selected-place__label'
  label.textContent = place.name

  const pin = document.createElement('span')
  pin.className = 'kakao-selected-place__pin'
  pin.setAttribute('aria-hidden', 'true')

  content.append(label, pin)
  return content
}

function defaultContent (place: Place) {
  const content = document.createElement('button')
  content.type = 'button'
  content.className = `kakao-marker ${place.congestionUnknown ? 'unknown' : place.level.toLowerCase()}`
  // 순번 핀도 혼잡도는 읽어 준다 - 색만으로는 스크린리더가 등급을 알 수 없다
  const congestion = place.congestionUnknown ? '혼잡 정보 없음' : `혼잡도 ${place.score}`
  content.setAttribute('aria-label', `${place.name} ${place.pinDescription ?? ''} · ${congestion}`)

  // 코스 지도처럼 핀에 순번을 다는 화면은 pinLabel을 준다 - 집중률 원값(21.88)은 보는 사람이 뜻을 알 수 없다
  const badge = document.createElement('span')
  badge.textContent = place.pinLabel ?? String(place.score)
  content.append(badge, document.createTextNode(place.name))
  return content
}

function render () {
  if (!map || !maps) return
  clear()

  const list = props.places.filter(valid)
  const bounds = new maps.LatLngBounds()
  let selectedPosition: KakaoLatLng | undefined

  list.forEach(place => {
    const position = new maps!.LatLng(place.latitude, place.longitude)
    const isSelected = place.id === props.selectedId
    bounds.extend(position)
    if (isSelected) selectedPosition = position

    const content = isSelected ? selectedContent(place) : defaultContent(place)
    content.addEventListener('click', () => {
      emit('select', place)
      map?.panTo(position)
    })

    overlays.push(new maps!.CustomOverlay({
      map: map!,
      position,
      content,
      yAnchor: 1,
      zIndex: isSelected ? 10 : 1,
    }))
  })

  if (list.length > 1) map.setBounds(bounds)
  else if (list.length === 1) {
    map.setCenter(new maps.LatLng(list[0].latitude, list[0].longitude))
    map.setLevel(6)
  }
  if (selectedPosition) map.panTo(selectedPosition)

  if (props.showRoute && list.length > 1) {
    polyline = new maps.Polyline({
      path: list.map(place => new maps!.LatLng(place.latitude, place.longitude)),
      strokeWeight: 5,
      strokeColor: '#1F7A6D',
      strokeOpacity: .8,
      strokeStyle: 'solid',
    })
    polyline.setMap(map)
  }
}

onMounted(async () => {
  try {
    maps = await loadKakaoMap()
    if (!active || !container.value) return
    map = new maps.Map(container.value, { center: new maps.LatLng(33.38, 126.53), level: 10 })
    render()
    resizeObserver = new ResizeObserver(() => {
      if (!active) return
      map?.relayout()
      render()
    })
    resizeObserver.observe(container.value)
  } catch {
    if (active) emit('error')
  }
})

watch(() => [props.places, props.selectedId, props.showRoute], render, { deep: true })
onBeforeUnmount(() => {
  active = false
  resizeObserver?.disconnect()
  clear()
  map = undefined
})
</script>
<template>
  <div
    ref="container"
    class="kakao-map"
    aria-label="제주 관광지 혼잡도 지도"
  />
</template>
<style>
.kakao-marker.unknown span { background: #78858a; }
</style>
