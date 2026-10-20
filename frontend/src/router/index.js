import { createRouter, createWebHistory } from 'vue-router'
import { useAuthStore } from '../stores/auth.js'
import { isAuthPath, safeLoginReturnTo } from '../utils/loginReturn.js'

/*
  통합 라우트 (2026-08-17).

  담당자 4명이 서로 다른 경로를 쓰고 있었다. 여기서 하나로 맞추고,
  옛 경로는 **지우지 않고** 리다이렉트로 남긴다 — 각 화면의 RouterLink 가
  아직 옛 경로를 물고 있을 수 있고, 링크를 공유한 사람도 있을 수 있다.

  meta 규칙
    layout        'default'(생략 시) | 'bare'(회원 화면) | 'map'(지도)
    skin          'toss' 면 App.vue 가 <html> 에 skin-toss 를 붙인다.
                  정동현님 리스킨(assets/styles.css 하단)이 그 화면에만 걸린다 —
                  전역으로 두면 AI코스 화면의 버튼·입력·패널까지 바뀐다
    styleScope    'content' 면 DefaultLayout 안의 콘텐츠 전용 CSS를 적용한다
    compactHeader DefaultLayout → AppHeader 로 넘어가는 축약 플래그 (공유 화면용)
    requiresAuth  로그인 필요. **없으면 공개**가 기본이다 (요구사항 USER_001:
                  비회원도 지도·코스 생성이 가능해야 한다)
    guestOnly     로그인 상태로 들어오면 이전 화면 또는 메인으로 보낸다
    title         document.title 접두사
*/
export const routes = [
  /* ── 메인 · 코스 만들기 ── */
  { path: '/', name: 'home', component: () => import('../views/home/HomeView.vue'), meta: { skin: 'toss', styleScope: 'content', title: '메인' } },
  { path: '/ai-course', name: 'ai-course', component: () => import('../views/ai-course/AiCourseView.vue'), meta: { styleScope: 'content', title: 'AI 코스' } },
  { path: '/ai-course/jobs/:jobId', name: 'course-generation-job', component: () => import('../views/ai-course/GenerationJobView.vue'), meta: { requiresAuth: true, styleScope: 'content', title: '코스 생성 상태' } },
  { path: '/travel/search', redirect: to => ({ path: '/ai-course', query: to.query }) },
  { path: '/recommendation', redirect: to => ({ path: '/ai-course', query: to.query }) },

  /* ── 지도 — 전용 레이아웃 (문서 스크롤 없음) ── */
  { path: '/map', name: 'map', component: () => import('../views/map/MapView.vue'), meta: { layout: 'map', title: '지도' } },

  /* ── 테마 — 관광지 소개 페이지의 입구(구석구석 테마 구조, 2026-09-17) ── */
  { path: '/themes', name: 'themes', component: () => import('../views/theme/ThemeView.vue'), meta: { skin: 'toss', styleScope: 'content', title: '테마' } },
  { path: '/themes/:key', name: 'theme-detail', component: () => import('../views/theme/ThemeDetailView.vue'), meta: { skin: 'toss', styleScope: 'content', title: '테마' } },

  /* ── 관광지 상세 ──
     백엔드 placeId(숫자)만 받는다. 옛 목업 id('bijarim' 등)는 이제 열 장소가 없어 지도로 보낸다 -
     예전 목업 페이지는 숫자 id가 들어오면 매칭에 실패해 항상 첫 목업 장소를 보여줬다. */
  { path: '/places/:placeId', name: 'place-detail', component: () => import('../views/place/PlaceDetailView.vue'),
    beforeEnter: to => (/^\d+$/.test(String(to.params.placeId)) ? true : { path: '/map', query: to.query, hash: to.hash }),
    meta: { skin: 'toss', styleScope: 'content', title: '관광지' } },

  /* ── 저장 코스 · 코스 상세 ──
     목록은 내 데이터라 로그인이 필요하고, 상세는 공유 링크로도 열려야 해서 공개다 */
  { path: '/courses', name: 'courses', component: () => import('../views/course/SavedCoursesView.vue'), meta: { requiresAuth: true, skin: 'toss', styleScope: 'content', title: '저장한 코스' } },
  { path: '/courses/:courseId', name: 'course-detail', component: () => import('../views/course/SavedCoursesView.vue'), meta: { skin: 'toss', styleScope: 'content', title: '코스 상세' } },

  /* ── 회원 (USER_001~003) — 자체 2단 구성이라 헤더를 얹지 않는다 ── */
  { path: '/login', name: 'login', component: () => import('../views/auth/LoginView.vue'), meta: { layout: 'bare', guestOnly: true, title: '로그인' } },
  { path: '/signup', name: 'signup', component: () => import('../views/auth/SignupView.vue'), meta: { layout: 'bare', guestOnly: true, title: '회원가입' } },
  { path: '/signup/done', name: 'signup-done', component: () => import('../views/auth/SignupDoneView.vue'), meta: { layout: 'bare', title: '인증 메일을 보냈어요' } },
  { path: '/verify', name: 'verify', component: () => import('../views/auth/VerifyEmailView.vue'), meta: { layout: 'bare', title: '이메일 인증' } },
  { path: '/find-password', name: 'find-password', component: () => import('../views/auth/FindPasswordView.vue'), meta: { layout: 'bare', guestOnly: true, title: '비밀번호 재설정' } },
  { path: '/oauth/callback', name: 'oauth-callback', component: () => import('../views/auth/OAuthCallbackView.vue'), meta: { layout: 'bare', title: '소셜 로그인' } },
  { path: '/auth/withdrawal', name: 'withdrawal-recovery', component: () => import('../views/auth/WithdrawalRecoveryView.vue'), meta: { layout: 'bare', title: '회원탈퇴 취소 확인' } },

  /* ── 마이페이지 (MY_004~011) — 로그인 필요 ──
     '저장한 코스' 는 /courses 로 옮겼다. 옛 경로는 아래 리다이렉트로 살려 둔다. */
  {
    path: '/mypage',
    component: () => import('../views/mypage/MyPageView.vue'),
    meta: { requiresAuth: true },
    children: [
      { path: '', redirect: '/mypage/reviews' },
      { path: 'reviews', name: 'my-reviews', component: () => import('../views/mypage/ReviewsTab.vue'), meta: { requiresAuth: true, title: '작성한 리뷰' } },
      { path: 'favorites', name: 'my-favorites', component: () => import('../views/mypage/FavoritesTab.vue'), meta: { requiresAuth: true, title: '찜한 장소' } },
      { path: 'alerts', name: 'my-alerts', component: () => import('../views/mypage/AlertsTab.vue'), meta: { requiresAuth: true, title: '알림 내역' } },
      { path: 'profile', name: 'my-profile', component: () => import('../views/mypage/ProfileTab.vue'), meta: { requiresAuth: true, title: '설정' } }
    ]
  },

  /* ── 공유 코스 (MY_003) — 비로그인 조회 가능 ── */
  { path: '/share/:token', name: 'share', component: () => import('../views/share/ShareCourseView.vue'), meta: { compactHeader: true, styleScope: 'content', title: '공유된 코스' } },

  /* ── 옛 경로 — 지우지 않고 새 경로로 보낸다 ── */
  { path: '/home', redirect: '/' },
  { path: '/course', redirect: '/ai-course' },
  { path: '/mypage/courses', redirect: '/courses' },
  { path: '/mypage/courses/:courseId', redirect: to => `/courses/${to.params.courseId}` },

  { path: '/:pathMatch(.*)*', name: 'not-found', component: () => import('../views/system/NotFoundView.vue'), meta: { title: '없는 페이지' } }
]

