import { describe, expect, it } from 'vitest'
import { safeOAuthReturnTo } from './oauthFlow.js'

describe('login return destination', () => {
  it.each(['/map?region=jeju#places', '/courses/12', '/'])('keeps internal destination %s', value => {
    expect(safeOAuthReturnTo(value)).toBe(value)
  })
  it.each([undefined, null, ['/', '/map'], 'https://example.com', '//example.com', '/\\example.com', '/%5cexample.com', '/login', '/LOGIN?redirect=/map', '/signup/done', '/verify?token=secret', '/find-password', '/oauth/callback', '/map/../login', '/%6cogin', '/map\n'])
  ('returns home for an absent, unsafe or auth destination %s', value => {
    expect(safeOAuthReturnTo(value)).toBe('/')
  })
})
