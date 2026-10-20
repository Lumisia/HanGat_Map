import { defineStore } from 'pinia'

let seq = 0

export const useUiStore = defineStore('ui', {
  state: () => ({
    toasts: [],
    /** 알림 개수를 다시 세야 할 때 올린다 (헤더 · 마이페이지 탭 배지가 이 값을 지켜본다) */
    alertsVersion: 0,
    aiCourseEntryVersion: 0
  }),
  actions: {
    enterAiCourse (path, event) {
      if (path !== '/ai-course' || event?.ctrlKey || event?.metaKey || event?.shiftKey || event?.altKey || (event?.button != null && event.button !== 0)) return
      this.aiCourseEntryVersion++
    },
    bumpAlerts () {
      this.alertsVersion++
    },
    /** 카피 톤: 해요체 (디자인.md §9) */
    toast (message, ms = 2600) {
      const id = ++seq
      this.toasts.push({ id, message })
      setTimeout(() => this.dismiss(id), ms)
      return id
    },
    dismiss (id) {
      this.toasts = this.toasts.filter(t => t.id !== id)
    }
  }
})
