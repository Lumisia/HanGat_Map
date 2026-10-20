import * as Vue from 'vue'
import { readFileSync } from 'node:fs'
import { compileScript, compileTemplate, parse } from '@vue/compiler-sfc'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import CourseConditionForm from './CourseConditionForm.vue'
import AppIcon from '../common/AppIcon.vue'
import { courseDateWindow } from '../../services/course/courseDatePolicy'

vi.mock('./AccommodationSearch.vue', () => ({ default: { render: () => null } }))
vi.mock('./KakaoPlaceSearch.vue', () => ({ default: { render: () => null } }))
vi.mock('./FixedSchedulePicker.vue', () => ({ default: { render: () => null } }))

const source = readFileSync(new URL('./CourseConditionForm.vue', import.meta.url), 'utf8')
const { descriptor } = parse(source)
const { code } = compileTemplate({ source: descriptor.template.content, id: 'condition-feedback',
  compilerOptions: { bindingMetadata: compileScript(descriptor, { id: 'condition-feedback' }).bindings } })
CourseConditionForm.render = new Function('Vue', code
  .replace(/import \{([^}]+)\} from "vue"/g, (_, names) => `const {${names.replace(/ as /g, ': ')}} = Vue`)
  .replace('export function render', 'return function render'))(Vue)
// The custom renderer runs client templates; compile the real icon as well.
const iconDescriptor = parse(readFileSync(new URL('../common/AppIcon.vue', import.meta.url), 'utf8')).descriptor
const iconCode = compileTemplate({ source: iconDescriptor.template.content, id: 'condition-icon',
  compilerOptions: { bindingMetadata: compileScript(iconDescriptor, { id: 'condition-icon' }).bindings } }).code
AppIcon.render = new Function('Vue', iconCode
  .replace(/import \{([^}]+)\} from "vue"/g, (_, names) => `const {${names.replace(/ as /g, ': ')}} = Vue`)
  .replace('export function render', 'return function render'))(Vue)

function all(node, predicate) {
  return [...(predicate(node) ? [node] : []), ...node.children.flatMap(child => all(child, predicate))]
}
const focus = vi.fn()
const scroll = vi.fn()
function node(tag, text = '') {
  return Vue.markRaw({ tag, tagName: tag.toUpperCase(), text, children: [], parent: null, props: {}, style: {},
    getRootNode() { return { activeElement: null } },
    addEventListener() {}, removeEventListener() {}, setAttribute() {}, removeAttribute() {},
    get dataset() { return { errorTarget: this.props['data-error-target'] } },
    focus(options) { focus(this, options) },
    scrollIntoView(options) { scroll(this, options) },
    querySelectorAll() { return all(this, el => el.props['data-error-target'] != null) },
    querySelector(selector) {
      if (selector.startsWith('.fixed-schedule-picker')) return null
      const tags = selector.startsWith('input') ? ['input', 'select'] : ['button']
      return all(this, el => tags.includes(el.tag) && !el.props.disabled)[0] ?? null
    },
  })
}
const renderer = Vue.createRenderer({
  createElement: node, createText: text => node('#text', text), createComment: text => node('#comment', text),
  setText: (el, text) => { el.text = text },
  setElementText: (el, text) => { el.text = text; el.children = [] },
  patchProp: (el, key, old, value) => { el.props[key] = value },
  parentNode: el => el.parent,
  nextSibling: el => el.parent?.children[el.parent.children.indexOf(el) + 1] ?? null,
  setScopeId() {},
  insert(el, parent, anchor = null) {
    if (el.parent) el.parent.children.splice(el.parent.children.indexOf(el), 1)
    const index = anchor ? parent.children.indexOf(anchor) : -1
    parent.children.splice(index < 0 ? parent.children.length : index, 0, el); el.parent = parent
  },
  remove(el) { if (el.parent) el.parent.children.splice(el.parent.children.indexOf(el), 1); el.parent = null },
})
let app, root, props, submitted
const text = el => el.text + el.children.map(text).join('')
const find = predicate => all(root, predicate)[0]
const cta = () => find(el => el.props.class === 'course-cta')
const feedback = () => find(el => el.props.class === 'validation-feedback')
const field = name => find(el => el.props['data-error-target'] === name)
const input = name => all(field(name), el => el.tag === 'input')[0]
const change = async (name, value) => { input(name).props['onUpdate:modelValue'](value); await Vue.nextTick() }
function mount(overrides = {}, loading = false) {
  const minimum = courseDateWindow().minimum
  props = Vue.reactive({ loading })
  submitted = vi.fn()
  const initial = { start_date: minimum, end_date: minimum, people: 2,
    transport: 'PUBLIC_TRANSIT', course_regions: [], course_styles: [{ tag_id: 1, code: 'NATURE', name: '자연', weight: 1 }],
    course_place_preferences: [], ...overrides }
  root = node('root')
  app = renderer.createApp({ setup: () => () => Vue.h(CourseConditionForm, { initial, loading: props.loading, onSubmit: submitted }) })
  app.provide(Vue.ssrContextKey, {})
  app.mount(root)
}
it('step navigation keeps entered conditions and accommodation', async () => {
  const accommodation = { source_code: 'KAKAO_LOCAL', source_place_id: 'test-hotel', place_name: '선택 숙소', latitude: 33.4, longitude: 126.5 }
  mount({ accommodation, people: 4 })
  const view = app._instance.subTree.component.setupState
  const before = JSON.parse(JSON.stringify(view.form))
  view.goStep(3); await Vue.nextTick()
  view.goStep(1); await Vue.nextTick()
  expect(view.form).toEqual(before)
  expect(view.form.accommodation.source_place_id).toBe('test-hotel')
})
beforeEach(() => {
  vi.stubGlobal('Document', class {})
  vi.stubGlobal('ShadowRoot', class {})
  vi.stubGlobal('document', { activeElement: null })
})
afterEach(() => { app?.unmount(); focus.mockClear(); scroll.mockClear(); vi.unstubAllGlobals() })

