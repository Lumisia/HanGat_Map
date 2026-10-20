<script setup>
/**
 * 테마 상세의 장소 카드 - 사진 · 이름 · 세부분류 · 읍면. 누르면 장소 소개 페이지(/places/:id).
 * 목록 API 에는 사진이 없어서 카드가 화면 가까이 오면 상세를 한 번 받아 첫 사진(축소본)을 붙인다.
 * 축소본(_image3_)이 없는 사진이 있다(큰노꼬메오름 등, 공사 서버 404) - 그러면 원본으로 바꿔 끼우고, 그것도 안 되면 글자 자리로.
 * 사진이 없는 장소는 묶음 색 바탕에 묶음 아이콘을 연하게 - "사진이 없다"가 아니라 "이런 종류다"로 읽히게.
 * 착한가격 식당(행안부 자료라 사진이 없음)은 대표 메뉴와 가격을 사진 자리에 - 사진보다 쓸모 있는 정보(2026-09-18 사용자 결정).
 */
import { onBeforeUnmount, onMounted, ref } from 'vue'
import MapPlaceService from '../../services/map/MapPlaceService'
import { displayName } from '../../config/themes.js'
import { firstMenu } from '../../utils/intro.js'

const props = defineProps({
  place: { type: Object, required: true },
  /** 묶음 아이콘·색 - 사진 없는 카드의 자리에 쓴다 */
  emoji: { type: String, default: '' },
  tint: { type: String, default: '' }
})
const el = ref(null)
const photo = ref(null)
const original = ref(null)   // 축소본이 깨지면 이걸로 한 번 더
const menu = ref(null)       // 착한가격 대표 메뉴 { n, p }
const loaded = ref(false)
let io = null

function onError () {
  if (original.value && photo.value !== original.value) photo.value = original.value
  else photo.value = null
}

async function load () {
  if (loaded.value) return
  loaded.value = true
  if (props.place.id == null) return
  const d = await MapPlaceService.getDetail(props.place.id).catch(() => null)
  const first = d?.images?.[0]
  original.value = first?.url ?? null
  photo.value = first?.thumb ?? original.value
  menu.value = props.place.good ? firstMenu(d?.overview) : null
}

onMounted(() => {
  if (typeof IntersectionObserver === 'undefined') { load(); return }
  io = new IntersectionObserver(entries => {
    if (entries.some(e => e.isIntersecting)) { load(); io.disconnect() }
  }, { rootMargin: '300px 0px 300px 0px' })
  io.observe(el.value)
})
onBeforeUnmount(() => io?.disconnect())
</script>

<template>
  <li ref="el" class="pcard">
    <RouterLink :to="`/places/${place.id}`" class="pcard-a">
      <div class="ph" :class="{ none: loaded && !photo }" :style="loaded && !photo && tint ? { background: tint } : null">
        <img v-if="photo" :src="photo" :alt="`${place.n} 사진`" loading="lazy" decoding="async" @error="onError">
        <template v-else-if="loaded">
          <span v-if="emoji" class="ph-emoji" aria-hidden="true">{{ emoji }}</span>
          <span v-if="menu" class="ph-menu"><b>{{ menu.n }}</b><em v-if="menu.p">{{ menu.p }}</em></span>
          <span v-else class="ph-none">{{ displayName(place.c) }}</span>
          <span class="ph-soon">이미지 준비 중입니다</span>
        </template>
      </div>
      <b>{{ place.n }}</b>
      <small>{{ [displayName(place.c), place.unit ?? place.r].filter(Boolean).join(' · ') }}</small>
    </RouterLink>
  </li>
</template>

<style scoped>
.pcard{list-style:none}
.pcard-a{display:block;color:inherit;text-decoration:none;border-radius:14px;outline:none}
.pcard-a:focus-visible{outline:2px solid var(--ac);outline-offset:3px}
.ph{aspect-ratio:4/3;border-radius:14px;overflow:hidden;background:var(--surf2);display:flex;align-items:center;justify-content:center;margin-bottom:8px;
  transition:transform .15s,box-shadow .15s}
.pcard-a:hover .ph{transform:translateY(-2px);box-shadow:var(--sh)}
.ph img{width:100%;height:100%;object-fit:cover;display:block}
.ph.none{flex-direction:column;gap:6px}
.ph-emoji{font-size:34px;line-height:1;opacity:.55;filter:saturate(.8)}
.ph-none{font-size:12px;font-weight:700;color:var(--tx3)}
.ph-menu{display:flex;flex-direction:column;align-items:center;gap:2px;padding:0 10px;text-align:center}
.ph-menu b{font-size:13px;font-weight:800;color:var(--tx);letter-spacing:-.02em;word-break:keep-all;line-height:1.3}
.ph-menu em{font-style:normal;font-size:12.5px;font-weight:700;color:var(--ac-dk)}
.ph-soon{font-size:10.5px;font-weight:500;color:var(--tx3);opacity:.8;margin-top:2px}
.pcard b{display:block;font-size:14px;font-weight:800;letter-spacing:-.02em;color:var(--tx);line-height:1.35;word-break:keep-all}
.pcard small{display:block;font-size:11.5px;color:var(--tx3);margin-top:2px}
</style>
