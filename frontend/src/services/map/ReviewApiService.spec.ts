import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest'

// backendClient(마이페이지팀 JS)는 목으로 바꾼다 - 위임이 맞게 가는지만 본다
vi.mock('../../api/backendClient.js', () => ({
  apiRequest: vi.fn(),
  getBackendAccessToken: vi.fn(() => 'test-token'),
  getBackendUserId: vi.fn(() => 7),
  reissueAccessToken: vi.fn(),
  BACKEND_BASE_URL: 'http://localhost:8080'
}))

import ReviewApiService, { failText, LOGIN_EXPIRED_TEXT } from './ReviewApiService'
import { apiRequest, reissueAccessToken } from '../../api/backendClient.js'

/** 값은 2026-08-31 실응답에서 가져왔다 */
const REAL_PAGE = {
  content: [{
    id: 1, userId: 1, nickname: '여행자', profileImageUrl: '/users/1/profile-image/12345678-1234-1234-1234-123456789abc.png', rating: 5, congestionReport: 'QUIET',
    content: 'good place', imageUrls: [], createdAt: '2026-08-31T01:37:06.580069'
  }],
  number: 0, size: 10, totalPages: 1, totalElements: 1
}

function mockFetch (handler: (url: string, init?: RequestInit) => unknown) {
  vi.stubGlobal('fetch', vi.fn(async (url: string, init?: RequestInit) => ({
    ok: true,
    status: 200,
    json: async () => handler(url, init)
  })))
}

beforeEach(() => vi.clearAllMocks())
afterEach(() => vi.unstubAllGlobals())

describe('후기 목록', () => {
  it('공개 API 로 조회한다 - 토큰 없이', async () => {
    mockFetch(() => ({ success: true, code: 2000, message: '', result: REAL_PAGE }))

    const page = await ReviewApiService.getReviews(24)

    expect(page.content[0].rating).toBe(5)
    expect(page.content[0].profileImageUrl).toBe('/users/1/profile-image/12345678-1234-1234-1234-123456789abc.png')
    expect(page.totalElements).toBe(1)
    const call = (fetch as ReturnType<typeof vi.fn>).mock.calls[0]
    expect(call[0]).toContain('/places/24/reviews?page=0&size=10')
  })
})

describe('작성·삭제 - 인증 클라이언트 위임', () => {
  it('작성은 auth 요청으로 나간다', async () => {
    await ReviewApiService.create(24, { rating: 4, content: '한줄' })

    expect(apiRequest).toHaveBeenCalledWith('/places/24/reviews',
      expect.objectContaining({ method: 'POST', auth: true }))
  })

  it('삭제도 auth 요청이다', async () => {
    await ReviewApiService.remove(1)

    expect(apiRequest).toHaveBeenCalledWith('/reviews/1',
      expect.objectContaining({ method: 'DELETE', auth: true }))
  })
})

describe('사진 업로드', () => {
  const file = new File([new Uint8Array([1, 2])], 'a.jpg', { type: 'image/jpeg' })

  it('multipart 로 올리고 URL 배열을 받는다', async () => {
    mockFetch(() => ({ success: true, result: ['/uploads/reviews/x.jpg'] }))

    const urls = await ReviewApiService.uploadPhotos([file])

    expect(urls).toEqual(['/uploads/reviews/x.jpg'])
    const init = (fetch as ReturnType<typeof vi.fn>).mock.calls[0][1] as RequestInit
    expect(init.body).toBeInstanceOf(FormData)
    expect((init.headers as Record<string, string>).Authorization).toContain('Bearer')
  })

  it('401 이면 재발급 후 한 번만 재시도한다', async () => {
    let calls = 0
    vi.stubGlobal('fetch', vi.fn(async () => {
      calls++
      return calls === 1
        ? { ok: false, status: 401, json: async () => ({ success: false }) }
        : { ok: true, status: 200, json: async () => ({ success: true, result: ['/uploads/reviews/y.jpg'] }) }
    }))

    const urls = await ReviewApiService.uploadPhotos([file])

    expect(reissueAccessToken).toHaveBeenCalledTimes(1)
    expect(urls).toEqual(['/uploads/reviews/y.jpg'])
  })

  it('서버가 거부하면 메시지를 담아 던진다', async () => {
    mockFetch(() => ({ success: false, message: '후기 사진은 최대 5장까지입니다.' }))

    await expect(ReviewApiService.uploadPhotos([file]))
      .rejects.toThrow('최대 5장')
  })

  /* 실패 응답의 모양이 제각각이다 - 어느 경우에도 영어 오류가 토스트에 뜨지 않아야 한다 */
  const mockFail = (status: number, body: () => unknown) =>
    vi.stubGlobal('fetch', vi.fn(async () => ({ ok: false, status, json: async () => body() })))

  it('검증 실패(400 JSON)는 서버 문구를 그대로 쓴다', async () => {
    mockFail(400, () => ({ success: false, code: 3000, message: '입력값을 확인해주세요.' }))
    await expect(ReviewApiService.uploadPhotos([file])).rejects.toThrow('입력값을 확인해주세요.')
  })

  it('5MB 초과(413, 본문에 message 없음)는 크기를 줄이라고 안내한다', async () => {
    mockFail(413, () => ({ timestamp: '2026-09-13T00:00:00', status: 413, error: 'Payload Too Large', path: '/reviews/photos' }))
    await expect(ReviewApiService.uploadPhotos([file])).rejects.toThrow('5MB 이하로 줄여 주세요')
  })

  it('JSON 이 아닌 본문(빈 413·프록시 HTML)이어도 우리 문구로 던진다 - 예전엔 "Unexpected end of JSON input" 이 떴다', async () => {
    mockFail(413, () => { throw new SyntaxError('Unexpected end of JSON input') })
    await expect(ReviewApiService.uploadPhotos([file])).rejects.toThrow('5MB 이하로 줄여 주세요')

    mockFail(502, () => { throw new SyntaxError('Unexpected token <') })
    await expect(ReviewApiService.uploadPhotos([file])).rejects.toThrow('잠시 후 다시 시도해 주세요')
  })

  it('연결이 끊겨 fetch 가 실패하면 영어 대신 인터넷 연결을 확인하라고 한다', async () => {
    vi.stubGlobal('fetch', vi.fn(async () => { throw new TypeError('Failed to fetch') }))
    await expect(ReviewApiService.uploadPhotos([file])).rejects.toThrow('인터넷 연결을 확인해 주세요')
  })

  it('재발급 뒤에도 401 이면 서버 봉투("JWT 토큰 유효하지 않음") 대신 로그인 만료 문구', async () => {
    vi.stubGlobal('fetch', vi.fn(async () => ({ ok: false, status: 401, json: async () => ({ success: false, code: 3002, message: 'JWT 토큰 유효하지 않음' }) })))
    await expect(ReviewApiService.uploadPhotos([file])).rejects.toThrow(LOGIN_EXPIRED_TEXT)
  })
})

