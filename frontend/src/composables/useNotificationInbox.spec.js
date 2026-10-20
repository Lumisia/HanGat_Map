import { effectScope } from 'vue'
import { beforeEach, expect, it, vi } from 'vitest'
import { useNotificationInbox } from './useNotificationInbox.js'
import { listNotificationPage } from '../api/notifications.js'
vi.mock('../api/notifications.js', () => ({ NOTIFICATIONS_ENABLED: true, listNotificationPage: vi.fn() }))
const page = (number, count = 7, totalPages = 3) => ({
  items: Array.from({ length: count }, (_, i) => ({ id: String(100 - number * 7 - i) })),
  number, totalPages, totalElements: 20, unreadCount: 10
})
beforeEach(() => { vi.clearAllMocks() })
it('서버에서 받은 7개씩 페이지를 바꾸고 필터 변경 시 첫 페이지로 돌아간다', async () => {
  const scope = effectScope(); const inbox = scope.run(useNotificationInbox)
  listNotificationPage.mockImplementation(async (number) => page(number))
  await inbox.load(1)
  expect(inbox.items.value).toHaveLength(7)
  expect(inbox.number.value).toBe(1)
  await inbox.filter('COURSE')
  expect(listNotificationPage).toHaveBeenLastCalledWith(0, 'COURSE')
  expect(inbox.number.value).toBe(0)
  scope.stop()
})
it('필터 변경 전 늦은 응답은 새 필터 결과를 덮어쓰지 못한다', async () => {
  const scope = effectScope(); const inbox = scope.run(useNotificationInbox)
  let finish
  listNotificationPage.mockImplementationOnce(() => new Promise(resolve => { finish = resolve }))
  const first = inbox.load()
  listNotificationPage.mockResolvedValueOnce(page(0, 1))
  await inbox.filter('LOGIN')
  finish(page(0, 7)); await first
  expect(inbox.items.value).toHaveLength(1)
  expect(inbox.category.value).toBe('LOGIN')
  scope.stop()
})
it('마지막 페이지의 마지막 알림을 삭제하면 남아 있는 마지막 페이지로 돌아간다', async () => {
  const scope = effectScope(); const inbox = scope.run(useNotificationInbox)
  listNotificationPage.mockResolvedValueOnce(page(2, 0, 2)).mockResolvedValueOnce(page(1, 7, 2))
  await inbox.load(2)
  expect(inbox.number.value).toBe(1)
  expect(inbox.items.value).toHaveLength(7)
  scope.stop()
})
it('페이지 이동 중 알림 갱신이 겹쳐도 사용자가 선택한 페이지를 유지한다', async () => {
  const scope = effectScope(); const inbox = scope.run(useNotificationInbox)
  let finishNavigation
  listNotificationPage.mockResolvedValueOnce(page(0))
  await inbox.load()
  listNotificationPage.mockImplementationOnce(() => new Promise(resolve => { finishNavigation = resolve }))
  const navigation = inbox.load(1)
  listNotificationPage.mockImplementationOnce(async number => page(number))
  await inbox.load()
  finishNavigation(page(1)); await navigation
  expect(listNotificationPage).toHaveBeenLastCalledWith(1, 'ALL')
  expect(inbox.number.value).toBe(1)
  scope.stop()
})
