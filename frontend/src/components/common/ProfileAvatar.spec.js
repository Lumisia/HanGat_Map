import { createSSRApp } from 'vue'
import { renderToString } from '@vue/server-renderer'
import { describe, expect, it } from 'vitest'
import ProfileAvatar from './ProfileAvatar.vue'
import { BACKEND_BASE_URL } from '../../api/backendClient.js'

const path = '/users/7/profile-image/12345678-1234-1234-1234-123456789abc.png'
describe('리뷰 작성자 프로필 사진', () => {
  it('공개 백엔드 주소를 인증 쿼리 없이 이미지로 표시한다', async () => {
    const html = await renderToString(createSSRApp(ProfileAvatar, { src: path, nickname: '여행자' }))
    expect(html).toContain(`src="${BACKEND_BASE_URL}${path}"`)
    expect(html).toContain('loading="lazy"')
    expect(html).not.toContain('Bearer')
  })
  it.each([null, '', 'https://external.example/photo.png', '/users/me/profile-image/test.png', '//external.example/photo.png', '/users/7/profile-image/../../../file'])
  ('사진이 없거나 공개 경로가 아니면 기본 글자를 표시한다: %s', async src => {
    const html = await renderToString(createSSRApp(ProfileAvatar, { src, nickname: '한갓' }))
    expect(html).not.toContain('<img')
    expect(html).toContain('한')
  })
  it('작성자 이름도 없으면 익명 기본 글자를 표시한다', async () => {
    const html = await renderToString(createSSRApp(ProfileAvatar))
    expect(html).toContain('여')
  })
})
