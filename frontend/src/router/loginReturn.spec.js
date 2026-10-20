import { beforeEach, describe, expect, it, vi } from 'vitest'
import { createPinia, setActivePinia } from 'pinia'
import { useAuthStore } from '../stores/auth.js'

const guards = vi.hoisted(() => ({ before: null }))
vi.mock('vue-router', async importOriginal => ({
  ...await importOriginal(),
  createWebHistory: () => ({}),
  createRouter: () => ({ beforeEach: guard => { guards.before = guard }, afterEach: () => {} })
}))
import './index.js'

const route = (path, name, meta = {}, query = {}) => ({ path, fullPath: path, name, meta, query, matched: name ? [{}] : [] })
describe('login navigation guard', () => {
  let auth
  beforeEach(() => {
    setActivePinia(createPinia())
    auth = useAuthStore()
    auth.ready = true
  })
  it('captures the previous public page including query and hash', async () => {
    await guards.before(route('/login', 'login', { guestOnly: true }), route('/map?region=jeju#places', 'map'))
    expect(auth.returnTo).toBe('/map?region=jeju#places')
  })
  it('uses home for direct login entry instead of an old destination', async () => {
    auth.returnTo = '/courses'
    await guards.before(route('/login', 'login'), route('/', undefined))
    expect(auth.returnTo).toBe('/')
  })
  it('preserves the requested protected destination instead of its preceding page', async () => {
    const redirect = await guards.before(route('/courses', 'courses', { requiresAuth: true }), route('/map', 'map'))
    await guards.before(route('/login', 'login', {}, redirect.query), route('/map', 'map'))
    expect(auth.returnTo).toBe('/courses')
  })
  it('keeps the destination during signup and password-reset detours', async () => {
    auth.returnTo = '/map'
    await guards.before(route('/login', 'login'), route('/find-password', 'find-password'))
    expect(auth.returnTo).toBe('/map')
  })
  it('prefers a new previous page over an abandoned login destination', async () => {
    auth.returnTo = '/courses'
    await guards.before(route('/login', 'login'), route('/places/42', 'place-detail'))
    expect(auth.returnTo).toBe('/places/42')
  })
  it('sends an already authenticated guest-only visitor home by default', async () => {
    auth.user = { userId: 7 }
    expect(await guards.before(route('/login', 'login', { guestOnly: true }), route('/', undefined))).toBe('/')
  })
  it('still requires password replacement for a temporary-password account', async () => {
    auth.user = { userId: 7, mustChangePassword: true }
    expect(await guards.before(route('/map', 'map'), route('/', 'home')))
      .toEqual({ name: 'my-profile', query: { force: 'password' } })
  })
})
