import * as Vue from 'vue'
import { createRenderer, nextTick, ssrContextKey } from 'vue'
import { readFileSync } from 'node:fs'
import { compileScript, compileTemplate, parse } from '@vue/compiler-sfc'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import ReviewEditDialog from './ReviewEditDialog.vue'
import ReviewApiService from '@/services/map/ReviewApiService'
import BaseModal from '../common/BaseModal.vue'
import StarIcon from '../map/StarIcon.vue'

// Vitest's Node mode compiles SFCs for SSR. Add their client render functions
// so the custom renderer can exercise the same buttons and handlers as a browser.
for (const [component, path] of [[ReviewEditDialog, './ReviewEditDialog.vue'], [BaseModal, '../common/BaseModal.vue'], [StarIcon, '../map/StarIcon.vue']]) {
  const { descriptor } = parse(readFileSync(new URL(path, import.meta.url), 'utf8'))
  const { code } = compileTemplate({ source: descriptor.template.content, filename: path, id: path,
    compilerOptions: { bindingMetadata: compileScript(descriptor, { id: path }).bindings },
  })
  component.render = new Function('Vue', code
    .replace(/import \{([^}]+)\} from "vue"/g, (_, names) => `const {${names.replace(/ as /g, ': ')}} = Vue`)
    .replace('export function render', 'return function render'))(Vue)
}

