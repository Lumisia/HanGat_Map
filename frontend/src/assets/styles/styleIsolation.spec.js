import { readFileSync } from 'node:fs'
import { resolve } from 'node:path'
import { beforeAll, describe, expect, it } from 'vitest'
import { build } from 'vite'

const frontendRoot = resolve(import.meta.dirname, '../../..')
const tokensCss = readFileSync(new URL('./tokens.css', import.meta.url), 'utf8')
const contentCss = readFileSync(new URL('../styles.css', import.meta.url), 'utf8')
const datePickerSource = readFileSync(new URL('../../components/map/DatePicker.vue', import.meta.url), 'utf8')
const fixedScheduleSource = readFileSync(new URL('../../components/course/FixedSchedulePicker.vue', import.meta.url), 'utf8')

function tokenHexValues(name) {
  return [...tokensCss.matchAll(new RegExp(`${name}:(#[0-9a-f]{6})`, 'gi'))].map(match => match[1])
}

function contrastRatio(foreground, background) {
  const luminance = (hex) => {
    const channels = hex.match(/[0-9a-f]{2}/gi).map(value => Number.parseInt(value, 16) / 255)
      .map(value => value <= 0.03928 ? value / 12.92 : ((value + 0.055) / 1.055) ** 2.4)
    return 0.2126 * channels[0] + 0.7152 * channels[1] + 0.0722 * channels[2]
  }
  const a = luminance(foreground), b = luminance(background)
  return (Math.max(a, b) + 0.05) / (Math.min(a, b) + 0.05)
}

let builtCss = ''

// viteCsp.spec.js와 같은 이유로 훅 기본 10초에 기대지 않는다 - 여기도 실제 프로덕션 빌드를 돈다.
const BUILD_TIMEOUT_MS = 60_000

beforeAll(async () => {
  const result = await build({
    root: frontendRoot,
    logLevel: 'silent',
    build: { write: false }
  })
  const outputs = Array.isArray(result) ? result.flatMap(item => item.output) : result.output
  builtCss = outputs
    .filter(item => item.type === 'asset' && item.fileName.endsWith('.css'))
    .map(item => String(item.source))
    .join('\n')
}, BUILD_TIMEOUT_MS)

describe('page CSS ownership', () => {
  it('keeps content and map rules inside their layout scopes in the production CSS', () => {
    expect(builtCss).toContain('@scope (.content-scope)')
    expect(builtCss).toContain('@scope (.map-shell)')
  })

  it('ships the complete map controls with valid responsive offsets', () => {
    expect(builtCss).not.toContain('5var(')
    for (const selector of [
      // .savebox 는 지도 패널의 로컬 코스 저장 UI 였고 2026-09-07 죽은 코드로 제거됐다
      '.seg button{', '#cond-body{', '.cal-h button',
      '.map-lightbox img', '.acts button{', '.rv-star button{',
      '.rv-strip li button{', '.rv-in input{', '.rv-in button{', '.sb-eg button{',
      '.ftr button{'
    ]) expect(builtCss).toContain(selector)
  })

  it('keeps teleported map dialogs styled outside the map page scope', () => {
    expect(builtCss).toContain('.map-calendar-backdrop[')
    expect(builtCss).toContain('.map-calendar[')
    expect(builtCss).toContain('.map-lightbox[')
  })

  it('does not leak thumbnail darkening onto the whole document', () => {
    expect(builtCss).not.toMatch(/:root[^{}]*\{filter:saturate\(\.55\) brightness\(\.34\)/)
  })

  it('uses the readable dark palette and theme-aware AI course tokens', () => {
    expect(tokensCss.match(/--surface:#29353c/g)).toHaveLength(2)
    expect(tokensCss.match(/--muted:#34434b/g)).toHaveLength(2)
    expect(tokensCss.match(/--dim:rgba\(4,10,12,\.34\)/g)).toHaveLength(2)
    expect(tokensCss).toContain('--course-bg:#cfe8fa')
    expect(tokensCss).toContain('--course-bg:#23343c')
    expect(tokensCss).toContain('--course-on-ac:#042f2a')
    expect(contentCss).toContain('.ai-course-page{min-height:100vh;color:var(--course-text);background:var(--course-bg)}')
    expect(contentCss).toContain('.course-radio input:checked+span,')
    expect(contentCss).toContain('.add-place-button:hover,.course-cta{color:var(--course-on-ac)}')
    expect(contentCss).toContain('.btn.primary{color:var(--on-ac)}')
    expect(fixedScheduleSource).toContain('color:var(--course-on-ac)!important')

    const sharedPairs = tokenHexValues('--primary').map((background, index) => [tokenHexValues('--on-ac')[index], background])
    const coursePairs = tokenHexValues('--course-accent').map((background, index) => [tokenHexValues('--course-on-ac')[index], background])
    for (const [foreground, background] of [...sharedPairs, ...coursePairs]) {
      expect(contrastRatio(foreground, background)).toBeGreaterThanOrEqual(4.5)
    }
  })

  it('gives the teleported calendar real modal keyboard behavior', () => {
    expect(datePickerSource).toContain('aria-haspopup="dialog"')
    expect(datePickerSource).toContain(':aria-expanded="open"')
    expect(datePickerSource).toContain('@keydown="trapFocus"')
    expect(datePickerSource).toContain('trigger.value?.focus()')
  })
})
