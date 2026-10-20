/**
 * 헤더와 모바일 하단 탭바가 **함께 읽는** 유일한 탭 정의.
 *
 * 통합 전에는 AppHeader.vue 와 MobileTabBar.vue 가 각자 배열을 갖고 있었다.
 * 한쪽만 고치면 화면을 돌렸을 때 메뉴가 달라진다 — 그래서 한 곳으로 모았다.
 *
 * 순서·라벨은 지정된 값이다. 임의로 바꾸지 말 것:
 *   메인 / AI코스 / 지도 / 테마 / 저장코스(여기서부터 오른쪽) / 회원(사진) / 로그인·로그아웃
 *   (테마는 2026-09-17 추가 - 관광지 소개 페이지의 입구. 헤더·모바일 탭바 둘 다에 들어간다)
 *
 * ⚠️ 마이페이지 탭은 **넣지 않는다** (로그인 여부와 무관하게 숨김).
 *    마이페이지로 가는 길은 헤더 오른쪽의 회원 아바타뿐이다.
 *    저장코스(/courses)는 로그인이 필요해서, 비로그인으로 누르면
 *    라우터 가드가 /login 으로 보낸다 (router/index.js 의 requiresAuth).
 *
 * 회원 아바타와 로그인·로그아웃 버튼은 로그인 상태에 따라 모양이 달라져
 * 배열로 표현하지 않고 AppHeader.vue 가 직접 그린다.
 *
 * @typedef {Object} NavTab
 * @property {string}  to     라우트 경로. router/index.js 와 반드시 일치
 * @property {string}  label  화면에 보이는 글자
 * @property {string}  icon   AppIcon 이름 (모바일 탭바용)
 * @property {'left'|'right'} side   스페이서 기준 배치
 * @property {'exact'|'prefix'} match 활성 판정 방식
 * @property {boolean} [badge] 읽지 않은 알림 수를 표시할지
 */

/** @type {NavTab[]} */
export const NAV_TABS = [
  { to: '/', label: '메인', icon: 'home', side: 'left', match: 'exact' },
  { to: '/ai-course', label: 'AI코스', icon: 'route', side: 'left', match: 'prefix' },
  { to: '/map', label: '지도', icon: 'map', side: 'left', match: 'prefix' },
  { to: '/themes', label: '테마', icon: 'album', side: 'left', match: 'prefix' },
  /* 저장코스부터 오른쪽. /courses/:id 에서도 켜져야 해서 prefix 다 */
  { to: '/courses', label: '저장코스', icon: 'bookmark', side: 'right', match: 'prefix' }
]

/**
 * 현재 경로에서 이 탭이 활성인가.
 * '/' 를 prefix 로 잡으면 모든 경로에서 켜지므로 match 를 나눠 뒀다.
 * @param {NavTab} tab
 * @param {string} path route.path
 */
export const isTabActive = (tab, path) =>
  tab.match === 'prefix' ? path.startsWith(tab.to) : path === tab.to

export const LEFT_TABS = NAV_TABS.filter(t => t.side === 'left')
export const RIGHT_TABS = NAV_TABS.filter(t => t.side === 'right')

// Menu entry is explicitly different from a result link or a page reload.
export const tabDestination = tab => tab.to === '/ai-course'
  ? { path: tab.to, query: { entry: 'new' } } : tab.to
