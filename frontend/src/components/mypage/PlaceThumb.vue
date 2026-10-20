<script setup>
/**
 * 장소 대표 이미지 (MY_006).
 *
 * `src` 가 있으면 사진을, 없거나 못 불러오면 카테고리 색 자리표시자를 그린다.
 * 찜 카드의 imagePlaceholder 옵션은 메인과 동일한 이미지 준비 안내를 사용한다.
 * 가짜 사진을 끌어오지 않고, 사진이 붙어도 레이아웃이 변하지 않는 것이 목적이다.
 * 사진 출처: 백엔드 찜 목록의 imageUrl(장소 상세 첫 사진의 썸네일, 2026-09-07 연결).
 * 후기 탭처럼 아직 사진을 안 넘기는 곳은 예전과 똑같이 자리표시자만 보인다.
 */
import { computed, ref, watch } from 'vue'
import { CATEGORY_HUE } from '../../data/places.js'
import { PLACEHOLDER_IMAGE } from '../../utils/imageFallback'

const props = defineProps({
  category: { type: String, default: '' },
  name: { type: String, default: '' },
  size: { type: String, default: '72px' },
  radius: { type: String, default: '12px' },
  /** 대표사진 URL. 없으면 자리표시자 */
  src: { type: String, default: null },
  imagePlaceholder: { type: Boolean, default: false }
})

/* 사진이 깨지면(404·차단) 자리표시자로 돌아간다. src 가 바뀌면 다시 시도한다 */
const failed = ref(false)
watch(() => props.src, () => { failed.value = false })
const showImage = computed(() => !!props.src && !failed.value)

const hue = computed(() => CATEGORY_HUE[props.category] ?? 210)
/*
  사진 자리를 그라디언트 + 사선 무늬로 채우면 "목업"처럼 보인다.
  단색 톤 블록 하나로 두고, 카테고리 글자는 서비스 타이포 그대로 얹는다.
  사진이 붙었을 때 레이아웃이 변하지 않는 것이 목적이지, 예뻐 보이는 게 목적이 아니다.
*/
const style = computed(() => ({
  width: props.size,
  height: props.size,
  borderRadius: props.radius,
  background: `hsl(${hue.value} 34% 90%)`
}))
</script>

<template>
  <div class="thumb" :style="style"
    :title="showImage ? `${props.name} 대표 이미지` : `${props.name} 대표 이미지 (준비 중)`"
    :aria-hidden="showImage || imagePlaceholder ? null : 'true'">
    <img v-if="showImage" :src="props.src" :alt="`${props.name} 대표 이미지`" loading="lazy"
      @error="failed = true">
    <img v-else-if="imagePlaceholder" :src="PLACEHOLDER_IMAGE" :alt="`${props.name} 이미지 준비 중입니다`" loading="lazy">
    <span v-else class="cat">{{ props.category || '장소' }}</span>
  </div>
</template>

<style scoped>
.thumb {
  flex-shrink: 0;
  display: flex; align-items: center; justify-content: center;
  position: relative; overflow: hidden;
}
.thumb img {
  width: 100%; height: 100%; object-fit: cover; display: block;
}
.cat {
  font-size: 11px; font-weight: 700; letter-spacing: .04em;
  color: var(--tx2);
}
</style>
