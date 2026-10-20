import { defineStore } from 'pinia'
import { ref } from 'vue'
import { getBackendSessionVersion } from '../api/backendClient.js'
import { NOTIFICATIONS_ENABLED, listNotifications, readNotification, readAllNotifications,
  deleteNotification, deleteAllNotifications, clearHeaderNotifications, streamNotifications } from '../api/notifications.js'

/** 헤더 요약과 SSE는 앱 전체에서 공유한다. 마이페이지의 필터·페이지와 섞지 않는다. */
export const useNotificationStore = defineStore('notifications', () => {
  const items = ref([])
  const unread = ref(0)
  const loading = ref(false)
  const connected = ref(false)
  const error = ref('')
  const revision = ref(0)
  const busy = ref(false)
  let lifecycle = 0; let request = 0; let controller; let timer; let watchdog; let active = false
  let pending = null; let refreshAgain = false

  function refresh () {
    if (!NOTIFICATIONS_ENABLED || !active) return Promise.resolve()
    if (pending || busy.value) { refreshAgain = true; return pending ?? Promise.resolve() }
    const ticket = ++request
    const life = lifecycle
    const epoch = getBackendSessionVersion()
    loading.value = !items.value.length
    const operation = (async () => {
      try {
        const page = await listNotifications()
        if (life !== lifecycle || epoch !== getBackendSessionVersion() || ticket !== request) return
        if (!Array.isArray(page?.items) || !Number.isFinite(page.unreadCount)) throw new Error('알림 응답 형식을 확인해 주세요.')
        items.value = page.items
        unread.value = page.unreadCount
        error.value = ''
        revision.value++
      } catch (failure) {
        if (life === lifecycle && ticket === request) error.value = failure.message || '알림을 불러오지 못했어요.'
      } finally {
        if (life === lifecycle) loading.value = false
      }
    })()
    pending = operation
    void operation.finally(() => {
      if (pending !== operation) return
      pending = null
      if (refreshAgain && life === lifecycle && !busy.value) {
        refreshAgain = false
        void refresh()
      }
    })
    return operation
  }

  /** 서버 성공 후 해당 항목만 갱신한다. 오래된 조회가 읽음·삭제를 되돌리지 못하게 한다. */
  async function mutate (send, apply) {
    if (busy.value) throw new Error('알림을 처리하고 있어요. 잠시 후 다시 시도해 주세요.')
    const life = lifecycle
    const epoch = getBackendSessionVersion()
    busy.value = true
    request++
    try {
      const result = await send()
      if (life !== lifecycle || epoch !== getBackendSessionVersion()) return
      request++
      apply()
      if (Number.isFinite(result?.unreadCount)) unread.value = result.unreadCount
      revision.value++
    } finally {
      if (life === lifecycle) {
        busy.value = false
        if (refreshAgain && !pending) { refreshAgain = false; void refresh() }
      }
    }
  }
  const markRead = id => mutate(() => readNotification(id), () => {
    items.value = items.value.map(item => String(item.id) === String(id)
      ? { ...item, readAt: item.readAt ?? new Date().toISOString() } : item)
  })
  const markAllRead = () => mutate(readAllNotifications, () => {
    items.value = items.value.map(item => ({ ...item, readAt: item.readAt ?? new Date().toISOString() }))
  })
  const remove = id => mutate(() => deleteNotification(id), () => {
    items.value = items.value.filter(item => String(item.id) !== String(id))
  })
  const removeAll = () => mutate(deleteAllNotifications, () => { items.value = [] })
  const clearHeader = () => mutate(clearHeaderNotifications, () => {
    items.value = items.value.filter(item => !item.readAt)
  })

  function stop () {
    active = false; lifecycle++; request++; pending = null; refreshAgain = false
    controller?.abort(); clearTimeout(timer); clearTimeout(watchdog)
    document.removeEventListener('visibilitychange', onVisibility)
    window.removeEventListener('online', onVisibility)
    connected.value = false; loading.value = false; busy.value = false
    items.value = []; unread.value = 0; error.value = ''; revision.value++
  }
  function onVisibility () {
    if (!active) return
    clearTimeout(timer); clearTimeout(watchdog); controller?.abort(); controller = undefined
    connected.value = false
    if (!document.hidden) timer = setTimeout(connect, 100)
  }
  async function connect () {
    if (!active || document.hidden) return
    const life = lifecycle
    const connection = new AbortController()
    controller = connection
    watchdog = setTimeout(() => connection.abort(), 90000)
    try {
      await streamNotifications({ signal: connection.signal,
        onOpen: () => { if (life === lifecycle) connected.value = true },
        // 최초 연결·실제 알림 변경만 동기화하고 연결 유지용 heartbeat는 무시한다.
        onEvent: ({ event }) => { if (event === 'invalidate' && life === lifecycle) void refresh() }
      })
    } catch { /* 재연결을 기다리는 동안에도 기존 목록을 유지한다. */ }
    finally {
      if (life === lifecycle && controller === connection) {
        clearTimeout(watchdog); connected.value = false
        if (active && !document.hidden) timer = setTimeout(() => { void refresh(); void connect() }, 15000 + Math.random() * 5000)
      }
    }
  }
  function start () {
    stop()
    if (!NOTIFICATIONS_ENABLED) return
    active = true
    document.addEventListener('visibilitychange', onVisibility)
    window.addEventListener('online', onVisibility)
    void refresh(); void connect()
  }
  return { items, unread, loading, connected, error, revision, busy, refresh, markRead, markAllRead, remove, removeAll, clearHeader, start, stop }
})