const router = createRouter({
  history: createWebHistory(),
  routes,
  // 새 화면은 맨 위, 뒤로/앞으로 가기는 있던 자리로(테마 목록·상세에서 장소를 봤다 돌아올 때, 2026-09-18). 목록이 비동기로 채워지는 시간을 조금 기다린다
  scrollBehavior: (to, from, saved) => (saved ? new Promise(resolve => setTimeout(() => resolve(saved), 80)) : { top: 0 })
})

/**
 * 보호 라우트 가드 (요구사항 정의서 USER_001 / 명세서 COM_004).
 * 비로그인으로 마이페이지에 들어오면 **원래 요청을 잃지 않도록** returnTo 를 남기고 로그인으로 보낸다.
 */
router.beforeEach(async (to, from) => {
  const auth = useAuthStore()
  if (!auth.ready) await auth.restore()

  if (to.meta.requiresAuth && !auth.isLoggedIn) {
    auth.returnTo = safeLoginReturnTo(to.fullPath)
    return { name: 'login', query: { redirect: auth.returnTo } }
  }
  if (to.name === 'login') {
    // 보호 화면의 명시적 목적지가 우선이다. 인증 화면을 왕복할 때는 기존 목적지를 보존한다.
    const previous = from.matched.length
      ? (isAuthPath(from.path) ? auth.returnTo : from.fullPath)
      : '/'
    auth.returnTo = safeLoginReturnTo(to.query.redirect ?? previous)
  }
  if (to.meta.guestOnly && auth.isLoggedIn) {
    const destination = safeLoginReturnTo(to.query.redirect ?? auth.returnTo)
    auth.returnTo = null
    return destination
  }
  /*
    임시 비밀번호로 들어온 상태면 비밀번호를 바꾸기 전까지 다른 화면을 막는다.
    ASVS 6.4.1 — 시스템이 만든 비밀번호가 장기 비밀번호가 되면 안 된다.
  */
  if (auth.isLoggedIn && auth.mustChangePassword &&
      to.name !== 'my-profile' && to.name !== 'login') {
    return { name: 'my-profile', query: { force: 'password' } }
  }
  return true
})

router.afterEach(to => {
  const base = '한갓지도'
  document.title = to.meta?.title ? `${to.meta.title} · ${base}` : base
})

export default router
