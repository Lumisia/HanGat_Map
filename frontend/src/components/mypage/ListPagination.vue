<script setup>
import { computed } from 'vue'
const props = defineProps({ page: { type: Number, default: 0 }, totalPages: { type: Number, default: 0 }, busy: Boolean, label: { type: String, required: true } })
defineEmits(['change'])
const pages = computed(() => {
  const start = Math.max(0, Math.min(props.page - 2, props.totalPages - 5))
  return Array.from({ length: Math.min(5, props.totalPages) }, (_, i) => start + i)
})
</script>
<template>
  <nav v-if="totalPages > 1" class="pagination" :aria-label="label">
    <button type="button" :disabled="busy || page <= 0" @click="$emit('change', page - 1)">이전</button>
    <button v-for="n in pages" :key="n" type="button" :aria-current="n === page ? 'page' : undefined" :disabled="busy" @click="$emit('change', n)">{{ n + 1 }}</button>
    <button type="button" :disabled="busy || page + 1 >= totalPages" @click="$emit('change', page + 1)">다음</button>
  </nav>
</template>
<style scoped>
.pagination { display: flex; flex-wrap: wrap; justify-content: center; gap: 4px; margin-top: 24px; }
button { min-width: 36px; min-height: 44px; padding: 0 8px; border-radius: 8px; color: var(--tx2); }
button[aria-current="page"] { background: var(--ac); color: var(--on-ac); }
button:disabled { opacity: .45; cursor: default; }
button:focus-visible { outline: 2px solid var(--ac); outline-offset: 2px; }
</style>