/* 등록·삭제 실패 토스트 - 서버 원문·숫자·영어가 사용자에게 안 보이게(2026-09-19) */
describe('failText', () => {
  const api = (status: number, code: number | string, message: string) => ({ status, code, message })

  it('로그인 만료(401 · 3001 · 3002)는 다시 로그인하라고 한다', () => {
    expect(failText(api(401, 3002, 'JWT 토큰 유효하지 않음'), '후기를 남기지 못했어요')).toBe(LOGIN_EXPIRED_TEXT)
    expect(failText(api(401, 3001, 'JWT 토큰 만료'), '후기를 남기지 못했어요')).toBe(LOGIN_EXPIRED_TEXT)
  })

  it('업무 오류(3xxx)는 서버 문구 그대로 - 별점(3213, 백엔드 문구를 화면 현실에 맞춤) · 본인만 · 5장 · 입력값 · 없는 장소', () => {
    expect(failText(api(400, 3213, '별점을 선택해 주세요.'), '후기를 남기지 못했어요')).toBe('별점을 선택해 주세요.')
    expect(failText(api(400, 3212, '본인이 작성한 후기만 수정하거나 삭제할 수 있습니다.'), '후기를 삭제하지 못했어요'))
      .toBe('본인이 작성한 후기만 수정하거나 삭제할 수 있습니다.')
    expect(failText(api(400, 3214, '후기 사진은 최대 5장까지입니다.'), '후기를 남기지 못했어요')).toBe('후기 사진은 최대 5장까지입니다.')
    expect(failText(api(400, 3000, '입력값을 확인해주세요.'), '후기를 남기지 못했어요')).toBe('입력값을 확인해주세요.')
    expect(failText(api(400, 3201, '존재하지 않는 장소입니다.'), '후기를 남기지 못했어요')).toBe('존재하지 않는 장소입니다.')
  })

  it('서버 장애(5xxx)와 응답 형식 오류는 내부 사정을 보이지 않고 기본 문구로 통일', () => {
    expect(failText(api(500, 5001, '데이터베이스 연결 및 처리 오류'), '후기를 삭제하지 못했어요')).toBe('후기를 삭제하지 못했어요 · 잠시 후 다시 시도해 주세요')
    expect(failText(api(500, 5002, '외부 API 호출에 실패했습니다.'), '후기를 남기지 못했어요')).toBe('후기를 남기지 못했어요 · 잠시 후 다시 시도해 주세요')
    expect(failText(api(502, 'INVALID_RESPONSE', '서버 응답 형식을 확인해주세요.'), '후기를 남기지 못했어요')).toBe('후기를 남기지 못했어요 · 잠시 후 다시 시도해 주세요')
    expect(failText(api(400, 'HTTP_ERROR', '요청을 처리하지 못했습니다.'), '후기를 남기지 못했어요')).toBe('후기를 남기지 못했어요 · 잠시 후 다시 시도해 주세요')
  })

  it('연결 실패·시간 초과는 인터넷 연결을 확인하라고 한다', () => {
    expect(failText(api(0, 'NETWORK_ERROR', '서버에 연결할 수 없습니다.'), '후기를 남기지 못했어요')).toBe('후기를 남기지 못했어요 · 인터넷 연결을 확인해 주세요')
  })

  it('사진 업로드가 만든 우리 문구(한글)는 그대로, 브라우저 예외 영어와 빈 오류는 기본 문구', () => {
    expect(failText(new Error('사진이 너무 커서 올리지 못했어요 · 5MB 이하로 줄여 주세요'), '후기를 남기지 못했어요')).toBe('사진이 너무 커서 올리지 못했어요 · 5MB 이하로 줄여 주세요')
    expect(failText(new SyntaxError('Unexpected token <'), '후기를 남기지 못했어요')).toBe('후기를 남기지 못했어요 · 잠시 후 다시 시도해 주세요')
    expect(failText(null, '후기를 남기지 못했어요')).toBe('후기를 남기지 못했어요 · 잠시 후 다시 시도해 주세요')
  })
})
