<script setup>
import { computed, onMounted, onBeforeUnmount, ref, watch } from 'vue'
import sunset from '../../assets/images/generation/wait-1.webp'
import drive from '../../assets/images/generation/wait-2.webp'
import statue from '../../assets/images/generation/wait-3.webp'
import photo from '../../assets/images/generation/wait-4.webp'
import success from '../../assets/images/generation/success.webp'
import failure from '../../assets/images/generation/failure.webp'
const props = defineProps({ status: { type: String, default: 'RUNNING' } })
const slides = [sunset, drive, statue, photo]
const index = ref(0)
const paused = ref(false)
const reduced = ref(false)
const pending = computed(() => ['QUEUED', 'RUNNING'].includes(props.status))
const resultImage = computed(() => props.status === 'SUCCEEDED' ? success : failure)
let timer, media, startX = null
function schedule() {
  clearInterval(timer)
  if (pending.value && !paused.value && !reduced.value && !document.hidden) {
    timer = setInterval(() => { index.value = (index.value + 1) % slides.length }, 3000)
  }
}
function motion() { reduced.value = media.matches; schedule() }
function swipe(event) {
  if (startX !== null && Math.abs(event.changedTouches[0].clientX - startX) > 45) {
    index.value = (index.value + (event.changedTouches[0].clientX < startX ? 1 : 3)) % 4
    schedule()
  }
  startX = null
}
watch([pending, paused], schedule)
onMounted(() => {
  media = window.matchMedia('(prefers-reduced-motion: reduce)')
  motion(); media.addEventListener('change', motion)
  document.addEventListener('visibilitychange', schedule)
  slides.forEach(src => { const image = new Image(); image.src = src })
})
onBeforeUnmount(() => {
  clearInterval(timer); media?.removeEventListener('change', motion)
  document.removeEventListener('visibilitychange', schedule)
})
</script>
<template>
  <div class="generation-art" :class="{ 'is-result': !pending }">
    <div v-if="pending" class="slide-window" aria-label="제주 여행 일러스트" @touchstart.passive="startX = $event.touches[0].clientX" @touchend.passive="swipe">
      <Transition name="travel-slide">
        <img :key="index" :src="slides[index]" alt="제주를 여행하는 한갓지도 캐릭터들" width="960" height="720">
      </Transition>
    </div>
    <img v-else :src="resultImage" :alt="status === 'SUCCEEDED' ? '여행 지도를 완성하는 캐릭터들' : '지도를 살펴보는 캐릭터들'" width="720" height="540">
    <div v-if="pending" class="slide-controls">
      <button v-for="(_, n) in slides" :key="n" type="button" :aria-label="`${n + 1}번째 그림`" :aria-pressed="index === n" @click="index = n; schedule()"><span :class="{ active: index === n }" /></button>
      <button type="button" class="pause" @click="paused = !paused" :disabled="reduced">{{ paused || reduced ? '재생' : '일시정지' }}</button>
    </div>
  </div>
</template>
<style scoped>
.generation-art { width: min(100%, 600px); margin: 0 auto 24px; }
.slide-window { position: relative; aspect-ratio: 4 / 3; overflow: hidden; border-radius: 18px; background: var(--surf2); touch-action: pan-y; }
.slide-window img { position: absolute; inset: 0; width: 100%; height: 100%; object-fit: contain; }
.is-result { max-width: 360px; }
.is-result > img { display: block; width: 100%; height: auto; }
.slide-controls { display: flex; align-items: center; justify-content: center; margin-top: 8px; }
.slide-controls button { min-width: 32px; min-height: 36px; display: grid; place-items: center; }
.slide-controls span { width: 6px; height: 6px; border-radius: 50%; background: var(--line2); }
.slide-controls .active { width: 20px; border-radius: 9px; background: var(--ac); }
.slide-controls .pause { font-size: 11px; color: var(--tx2); padding: 0 10px; }
button:focus-visible { outline: 2px solid var(--ac); border-radius: 8px; }
.travel-slide-enter-active, .travel-slide-leave-active { transition: transform .55s ease; }
.travel-slide-enter-from { transform: translateX(100%); }
.travel-slide-leave-to { transform: translateX(-100%); }
@media(prefers-reduced-motion: reduce) { .travel-slide-enter-active, .travel-slide-leave-active { transition: none; } }
</style>
