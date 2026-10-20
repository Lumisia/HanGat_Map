<script setup>
/** 리뷰 작성자 사진. 사진이 없거나 읽지 못하면 기존 닉네임 첫 글자를 표시한다. */
import { computed, ref, watch } from 'vue'
import { publicProfileImageUrl } from '../../utils/profileImage.js'

const props = defineProps({
  src: { type: String, default: null },
  nickname: { type: String, default: null }
})
const failed = ref(false)
const imageUrl = computed(() => publicProfileImageUrl(props.src))
const initial = computed(() => (props.nickname?.trim() || '여')[0])
watch(() => props.src, () => { failed.value = false })
</script>

<template>
  <span class="profile-avatar" aria-hidden="true">
    <img v-if="imageUrl && !failed" :key="imageUrl" :src="imageUrl" alt="" loading="lazy"
      decoding="async" referrerpolicy="no-referrer" @error="failed = true" />
    <span v-else>{{ initial }}</span>
  </span>
</template>

<style scoped>
.profile-avatar { display: inline-flex; align-items: center; justify-content: center; width: 22px; height: 22px; flex-shrink: 0; border-radius: 50%; overflow: hidden; background: var(--ac-bg); color: var(--ac-dk); font-size: 10.5px; font-weight: 800; }
.profile-avatar img { display: block; width: 100%; height: 100%; object-fit: cover; }
</style>
