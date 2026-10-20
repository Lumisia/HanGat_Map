<script setup>
/** 원형으로 보일 영역을 이동·확대하고, 같은 영역을 정사각형 PNG로 내보낸다. */
import { computed, ref } from 'vue'
import { cropGeometry } from '../../utils/profileCrop.js'

const props = defineProps({ src: { type: String, required: true }, disabled: Boolean })
const emit = defineEmits(['ready', 'error'])
const photo = ref(null)
const dimensions = ref(null)
const zoom = ref(1)
const offset = ref({ x: 0, y: 0 })
let drag = null
const geometry = computed(() => dimensions.value
  ? cropGeometry(dimensions.value.width, dimensions.value.height, zoom.value, offset.value.x, offset.value.y) : null)
const imageStyle = computed(() => {
  const g = geometry.value
  if (!g) return { visibility: 'hidden' }
  return {
    width: `${dimensions.value.width * g.scale / 256 * 100}%`,
    height: `${dimensions.value.height * g.scale / 256 * 100}%`,
    left: `${50 + g.x / 256 * 100}%`, top: `${50 + g.y / 256 * 100}%`
  }
})

function loaded () {
  try {
    const { naturalWidth: width, naturalHeight: height } = photo.value
    cropGeometry(width, height, 1, 0, 0)
    dimensions.value = { width, height }
    emit('ready', true)
  } catch (e) { failed(e.message) }
}
function failed (message = '사진을 읽지 못했어요. 다른 파일을 선택해 주세요.') {
  dimensions.value = null
  emit('ready', false)
  emit('error', message)
}
function move (x, y) {
  if (!geometry.value) return
  const g = cropGeometry(dimensions.value.width, dimensions.value.height, zoom.value, x, y)
  offset.value = { x: g.x, y: g.y }
}
function setZoom (value) {
  if (props.disabled || !geometry.value) return
  const next = Math.max(1, Math.min(4, Number(value)))
  const ratio = next / zoom.value
  const { x, y } = geometry.value
  zoom.value = next
  move(x * ratio, y * ratio)
  drag = null
}
function reset () {
  if (props.disabled) return
  zoom.value = 1
  offset.value = { x: 0, y: 0 }
  drag = null
}
function startDrag (event) {
  if (props.disabled || !geometry.value || !event.isPrimary || event.button !== 0) return
  event.preventDefault()
  event.currentTarget.focus()
  event.currentTarget.setPointerCapture(event.pointerId)
  drag = { id: event.pointerId, startX: event.clientX, startY: event.clientY,
    x: geometry.value.x, y: geometry.value.y, ratio: 256 / event.currentTarget.getBoundingClientRect().width }
}
function dragPhoto (event) {
  if (props.disabled || !drag || drag.id !== event.pointerId) return
  move(drag.x + (event.clientX - drag.startX) * drag.ratio, drag.y + (event.clientY - drag.startY) * drag.ratio)
}
function moveByKey (event) {
  if (props.disabled || !geometry.value) return
  const steps = { ArrowLeft: [-1, 0], ArrowRight: [1, 0], ArrowUp: [0, -1], ArrowDown: [0, 1] }
  const step = steps[event.key]
  if (!step) return
  event.preventDefault()
  const amount = event.shiftKey ? 16 : 4
  move(geometry.value.x + step[0] * amount, geometry.value.y + step[1] * amount)
}

/** 화면에서 선택한 사각 영역만 업로드한다. 원형 마스크는 UI 표시용으로 저장하지 않는다. */
async function exportFile () {
  const g = geometry.value
  if (!g || !photo.value) throw new Error('사진을 먼저 불러와 주세요.')
  const canvas = document.createElement('canvas')
  canvas.width = canvas.height = 512
  const context = canvas.getContext('2d')
  if (!context) throw new Error('이 브라우저에서는 사진을 편집할 수 없어요.')
  context.imageSmoothingQuality = 'high'
  context.drawImage(photo.value, g.sx, g.sy, g.side, g.side, 0, 0, 512, 512)
  const blob = await new Promise(resolve => canvas.toBlob(resolve, 'image/png'))
  if (!blob || !blob.size || blob.size > 5 * 1024 * 1024) throw new Error('사진 변환에 실패했어요. 다른 사진으로 시도해 주세요.')
  return new File([blob], 'profile.png', { type: 'image/png' })
}
defineExpose({ exportFile })
</script>

<template>
  <div class="crop-editor">
    <div class="crop-window" tabindex="0" role="group" aria-label="프로필 사진 위치 조정"
      aria-describedby="crop-instructions" :aria-disabled="disabled"
      @pointerdown="startDrag" @pointermove="dragPhoto" @pointerup="drag = null"
      @pointercancel="drag = null" @lostpointercapture="drag = null" @keydown="moveByKey">
      <img ref="photo" :src="src" :style="imageStyle" alt="선택한 프로필 사진" draggable="false"
        @load="loaded" @error="failed()" />
      <div class="crop-mask" aria-hidden="true" />
    </div>
    <p id="crop-instructions" class="crop-note">사진을 드래그해 원 안에 맞춰 주세요.<br>방향키로도 위치를 조절할 수 있어요.</p>
    <div class="crop-zoom">
      <button type="button" aria-label="사진 축소" :disabled="disabled || !geometry || zoom <= 1" @click="setZoom(zoom - .1)">−</button>
      <input type="range" min="1" max="4" step="0.01" :value="zoom" aria-label="사진 확대 배율"
        :aria-valuetext="`${Math.round(zoom * 100)}%`" :disabled="disabled || !geometry"
        @input="setZoom($event.target.value)" />
      <button type="button" aria-label="사진 확대" :disabled="disabled || !geometry || zoom >= 4" @click="setZoom(zoom + .1)">+</button>
      <output>{{ Math.round(zoom * 100) }}%</output>
    </div>
    <button type="button" class="crop-reset" :disabled="disabled || !geometry" @click="reset">위치·크기 초기화</button>
  </div>
</template>

<style scoped>
.crop-editor { display: grid; justify-items: center; gap: 12px; width: 100%; }
.crop-window { position: relative; width: min(256px, 65vw); aspect-ratio: 1; overflow: hidden; background: var(--surf2); touch-action: none; cursor: grab; user-select: none; border-radius: 12px; }
.crop-window:active { cursor: grabbing; }
.crop-window img { position: absolute; max-width: none; transform: translate(-50%, -50%); pointer-events: none; }
.crop-mask { position: absolute; inset: 0; border: 2px solid white; border-radius: 50%; box-shadow: 0 0 0 100px rgb(0 0 0 / .5); pointer-events: none; }
.crop-note { margin: 0; text-align: center; font-size: 12px; color: var(--tx2); line-height: 1.6; }
.crop-zoom { display: flex; align-items: center; gap: 10px; width: min(320px, 100%); }
.crop-zoom input { min-width: 0; flex: 1; accent-color: var(--ac); }
.crop-zoom button { width: 36px; height: 36px; flex-shrink: 0; border-radius: 50%; background: var(--surf2); font-size: 22px; color: var(--ac); }
.crop-zoom output { min-width: 40px; font-size: 12px; font-variant-numeric: tabular-nums; color: var(--tx2); }
.crop-reset { color: var(--ac); font-size: 12px; padding: 6px 10px; text-decoration: underline; }
button:disabled { opacity: .45; cursor: default; }
.crop-window:focus-visible, button:focus-visible, input:focus-visible { outline: 2px solid var(--ac); outline-offset: 3px; }
</style>
