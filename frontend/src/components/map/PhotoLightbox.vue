<script setup>
/* 사진 확대 보기(관광공사 사진·후기 사진 공용) — 여러 장이면 좌우로 넘기고, 배경·×·Esc로 닫는다.
   사진마다 설명(alt)과 출처를 받는다 - 전엔 전부 '후기 사진'이라 읽히고 공공누리 출처가 전면 화면에서 사라졌다(최종점검 #44·#45).
   원본이 404면 어두운 화면만 남던 것을 안내 문구로(#46) */
import { ref, computed, onMounted, onBeforeUnmount } from 'vue'

const photos = ref([])
const alts = ref([])
const source = ref('')
const index = ref(0)
const failed = ref(false)
const open = computed(() => photos.value.length > 0)
const many = computed(() => photos.value.length > 1)
const alt = computed(() => alts.value[index.value] || (many.value ? `후기 사진 ${index.value + 1}` : '후기 사진'))

/** list: 사진 URL 배열, i: 시작 장, opts.alts: 장마다 설명, opts.source: 출처 문구(관광공사 사진) */
function show(list, i, { alts: a = [], source: s = '' } = {}) {
  photos.value = list; alts.value = a; source.value = s; index.value = i; failed.value = false
}
function close() { photos.value = [] }
function move(d) { index.value = (index.value + d + photos.value.length) % photos.value.length; failed.value = false }

function onKey(e) {
  if (!open.value) return
  if (e.key === 'Escape') close()
  else if (e.key === 'ArrowLeft' && many.value) move(-1)
  else if (e.key === 'ArrowRight' && many.value) move(1)
}
onMounted(() => document.addEventListener('keydown', onKey))
onBeforeUnmount(() => document.removeEventListener('keydown', onKey))

defineExpose({ show, close })
</script>

<template>
  <Teleport to="body">
    <div v-if="open" class="map-lightbox" @click.self="close">
      <button v-if="many" class="lbx-nav prev" aria-label="이전 사진" @click.stop="move(-1)">‹</button>
      <img v-if="!failed" :src="photos[index]" :alt="alt" @error="failed = true">
      <div v-else class="lbx-fail" role="alert">사진을 불러오지 못했어요<small>원본이 없거나 연결이 끊겼어요</small></div>
      <button v-if="many" class="lbx-nav next" aria-label="다음 사진" @click.stop="move(1)">›</button>
      <button class="lbx-x" aria-label="닫기" @click="close">×</button>
      <div class="lbx-foot">
        <span v-if="many" class="lbx-cnt">{{ index + 1 }} / {{ photos.length }}</span>
        <span v-if="source" class="lbx-src">{{ source }}</span>
      </div>
    </div>
  </Teleport>
</template>

<style scoped>
.map-lightbox{position:fixed;inset:0;z-index:3000;background:rgba(10,16,24,.9);
  display:flex;align-items:center;justify-content:center;padding:44px 60px;cursor:zoom-out}
.map-lightbox img{max-width:100%;max-height:100%;object-fit:contain;border-radius:10px;
  box-shadow:0 18px 60px rgba(0,0,0,.55);cursor:default}
.map-lightbox button{color:#fff;opacity:.8}
.map-lightbox button:hover{opacity:1;background:rgba(255,255,255,.16)}
.lbx-x{position:absolute;top:14px;right:16px;font-size:27px;line-height:1;
  width:40px;height:40px;border-radius:50%}
.lbx-nav{position:absolute;top:50%;transform:translateY(-50%);font-size:32px;line-height:1;
  width:44px;height:66px;border-radius:12px}
.lbx-nav.prev{left:12px}
.lbx-nav.next{right:12px}
.lbx-foot{position:absolute;bottom:20px;left:60px;right:60px;display:flex;justify-content:center;align-items:center;gap:8px;
  flex-wrap:wrap;pointer-events:none}
.lbx-cnt,.lbx-src{color:#fff;font-size:12.5px;font-weight:700;background:rgba(0,0,0,.5);padding:5px 15px;border-radius:var(--rp)}
.lbx-src{font-weight:600;opacity:.92}   /* 공공누리 출처 - 전면 화면에서도 보인다(#45) */
.lbx-fail{display:flex;flex-direction:column;align-items:center;gap:6px;color:#fff;font-size:15px;font-weight:700;cursor:default}
.lbx-fail small{font-size:12px;font-weight:500;opacity:.75}
@media(max-width:768px){
  .map-lightbox{padding:52px 12px}
  .lbx-foot{left:12px;right:12px}
  .lbx-nav{width:38px;height:56px;font-size:26px}
  .lbx-nav.prev{left:2px}
  .lbx-nav.next{right:2px}
}
</style>
