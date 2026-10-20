<script setup lang="ts">
import { ref, onMounted, onBeforeUnmount, nextTick } from 'vue'
import AppIcon from '../common/AppIcon.vue'
defineProps<{ title: string; disabled?: boolean }>()
const emit = defineEmits<(event: 'rename' | 'share' | 'delete') => void>()
const open = ref(false), root = ref<HTMLElement>(), trigger = ref<HTMLButtonElement>()
async function toggle() { open.value = !open.value; if (open.value) { await nextTick(); root.value?.querySelector<HTMLButtonElement>('[role="menuitem"]')?.focus() } }
function close() { open.value = false; trigger.value?.focus() }
function outside(e: Event) { if (!root.value?.contains(e.target as Node)) open.value = false }
function action(name: 'rename' | 'share' | 'delete') { close(); emit(name) }
function key(e: KeyboardEvent) {
  if (e.key === 'Escape') { e.preventDefault(); close() }
  if (!['ArrowDown', 'ArrowUp', 'Home', 'End'].includes(e.key)) return
  e.preventDefault()
  const items = [...root.value!.querySelectorAll<HTMLButtonElement>('[role="menuitem"]')]
  const index = items.indexOf(document.activeElement as HTMLButtonElement)
  items[e.key === 'Home' ? 0 : e.key === 'End' ? items.length - 1 : (index + (e.key === 'ArrowUp' ? -1 : 1) + items.length) % items.length]?.focus()
}
onMounted(() => document.addEventListener('pointerdown', outside))
onBeforeUnmount(() => document.removeEventListener('pointerdown', outside))
</script>
<template>
  <div ref="root" class="course-menu" @keydown="key" @focusout="e => { if (!root?.contains(e.relatedTarget as Node)) open = false }">
    <button ref="trigger" type="button" class="more-button" :disabled="disabled" :aria-label="`${title} 메뉴`" title="코스 메뉴" aria-haspopup="menu" :aria-expanded="open" @click="toggle">⋯</button>
    <div v-if="open" class="menu-items" role="menu" :aria-label="`${title} 관리`">
      <button type="button" role="menuitem" @click="action('rename')"><AppIcon name="edit" :size="17" />이름 변경</button>
      <button type="button" role="menuitem" @click="action('share')"><AppIcon name="share" :size="17" />공유</button>
      <button type="button" role="menuitem" class="danger" @click="action('delete')"><AppIcon name="trash" :size="17" />삭제</button>
    </div>
  </div>
</template>
<style scoped>
.course-menu{position:absolute;right:10px;top:8px;z-index:2}.course-menu:focus-within{z-index:5}.more-button{display:grid;place-items:center;width:40px;height:40px;border:0;background:transparent;color:var(--tx2);font-size:26px;border-radius:9px;cursor:pointer}.more-button:hover{background:var(--surf2)}.menu-items{position:absolute;right:0;top:42px;min-width:150px;padding:5px;border:1px solid var(--line);border-radius:12px;background:var(--surface);box-shadow:0 8px 24px #0002}.menu-items button{display:flex;align-items:center;gap:10px;width:100%;min-height:42px;padding:10px;border:0;border-radius:8px;background:transparent;color:var(--text);font-size:13px;text-align:left;cursor:pointer}.menu-items button:hover{background:var(--surf2)}.menu-items .danger{color:#bf3434}button:focus-visible{outline:2px solid var(--primary);outline-offset:2px}
</style>
