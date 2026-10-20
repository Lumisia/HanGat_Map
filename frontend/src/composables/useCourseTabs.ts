import { ref } from 'vue'

export function useCourseTabs() {
  const opened = ref<Array<{ id: string; title: string }>>([])
  const active = ref('')
  function open(tab: { id: string; title: string }) {
    if (!opened.value.some(item => item.id === tab.id)) opened.value.push(tab)
    active.value = tab.id
  }
  function close(id: string) {
    const index = opened.value.findIndex(tab => tab.id === id)
    if (index < 0) return
    opened.value.splice(index, 1)
    if (active.value === id) active.value = opened.value[Math.max(0, index - 1)]?.id ?? ''
  }
  return { opened, active, open, close, showList: () => { active.value = '' } }
}
