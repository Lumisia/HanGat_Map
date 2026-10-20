import { readFileSync } from 'node:fs'
import { describe, expect, it } from 'vitest'
import { createSSRApp } from 'vue'
import { renderToString } from '@vue/server-renderer'
import { createPinia } from 'pinia'
import { createMemoryHistory, createRouter } from 'vue-router'
import AppHeader from './AppHeader.vue'

const headerSource = readFileSync(new URL('./AppHeader.vue', import.meta.url), 'utf8')

describe('app header mobile presentation', () => {
  it('renders navigation without the mobile preview popup action', async () => {
    const router = createRouter({ history: createMemoryHistory(), routes: [{ path: '/:pathMatch(.*)*', component: {} }] })
    await router.push('/')
    const html = await renderToString(createSSRApp(AppHeader).use(createPinia()).use(router))
    expect(html).not.toContain('aria-label="모바일 화면으로 미리보기"')
    expect(html).toContain('aria-controls="mobile-header-menu"')
    expect(html).toContain('로그인')
  })

  it('keeps an accessible mobile menu trigger and navigation panel in the top header', () => {
    expect(headerSource).toContain('mobileMenuOpen')
    expect(headerSource).toContain('class="mobile-menu-button"')
    expect(headerSource).toContain(':aria-expanded="mobileMenuOpen"')
    expect(headerSource).toContain('aria-controls="mobile-header-menu"')
    expect(headerSource).toContain('id="mobile-header-menu"')
    expect(headerSource).toContain('class="mobile-header-menu"')
    expect(headerSource).toMatch(/@media \(max-width: 768px\)[\s\S]*\.mobile-menu-button \{ display: inline-flex; \}/)
  })
})
