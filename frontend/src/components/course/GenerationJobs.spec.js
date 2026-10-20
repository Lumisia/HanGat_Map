import * as Vue from 'vue'
import { readFileSync } from 'node:fs'
import { compileScript, compileTemplate, parse } from '@vue/compiler-sfc'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import Component from './GenerationJobs.vue'
import { listGenerationJobs } from '../../api/courseGeneration.js'
const deps = vi.hoisted(() => ({ auth: null }))
vi.mock('../../stores/auth.js', () => ({ useAuthStore: () => deps.auth }))
vi.mock('../../api/notifications.js', () => ({ ASYNC_COURSES_ENABLED: true }))
vi.mock('../../api/courseGeneration.js', () => ({ listGenerationJobs: vi.fn() }))
const source = readFileSync(new URL('./GenerationJobs.vue', import.meta.url), 'utf8')
const { descriptor } = parse(source)
const { code } = compileTemplate({ source: descriptor.template.content, id: 'history-modal', compilerOptions: { bindingMetadata: compileScript(descriptor, { id: 'history-modal' }).bindings } })
Component.render = new Function('Vue', code.replace(/import \{([^}]+)\} from "vue"/g, (_, names) => `const {${names.replace(/ as /g, ': ')}} = Vue`).replace('export function render', 'return function render'))(Vue)
let active
function node(tag, text = '') {
  return Vue.markRaw({ tag, text, props: {}, children: [], parent: null, open: false,
    showModal() { this.open = true }, close() { this.open = false }, focus() { active = this } })
}
const renderer = Vue.createRenderer({ createElement: node, createText: text => node('#text', text), createComment: text => node('#comment', text),
  setText: (el, text) => { el.text = text }, setElementText: (el, text) => { el.text = text; el.children = [] },
  patchProp: (el, key, old, value) => { el.props[key] = value }, parentNode: el => el.parent,
  nextSibling: el => el.parent?.children[el.parent.children.indexOf(el) + 1] ?? null, setScopeId() {},
  insert(el, parent, anchor = null) { if (el.parent) el.parent.children.splice(el.parent.children.indexOf(el), 1); const i = anchor ? parent.children.indexOf(anchor) : -1; parent.children.splice(i < 0 ? parent.children.length : i, 0, el); el.parent = parent },
  remove(el) { if (el.parent) el.parent.children.splice(el.parent.children.indexOf(el), 1) },
})
const all = (node, match) => [...(match(node) ? [node] : []), ...node.children.flatMap(child => all(child, match))]
const text = node => node.text + node.children.map(text).join('')
let app, root
const selected = vi.fn()
const find = match => all(root, match)[0]
const flush = async () => { for (let i = 0; i < 8; i++) await Vue.nextTick() }
function mount() {
  root = node('root'); app = renderer.createApp(Component, { onSelected: selected }); app.provide(Vue.ssrContextKey, {})
  app.component('RouterLink', { setup: (_, { slots }) => () => Vue.h('a', slots.default?.()) }); app.mount(root)
}
beforeEach(() => {
  vi.clearAllMocks(); active = null; deps.auth = Vue.reactive({ isLoggedIn: true, user: { userId: 6 } })
  vi.mocked(listGenerationJobs).mockImplementation(async page => ({ items: Array.from({ length: page === 0 ? 5 : 1 }, (_, i) => ({ jobId: `${page}-${i}`, startDate: '2026-09-20', endDate: '2026-09-22', createdAt: '2026-09-19T00:00:00Z', status: 'SUCCEEDED' })), hasNext: page === 0 }))
})
afterEach(() => app?.unmount())
describe('existing dialog paginated content', () => {
  it('loads five then one, emitting selection for the existing parent dialog', async () => {
    mount(); await flush()
    expect(all(root, el => el.tag === 'li')).toHaveLength(5)
    expect(text(root)).toContain('생성 완료')
    find(el => el.tag === 'button' && text(el) === '다음').props.onClick(); await flush()
    expect(listGenerationJobs).toHaveBeenLastCalledWith(1, 5)
    expect(all(root, el => el.tag === 'li')).toHaveLength(1)
    expect(text(root)).toContain('2페이지')
    find(el => el.tag === 'a').props.onClick()
    expect(selected).toHaveBeenCalledTimes(1)
    app.unmount(); app = null
    expect(listGenerationJobs).toHaveBeenCalledTimes(2)
  })
  it('clears the visible list on logout', async () => {
    mount(); await flush()
    deps.auth.isLoggedIn = false; await flush()
    expect(all(root, el => el.tag === 'li')).toHaveLength(0)
    expect(listGenerationJobs).toHaveBeenCalledTimes(1)
  })
  it('shows safe failure and retry, then empty state', async () => {
    vi.mocked(listGenerationJobs).mockRejectedValueOnce(Error('offline')).mockResolvedValue({ items: [], hasNext: false })
    mount(); await flush()
    expect(text(find(el => el.props.role === 'alert'))).toContain('불러오지 못')
    find(el => el.tag === 'button' && text(el) === '다시 시도').props.onClick(); await flush()
    expect(text(root)).toContain('접수된 요청이 없어요.')
  })
})
