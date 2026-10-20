<script setup>
import illustration from '../../assets/images/unavailable-404.png'
defineProps({ title: { type: String, required: true }, description: { type: String, required: true } })
</script>
<template>
  <section class="unavailable-card" role="status">
    <img class="illustration" :src="illustration" alt="" width="517" height="452" />
    <h1>{{ title }}</h1>
    <p class="description">{{ description }}</p>
    <div class="actions"><slot /></div>
  </section>
</template>
<style scoped>
.unavailable-card { width: 100%; max-width: 440px; box-sizing: border-box; margin: 0 auto; padding: 32px 24px 30px; background: var(--surf); color: var(--tx); border: 1px solid var(--line); border-radius: 24px; box-shadow: var(--sh-soft); text-align: center; }
.illustration { display: block; width: 160px; max-width: 60%; height: auto; object-fit: contain; margin: 0 auto 18px; }
h1 { font-size: 19px; font-weight: 800; letter-spacing: -.035em; line-height: 1.5; margin: 0 0 6px; }
.description { font-size: 12.5px; color: var(--tx2); line-height: 1.65; margin: 0; overflow-wrap: anywhere; }
.actions { display: flex; flex-wrap: wrap; gap: 8px; margin-top: 18px; }
.actions :deep(a) { flex: 1 1 140px; min-height: 44px; display: flex; align-items: center; justify-content: center; text-align: center; }
.actions :deep(a:focus-visible) { outline: 2px solid var(--ac); outline-offset: 3px; }
@media (max-width: 480px) { .unavailable-card { padding: 28px 20px 24px; } }
</style>