describe('course input validation feedback', () => {
  it('allows valid input and keeps the existing payload unchanged', async () => {
    mount()
    expect(cta().props.disabled).toBe(false)
    expect(text(feedback())).not.toContain('입력 확인하기')
    find(el => el.tag === 'form').props.onSubmit({ preventDefault() {} })
    await Vue.nextTick()
    expect(submitted).toHaveBeenCalledOnce()
    expect(submitted.mock.calls[0][0]).toMatchObject({ people: 2, transport: 'PUBLIC_TRANSIT' })
  })
  it('shows one error next to the button and at the input, and clears immediately', async () => {
    mount({ people: 0 })
    expect(cta().props.disabled).toBe(false)
    expect(text(feedback())).toContain('인원은 1명 이상')
    expect(text(feedback())).not.toContain('개 오류')
    expect(input('people').props['aria-invalid']).toBe(true)
    expect(text(field('people'))).toContain('인원은 1명 이상')
    await change('people', 2)
    expect(cta().props.disabled).toBe(false)
    expect(text(feedback())).not.toContain('입력 확인하기')
    expect(input('people').props['aria-invalid']).toBe(false)
    expect(focus).not.toHaveBeenCalled(); expect(scroll).not.toHaveBeenCalled()
  })
  it('reports missing values in input order with the remaining error count', () => {
    mount({ start_date: '', end_date: '', people: '', transport: '', course_styles: [] })
    expect(text(feedback())).toContain('여행 시작일과 종료일을 모두 입력')
    expect(text(feedback())).toContain('외 3개 오류')
    expect(cta().props.disabled).toBe(false)
    expect(feedback().props['aria-live']).toBe('polite')
    expect(feedback().props['aria-atomic']).toBe('true')
  })
  it('moves to the invalid step on generation without emitting a request', async () => {
    mount({ people: 0 })
    expect(focus).not.toHaveBeenCalled()
    const nav = find(el => el.props['aria-label'] === '코스 조건 입력 단계')
    all(nav, el => el.tag === 'button')[3].props.onClick()
    await Vue.nextTick()
    await find(el => el.tag === 'form').props.onSubmit({ preventDefault() {} })
    expect(submitted).not.toHaveBeenCalled()
    expect(find(el => el.tag === 'form').props.class).toContain('step-1')
    expect(focus.mock.lastCall?.[0] === input('people')).toBe(true)
    expect(focus.mock.lastCall?.[1]).toEqual({ preventScroll: true })
    expect(scroll.mock.lastCall?.[0] === field('people')).toBe(true)
    expect(scroll.mock.lastCall?.[1]).toEqual({ block: 'center', behavior: 'auto' })
    await change('people', 1)
    expect(cta().props.disabled).toBe(false)
  })
  it('targets the missing end date without changing date rules', async () => {
    mount({ end_date: '' })
    await find(el => el.tag === 'form').props.onSubmit({ preventDefault() {} })
    expect(focus.mock.lastCall?.[0] === input('end-date')).toBe(true)
  })
  it('retains date boundary errors and blocks programmatic invalid submission', () => {
    mount({ start_date: '2000-01-01', end_date: '2000-01-02' })
    expect(text(feedback())).toContain('30일 이내')
    find(el => el.tag === 'form').props.onSubmit({ preventDefault() {} })
    expect(submitted).not.toHaveBeenCalled()
  })
  it('updates selection errors and focuses a keyboard-operable style control', async () => {
    mount({ course_styles: [] })
    await find(el => el.tag === 'form').props.onSubmit({ preventDefault() {} })
    const styleButton = all(field('styles'), el => el.tag === 'button')[0]
    expect(focus.mock.lastCall?.[0] === styleButton).toBe(true)
    styleButton.props.onClick(); await Vue.nextTick()
    expect(cta().props.disabled).toBe(false)
  })
  it('includes the existing fixed-schedule validation without relaxing it', () => {
    mount({ course_place_preferences: [{ place_id: 1, place_name: '방문 장소', preference_type: 'WANT', fixed_time: '10:00' }] })
    expect(text(feedback())).toContain('방문 장소: 시간을 지정하려면 날짜도 선택')
    expect(cta().props.disabled).toBe(false)
  })
  it('keeps generation progress distinct, and blocks duplicate submission', async () => {
    mount({}, true)
    expect(cta().props.disabled).toBe(true)
    expect(text(cta())).toContain('코스를 만들고 있어요')
    expect(text(feedback())).not.toContain('입력 확인하기')
    find(el => el.tag === 'form').props.onSubmit({ preventDefault() {} })
    expect(submitted).not.toHaveBeenCalled()
    props.loading = false; await Vue.nextTick()
    expect(cta().props.disabled).toBe(false)
  })
  it('reserves validation scroll clearance for fixed mobile navigation', () => {
    expect(source).toContain('scroll-margin-block: 100px calc(var(--mobile-tabbar-h, 0px) + 24px)')
  })
})
