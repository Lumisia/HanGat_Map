import { createPinia, setActivePinia } from 'pinia'
import { afterEach, beforeEach, expect, it, vi } from 'vitest'
import { useNotificationStore } from './notifications.js'
import * as api from '../api/notifications.js'

vi.mock('../api/notifications.js', () => ({
  NOTIFICATIONS_ENABLED: true, listNotifications: vi.fn(), readNotification: vi.fn(),
  readAllNotifications: vi.fn(), deleteNotification: vi.fn(), deleteAllNotifications: vi.fn(),
  streamNotifications: vi.fn()
}))
let store
let stream
const row = id => ({ id: String(id), title: '알림', readAt: null })
const tick = async () => { for (let i = 0; i < 8; i++) await Promise.resolve() }
beforeEach(() => {
  vi.useFakeTimers()
  vi.clearAllMocks()
  vi.stubGlobal('document', { hidden: false, addEventListener() {}, removeEventListener() {} })
  vi.stubGlobal('window', { addEventListener() {}, removeEventListener() {} })
  setActivePinia(createPinia())
  api.listNotifications.mockResolvedValue({ items: [row(3), row(2)], unreadCount: 2, nextCursor: null })
  api.streamNotifications.mockImplementation(options => { stream = options; return new Promise(() => {}) })
  store = useNotificationStore()
})
afterEach(() => { store.stop(); vi.useRealTimers(); vi.unstubAllGlobals() })

it('읽음 처리 후 목록을 다시 받지 않고 배지와 해당 항목만 갱신한다', async () => {
  store.start(); await tick()
  api.readNotification.mockResolvedValue({ unreadCount: 1 })
  await store.markRead('3')
  expect(store.items[0].readAt).toBeTruthy()
  expect(store.unread).toBe(1)
  expect(api.listNotifications).toHaveBeenCalledTimes(1)
})
it('백그라운드 동기화 중에도 기존 알림 목록을 유지한다', async () => {
  store.start(); await tick()
  let finish
  api.listNotifications.mockImplementation(() => new Promise(resolve => { finish = resolve }))
  const refresh = store.refresh()
  expect(store.loading).toBe(false)
  expect(store.items).toHaveLength(2)
  finish({ items: [row(4), row(3)], unreadCount: 3 }); await refresh
  expect(store.items[0].id).toBe('4')
})
it('heartbeat 신호에는 재조회하지 않고 invalidate만 반영한다', async () => {
  store.start(); await tick()
  stream.onOpen(); stream.onEvent({ event: 'heartbeat' }); await tick()
  expect(api.listNotifications).toHaveBeenCalledTimes(1)
  stream.onEvent({ event: 'invalidate' }); await tick()
  expect(api.listNotifications).toHaveBeenCalledTimes(2)
})
it('삭제 후 이미 시작한 오래된 응답이 알림을 되살리지 않는다', async () => {
  store.start(); await tick()
  let finish
  api.listNotifications.mockImplementation(() => new Promise(resolve => { finish = resolve }))
  const refresh = store.refresh()
  api.deleteNotification.mockResolvedValue({ unreadCount: 1 })
  await store.remove('3')
  finish({ items: [row(3), row(2)], unreadCount: 2 }); await refresh
  expect(store.items.map(item => item.id)).toEqual(['2'])
  expect(store.unread).toBe(1)
})
it('전체 삭제는 헤더 목록과 미확인 배지를 함께 비운다', async () => {
  store.start(); await tick()
  api.deleteAllNotifications.mockResolvedValue({ unreadCount: 0 })
  await store.removeAll()
  expect(store.items).toEqual([])
  expect(store.unread).toBe(0)
})
it('이전 계정의 지연된 읽음 응답이 새 알림함을 변경하지 않는다', async () => {
  store.start(); await tick()
  let finish
  api.readNotification.mockImplementation(() => new Promise(resolve => { finish = resolve }))
  const read = store.markRead('3')
  store.stop(); store.start(); await tick()
  finish({ unreadCount: 0 }); await read
  expect(store.items[0].readAt).toBeNull()
  expect(store.unread).toBe(2)
})
