import { defineStore } from 'pinia'
import * as authApi from '../api/userAuth.js'
import { ApiError } from '../api/errors.js'
import {
  accessTokenRemainSeconds, clearBackendSession, getBackendSessionVersion
} from '../api/backendClient.js'

/**
 * 인증 상태.
 * - 비로그인도 지도·검색·AI 코스 생성은 쓸 수 있다 (USER_001).
 *   보호가 필요한 곳(찜·저장·마이페이지)만 라우터 가드에서 막는다.
 * - 액세스 토큰은 메모리에만 있다 → 새로고침하면 사라진다.
 *   restore() 가 리프레시 쿠키로 조용히 다시 받아온다.
 */
export const useAuthStore = defineStore('auth', {
  state: () => ({
    user: null,
    ready: false,
    loading: false,
    /** 로그인 후 돌아갈 경로 — 보호 기능 진입이 막혔을 때 원래 요청을 잃지 않기 위함 */
    returnTo: null,
    /** 세션이 끊긴 이유. 배너 문구를 다르게 보여주려고 코드까지 들고 있는다. */
    endedReason: null
  }),

  getters: {
    isLoggedIn: s => !!s.user,
    /*
      통합(2026-08-17): 메인·AI코스 화면이 쓰던 이름.
      두 스토어의 defineStore id 가 똑같이 'auth' 라 한쪽만 남겨야 했고,
      기능이 많은 이쪽을 남겼다. 저쪽 .vue 를 고치는 대신 이름만 여기서 맞춘다.
    */
    isAuthenticated: s => !!s.user,
    displayName: s => s.user?.nickname || s.user?.name || '',
    initial: s => (s.user?.nickname || s.user?.name || '?').slice(0, 1),
    /** 임시 비밀번호로 들어온 상태 — 비밀번호를 바꾸기 전엔 다른 걸 못 하게 막는다 */
    mustChangePassword: s => !!s.user?.mustChangePassword
  },

  actions: {
    async loginDemo () {
      if (this.loading) return
      this.loading = true
      try {
        const res = await authApi.loginDemo()
        this.user = res.user
        this.endedReason = null
        return res
      } finally { this.loading = false }
    },
    /** 앱 시작 시 리프레시 쿠키로 세션 복구 */
    async restore () {
      if (this.ready) return
      try {
        // HttpOnly refresh 쿠키는 JavaScript로 존재 여부를 읽을 수 없으므로 항상 조용히 시도한다.
        this.user = await authApi.restoreSession()
      } catch (e) {
        if (e instanceof ApiError) {
          this.user = null
          // 조용한 복구 실패는 배너를 띄우지 않는다 (그냥 로그인 안 된 상태)
        } else {
          throw e
        }
      } finally {
        this.ready = true
      }
    },

    async login (payload) {
      this.loading = true
      try {
        const res = await authApi.login(payload)
        this.user = res.recoveryRequired ? null : res.user
        this.endedReason = null
        return res
      } finally {
        this.loading = false
      }
    },

    async verifyEmail (token) {
      return authApi.verifyEmail(token)
    },

    async withdraw (email) {
      const result = await authApi.withdrawAccount(email)
      this.user = null
      this.returnTo = null
      this.endedReason = null
      return result
    },

    async logout () {
      try {
        await authApi.logout()
      } finally {
        // 서버 호출이 실패해도 클라이언트 상태는 반드시 비운다
        clearBackendSession()
        this.user = null
        this.returnTo = null
        this.endedReason = null
      }
    },

    async refreshMe () {
      this.user = await authApi.me()
      return this.user
    },

    async restoreAfterOAuth () {
      this.user = await authApi.restoreSession()
      this.ready = true
      this.endedReason = null
      return this.user
    },

    async updateNickname (nickname) {
      const epoch = getBackendSessionVersion()
      const user = await authApi.updateNickname(nickname)
      if (epoch !== getBackendSessionVersion() || user.userId !== this.user?.userId) {
        throw new ApiError(401, 'SESSION_CHANGED', '로그인 계정이 변경됐어요. 다시 시도해 주세요.')
      }
      this.user = { ...this.user, nickname: user.nickname }
      return this.user
    },

    async updateBirthDate (birthDate) {
      const epoch = getBackendSessionVersion()
      const user = await authApi.updateBirthDate(birthDate)
      if (epoch !== getBackendSessionVersion() || user.userId !== this.user?.userId) {
        throw new ApiError(401, 'SESSION_CHANGED', '로그인 계정이 변경됐어요. 다시 시도해 주세요.')
      }
      this.user = { ...this.user, birthDate: user.birthDate }
      return this.user
    },

    /** 업로드 중 로그아웃하거나 계정을 바꾸면 늦게 도착한 프로필로 세션을 복원하지 않는다. */
    async updateProfileImage (file) {
      const epoch = getBackendSessionVersion()
      const userId = this.user?.userId
      const user = await authApi.updateProfileImage(file)
      if (epoch !== getBackendSessionVersion() || userId !== this.user?.userId || userId !== user?.userId) {
        throw new ApiError(401, 'SESSION_CHANGED', '로그인 계정이 변경됐어요. 다시 시도해 주세요.')
      }
      // 동시에 편집한 닉네임 등은 건드리지 않고 사진 정보만 갱신한다.
      this.user = { ...this.user, profileImageUrl: user.profileImageUrl }
      return this.user
    },

    /** 401 을 만난 화면들이 공통으로 부르는 처리 */
    handleUnauthorized (routePath = null, code = 'SESSION_EXPIRED') {
      clearBackendSession()
      this.user = null
      this.endedReason = code
      if (routePath) this.returnTo = routePath
    },

    accessTokenRemainSeconds
  }
})