// Render the real dialog in the existing Node test environment, including its events.
function element(tag, text = '') {
  return {
    tag, tagName: tag.toUpperCase(), text, props: {}, children: [], parent: null,
    get options() { return this.children.filter(child => child.tag === 'option') },
    addEventListener() {}, removeEventListener() {}, setAttribute() {}, removeAttribute() {},
    focus() { document.activeElement = this },
    click: vi.fn(),
  }
}
const body = element('body')
const renderer = createRenderer({
  createElement: element,
  createText: text => element('#text', text),
  createComment: text => element('#comment', text),
  setText: (node, text) => { node.text = text },
  setElementText: (node, text) => { node.text = text; node.children = [] },
  parentNode: node => node.parent,
  nextSibling: node => node.parent?.children[node.parent.children.indexOf(node) + 1] ?? null,
  querySelector: () => body,
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
function all(predicate, node = body) {
  return [...(predicate(node) ? [node] : []), ...node.children.flatMap(child => all(predicate, child))]
}
const byLabel = label => all(node => node.props['aria-label'] === label)[0]
const textOf = node => node.text + node.children.map(textOf).join('')
let app
const review = overrides => ({
  id: 42, rating: 3, congestionReport: 'NORMAL', content: '산책하기 좋아요',
  imageUrls: ['/reviews/42/photo.jpg'],
  editableUntil: new Date(Date.now() + 60_000).toISOString(), ...overrides,
})
function mount(overrides = {}) {
  app = renderer.createApp(ReviewEditDialog, { review: review(overrides) })
  app.provide(ssrContextKey, {})
  app.mount(element('root'))
}
async function submit() {
  await all(node => node.tag === 'form')[0].props.onSubmit({ preventDefault() {} })
  await nextTick()
}

beforeEach(() => {
  body.children = []
  vi.stubGlobal('document', { body: { style: {} }, activeElement: null, addEventListener() {}, removeEventListener() {} })
  vi.spyOn(ReviewApiService, 'update').mockResolvedValue({ id: 42 })
  vi.spyOn(ReviewApiService, 'uploadPhotos').mockResolvedValue(['/reviews/42/new.jpg'])
  vi.spyOn(URL, 'createObjectURL').mockReturnValue('blob:new-photo')
  vi.spyOn(URL, 'revokeObjectURL').mockImplementation(() => {})
})
afterEach(() => {
  app?.unmount()
  vi.restoreAllMocks()
  vi.unstubAllGlobals()
})

describe('리뷰 수정 입력', () => {
  it('별 다섯 개로 점수를 바꾸고 같은 별을 다시 눌러도 점수를 유지한다', async () => {
    mount()
    const stars = all(node => /^\d점$/.test(node.props['aria-label'] ?? ''))
    expect(stars).toHaveLength(5)
    expect(byLabel('3점').props['aria-pressed']).toBe(true)
    byLabel('5점').props.onClick()
    await nextTick()
    byLabel('5점').props.onClick()
    await nextTick()
    expect(byLabel('5점').props['aria-pressed']).toBe(true)
    await submit()
    expect(ReviewApiService.update).toHaveBeenCalledWith(42, {
      rating: 5, congestionReport: null, content: '산책하기 좋아요', imageUrls: ['/reviews/42/photo.jpg'],
    })
    expect(ReviewApiService.uploadPhotos).not.toHaveBeenCalled()
  })

  it('혼잡도 버튼이 없고, 별점 없는 기존 후기는 별점을 골라야 저장되며 저장 시 제보는 비운다', async () => {
    mount({ rating: null })
    expect(all(node => node.tag === 'button' && textOf(node) === '한산')).toHaveLength(0)
    expect(all(node => node.props.type === 'submit')[0].props.disabled).toBe(true)
    byLabel('4점').props.onClick()
    await nextTick()
    expect(all(node => node.props.type === 'submit')[0].props.disabled).toBe(false)
    await submit()
    expect(ReviewApiService.update).toHaveBeenLastCalledWith(42, expect.objectContaining({ rating: 4, congestionReport: null }))
  })

  it('사진 추가 타일로 파일 선택을 열고 기존 사진과 새 사진을 함께 저장한다', async () => {
    mount()
    expect(byLabel('사진 추가')).toBeDefined()
    byLabel('사진 추가').props.onClick()
    const input = all(node => node.props.type === 'file')[0]
    expect(input.click).toHaveBeenCalledOnce()
    const file = new File(['photo'], 'photo.jpg', { type: 'image/jpeg' })
    input.props.onChange({ target: { files: [file], value: 'photo.jpg' } })
    await nextTick()
    expect(all(node => node.tag === 'img')).toHaveLength(2)
    expect(textOf(byLabel('사진 추가'))).toContain('2/5')
    await submit()
    expect(ReviewApiService.uploadPhotos).toHaveBeenCalledWith([file], { sessionBound: true })
    expect(ReviewApiService.update).toHaveBeenCalledWith(42, expect.objectContaining({ imageUrls: ['/reviews/42/photo.jpg', '/reviews/42/new.jpg'] }))
  })

  it('기존 사진도 다섯 장 제한에 포함하며 초과 선택을 알린다', async () => {
    mount({ imageUrls: ['/1.jpg', '/2.jpg', '/3.jpg', '/4.jpg'] })
    expect(byLabel('사진 추가')).toBeDefined()
    const file = new File(['photo'], 'photo.jpg', { type: 'image/jpeg' })
    all(node => node.props.type === 'file')[0].props.onChange({ target: { files: [file, file], value: '' } })
    await nextTick()
    expect(all(node => node.tag === 'img')).toHaveLength(5)
    expect(byLabel('사진 추가')).toBeUndefined()
    expect(textOf(all(node => node.props.role === 'alert')[0])).toContain('최대 5장')
  })

  it('5MB를 넘는 사진은 제외하고 저장된 사진 제거는 업로드를 발생시키지 않는다', async () => {
    mount()
    all(node => node.props.type === 'file')[0].props.onChange({ target: {
      files: [{ name: 'large.jpg', type: 'image/jpeg', size: 5 * 1024 * 1024 + 1 }], value: '',
    } })
    await nextTick()
    expect(all(node => node.tag === 'img')).toHaveLength(1)
    expect(textOf(all(node => node.props.role === 'alert')[0])).toContain('5MB 이하')
    byLabel('사진 1 제거').props.onClick()
    await nextTick()
    expect(all(node => node.tag === 'img')).toHaveLength(0)
    await submit()
    expect(ReviewApiService.uploadPhotos).not.toHaveBeenCalled()
    expect(ReviewApiService.update).toHaveBeenCalledWith(42, expect.objectContaining({ imageUrls: [] }))
  })

  it('수정 기한이 지나면 입력과 저장을 막는다', async () => {
    mount({ editableUntil: new Date(Date.now() - 1).toISOString() })
    expect(all(node => node.tag === 'fieldset')[0].props.disabled).toBe(true)
    expect(all(node => node.props.type === 'submit')[0].props.disabled).toBe(true)
    await submit()
    expect(ReviewApiService.update).not.toHaveBeenCalled()
  })
})
