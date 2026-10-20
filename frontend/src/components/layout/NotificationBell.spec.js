import * as Vue from 'vue'
import { createRenderer, nextTick, ssrContextKey } from 'vue'
import { createPinia, setActivePinia } from 'pinia'
import { createMemoryHistory, createRouter } from 'vue-router'
import { readFileSync } from 'node:fs'
import { compileScript, compileTemplate, parse } from '@vue/compiler-sfc'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import NotificationBell from './NotificationBell.vue'
import AppIcon from '../common/AppIcon.vue'
import { useAuthStore } from '../../stores/auth.js'
import { useNotificationStore } from '../../stores/notifications.js'
import * as api from '../../api/notifications.js'

vi.mock('../../api/notifications.js', async importOriginal => ({
  ...await importOriginal(), NOTIFICATIONS_ENABLED: true,
  readNotification: vi.fn(),
  clearHeaderNotifications: vi.fn(),
  deleteAllNotifications: vi.fn(),
}))

// Node Vitest builds SFCs for SSR; client render functions let this renderer
// execute the real component's controls without introducing a DOM dependency.
for (const [component, path] of [[NotificationBell, './NotificationBell.vue'], [AppIcon, '../common/AppIcon.vue']]) {
  const { descriptor } = parse(readFileSync(new URL(path, import.meta.url), 'utf8'))
  const { code } = compileTemplate({ source: descriptor.template.content, filename: path, id: path,
    compilerOptions: { bindingMetadata: compileScript(descriptor, { id: path }).bindings },
  })
  component.render = new Function('Vue', code
    .replace(/import \{([^}]+)\} from "vue"/g, (_, names) => `const {${names.replace(/ as /g, ': ')}} = Vue`)
    .replace('export function render', 'return function render'))(Vue)
}

function nodes(predicate, node) {
  return [...(predicate(node) ? [node] : []), ...node.children.flatMap(child => nodes(predicate, child))]
}
const hasClass = (node, name) => (node.props.class ?? '').split(' ').includes(name)
function element(tag, text = '') {
  return Vue.markRaw({ tag, text, props: {}, children: [], parent: null,
    querySelector(selector) { return nodes(node => hasClass(node, selector.slice(1)), this)[0] },
    focus() { document.activeElement = this },
  })
}
const renderer = createRenderer({
  createElement: element, createText: text => element('#text', text), createComment: () => element('#comment'),
  setText: (node, text) => { node.text = text },
  setElementText: (node, text) => { node.text = text; node.children = [] },
  parentNode: node => node.parent,
  nextSibling: node => node.parent?.children[node.parent.children.indexOf(node) + 1] ?? null,
  setScopeId() {},
  patchProp: (node, key, previous, value) => { node.props[key] = value },
  insert(node, parent, anchor = null) {
    if (node.parent) node.parent.children.splice(node.parent.children.indexOf(node), 1)
    const index = anchor ? parent.children.indexOf(anchor) : -1
    parent.children.splice(index < 0 ? parent.children.length : index, 0, node)
    node.parent = parent
  },
  remove(node) {
    if (node.parent) node.parent.children.splice(node.parent.children.indexOf(node), 1)
    node.parent = null
  },
})
const textOf = node => node.text + node.children.map(textOf).join('')
let app, root, store, router
const findClass = name => nodes(node => hasClass(node, name), root)

beforeEach(async () => {
  vi.clearAllMocks()
  vi.stubGlobal('document', { activeElement: null, addEventListener() {}, removeEventListener() {} })
  const pinia = createPinia()
  setActivePinia(pinia)
  useAuthStore().user = { userId: 7, nickname: '여행자' }
  store = useNotificationStore()
  store.items = [{
    id: '42', type: 'AI_COURSE_COMPLETED', title: '코스가 완성됐어요', message: '여행 코스를 확인해 주세요.',
    severity: 'NORMAL', targetType: 'COURSE', targetId: '99', createdAt: new Date().toISOString(), readAt: null,
  }]
  store.unread = 1
  api.readNotification.mockResolvedValue({ unreadCount: 0 })
  router = createRouter({ history: createMemoryHistory(), routes: [
    { path: '/', name: 'home', component: { render: () => null } },
    { path: '/my/alerts', name: 'my-alerts', component: { render: () => null } },
    { path: '/course/:id', name: 'course-detail', component: { render: () => null } },
  ] })
  await router.push('/')
  await router.isReady()
  vi.spyOn(router, 'push')
  vi.spyOn(store, 'refresh')
  vi.spyOn(store, 'markRead')
  root = element('root')
  app = renderer.createApp(NotificationBell)
  app.use(pinia)
  app.use(router)
  app.provide(ssrContextKey, {})
  app.mount(root)
})
afterEach(() => {
  app?.unmount()
  vi.restoreAllMocks()
  vi.unstubAllGlobals()
})

describe('헤더 알림 패널', () => {
  it('읽은 알림만 헤더에서 숨기고 전체 알림 삭제 API는 호출하지 않는다', async () => {
    store.items[0].readAt = new Date().toISOString()
    store.unread = 0
    api.clearHeaderNotifications.mockResolvedValue({ unreadCount: 0 })
    await findClass('bell')[0].props.onClick()
    expect(textOf(findClass('sw')[0])).toBe('모두 삭제')
    await findClass('sw')[0].props.onClick()
    await nextTick()
    expect(api.clearHeaderNotifications).toHaveBeenCalledTimes(1)
    expect(api.deleteAllNotifications).not.toHaveBeenCalled()
    expect(findClass('prow')).toHaveLength(0)
    expect(textOf(findClass('panel')[0])).toContain('알림이 없습니다.')
    expect(findClass('empty-notifications')[0].children.some(node => node.tag === 'svg')).toBe(true)
  })

  it('종을 열고 다시 열어도 알림을 재조회하지 않는다', async () => {
    await findClass('bell')[0].props.onClick()
    expect(findClass('panel')).toHaveLength(1)
    expect(document.activeElement).toBe(findClass('panel')[0])
    await findClass('bell')[0].props.onClick()
    await findClass('bell')[0].props.onClick()
    expect(findClass('panel')).toHaveLength(1)
    expect(store.refresh).not.toHaveBeenCalled()
  })

  it('읽지 않은 알림을 누르면 읽음만 반영하고 패널과 현재 화면을 유지한다', async () => {
    await findClass('bell')[0].props.onClick()
    await findClass('prow')[0].props.onClick()
    await nextTick()
    expect(store.markRead).toHaveBeenCalledWith('42')
    expect(api.readNotification).toHaveBeenCalledWith('42')
    expect(store.items[0].readAt).toBeTruthy()
    expect(findClass('prow')[0].props.class).not.toContain('unread')
    expect(findClass('bell')[0].props['aria-label']).toBe('알림')
    expect(findClass('panel')).toHaveLength(1)
    expect(router.push).not.toHaveBeenCalled()
    expect(router.currentRoute.value.fullPath).toBe('/')
    await findClass('prow')[0].props.onClick()
    expect(store.markRead).toHaveBeenCalledTimes(1)
  })

  it('백그라운드 로딩 중에도 기존 알림을 패널에 표시한다', async () => {
    await findClass('bell')[0].props.onClick()
    store.loading = true
    await nextTick()
    expect(findClass('prow')).toHaveLength(1)
    expect(textOf(findClass('prow')[0])).toContain('코스가 완성됐어요')
    expect(textOf(findClass('panel')[0])).not.toContain('불러오는 중')
    expect(findClass('panel')).toHaveLength(1)
  })
})
