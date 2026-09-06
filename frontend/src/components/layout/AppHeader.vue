<script setup>
/**
 * 공통 헤더 (요구사항 정의서 COM_001, 명세서 COM_002/COM_003).
 *
 * 스타일은 assets/styles/base.css의 .nav / .nav-in / .tab / .logo / .ghost를 그대로 쓴다 —
 * 통합 지시가 "헤더 CSS 는 우리 폴더 기준" 이었다.
 *
 * 탭 목록은 config/navTabs.js 한 곳에서 온다. MobileTabBar.vue 도 같은 배열을 읽는다.
 * 배치: 로고 · 메인 · AI코스 · 지도 ─spacer─ 마이페이지 · 테마 · 알림 · 회원 · 로그인/로그아웃
 */
import { computed, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { useAuthStore } from '../../stores/auth.js'
import { useUiStore } from '../../stores/ui.js'
import { listAlerts } from '../../api/mypage.js'
import { LEFT_TABS, NAV_TABS, RIGHT_TABS, isTabActive } from '../../config/navTabs.js'
import ThemeToggle from './ThemeToggle.vue'
import NotificationBell from './NotificationBell.vue'

defineProps({ compact: { type: Boolean, default: false } })

const auth = useAuthStore()
const ui = useUiStore()
const route = useRoute()
const router = useRouter()

const unread = ref(0)
const mobileMenuOpen = ref(false)

async function loadUnread () {
  if (!auth.isLoggedIn) {
    unread.value = 0
    return
  }
  try {
    const res = await listAlerts({ onlyUnread: true })
    unread.value = res.unread
  } catch {
    unread.value = 0
  }
}

watch(() => [auth.user?.userId, route.fullPath, ui.alertsVersion], loadUnread, { immediate: true })
watch(() => route.fullPath, () => { mobileMenuOpen.value = false })

const activeOf = computed(() => tab => isTabActive(tab, route.path))

function openMobilePreview () {
  const href = router.resolve(route.fullPath).href
  const preview = window.open(
    href,
    'hangat-mobile-preview',
    'popup,width=390,height=844,resizable=yes,scrollbars=yes'
  )
  preview?.focus()
}

async function onLogout () {
  mobileMenuOpen.value = false
  await auth.logout()
  ui.toast('로그아웃했어요')
  router.push({ name: 'login' })
}
</script>

<template>
  <nav class="nav">
    <div class="nav-in">
      <RouterLink to="/" class="logo">
        <!-- 마크는 장식이다 — 바로 뒤에 "한갓지도" 글자가 있어 alt 를 비운다 -->
        <img class="brand-mk" src="/hangat-mark.png" alt="" width="166" height="144">
        <span>한<em>갓</em>지도</span>
      </RouterLink>

      <!-- 왼쪽: 메인 · AI코스 · 지도 -->
      <RouterLink
        v-for="t in LEFT_TABS"
        :key="t.to"
        :to="t.to"
        class="tab"
        :class="{ on: activeOf(t) }"
        :aria-current="activeOf(t) ? 'page' : undefined"
      >
        {{ t.label }}
      </RouterLink>

      <div class="sp" />

      <!-- 오른쪽: 마이페이지부터 -->
      <RouterLink
        v-for="t in RIGHT_TABS"
        :key="t.to"
        :to="t.to"
        class="tab"
        :class="{ on: activeOf(t) }"
        :aria-current="activeOf(t) ? 'page' : undefined"
      >
        {{ t.label }}
        <span v-if="t.badge && unread" class="nbadge tnum">{{ unread }}</span>
      </RouterLink>

      <button
        type="button"
        class="mobile-preview-button"
        aria-label="모바일 화면으로 미리보기"
        title="모바일 화면으로 미리보기"
        @click="openMobilePreview"
      >
        <svg width="15" height="18" viewBox="0 0 15 18" aria-hidden="true">
          <rect x="2" y="1" width="11" height="16" rx="2.5" fill="none" stroke="currentColor" stroke-width="1.5" />
          <path d="M6 14.5h3" stroke="currentColor" stroke-width="1.5" stroke-linecap="round" />
        </svg>
      </button>
      <ThemeToggle variant="icon" />
      <NotificationBell />

      <div class="account-actions">
        <template v-if="auth.isLoggedIn">
          <RouterLink to="/mypage/profile" class="who">
            <span class="av">{{ auth.initial }}</span>
            <b>{{ auth.displayName }}</b>
          </RouterLink>
          <button class="ghost" @click="onLogout">로그아웃</button>
        </template>
        <template v-else>
          <!-- 회원가입 버튼은 헤더에서 뺐다. 가입 경로는 로그인 화면 안에 있다 -->
          <RouterLink to="/login" class="ghost lg">로그인</RouterLink>
        </template>
      </div>

      <button
        type="button"
        class="mobile-menu-button"
        :aria-expanded="mobileMenuOpen"
        aria-controls="mobile-header-menu"
        :aria-label="mobileMenuOpen ? '메뉴 닫기' : '메뉴 열기'"
        @click="mobileMenuOpen = !mobileMenuOpen"
      >
        <span aria-hidden="true" />
        <span aria-hidden="true" />
        <span aria-hidden="true" />
      </button>
    </div>

    <div
      v-show="mobileMenuOpen"
      id="mobile-header-menu"
      class="mobile-header-menu"
      aria-label="모바일 메뉴"
    >
      <RouterLink
        v-for="t in NAV_TABS"
        :key="`mobile-${t.to}`"
        :to="t.to"
        class="mobile-menu-link"
        :class="{ on: activeOf(t) }"
        :aria-current="activeOf(t) ? 'page' : undefined"
        @click="mobileMenuOpen = false"
      >
        {{ t.label }}
      </RouterLink>
      <RouterLink
        v-if="auth.isLoggedIn"
        to="/mypage/profile"
        class="mobile-menu-link"
        @click="mobileMenuOpen = false"
      >
        {{ auth.displayName }} · 마이페이지
      </RouterLink>
      <RouterLink
        v-else
        to="/login"
        class="mobile-menu-link mobile-account-link"
        @click="mobileMenuOpen = false"
      >
        로그인
      </RouterLink>
      <button v-if="auth.isLoggedIn" class="mobile-menu-logout" @click="onLogout">
        로그아웃
      </button>
    </div>
  </nav>
</template>

<style scoped>
/*
  base.css 의 `.logo{display:flex}` 를 그대로 쓴다.
  여기 있던 `display:inline-block` 은 예전에 마크가 인라인 SVG 였을 때 남은 것인데,
  마스코트 <img>(display:block)로 바꾸자 마크와 글자가 **두 줄로 갈라졌다.**
  스코프 스타일이 base.css 보다 우선해서 flex 가 먹지 않았기 때문이다.
*/
.tab { position: relative; }
.account-actions { display: contents; }

.mobile-preview-button {
  width: 34px;
  height: 34px;
  flex: 0 0 auto;
  display: inline-flex;
  align-items: center;
  justify-content: center;
  border-radius: 50%;
  color: var(--tx2);
  transition: background .15s, color .15s;
}
.mobile-preview-button:hover { background: var(--surf2); color: var(--tx); }

.mobile-menu-button,
.mobile-header-menu { display: none; }
.mobile-menu-button {
  width: 42px;
  height: 42px;
  align-items: center;
  justify-content: center;
  flex-direction: column;
  gap: 4px;
  border-radius: 50%;
  color: var(--tx);
}
.mobile-menu-button:hover { background: var(--surf2); }
.mobile-menu-button span {
  width: 18px;
  height: 2px;
  border-radius: 2px;
  background: currentColor;
  transition: transform .18s, opacity .18s;
}
.mobile-menu-button[aria-expanded="true"] span:nth-child(1) { transform: translateY(6px) rotate(45deg); }
.mobile-menu-button[aria-expanded="true"] span:nth-child(2) { opacity: 0; }
.mobile-menu-button[aria-expanded="true"] span:nth-child(3) { transform: translateY(-6px) rotate(-45deg); }

.nbadge {
  display: inline-block;
  min-width: 16px;
  margin-left: 4px;
  padding: 0 5px;
  border-radius: var(--rp);
  /* 알림 표시는 전부 주색 하나로 통일한다 (종 배지·하단 탭 점과 같은 색) */
  background: var(--ac);
  color: var(--on-ac);
  font-size: 10.5px;
  font-weight: 800;
  line-height: 16px;
  text-align: center;
  vertical-align: 1px;
}

.who {
  display: flex;
  align-items: center;
  gap: 7px;
  padding: 6px 13px 6px 7px;
  border-radius: var(--rp);
  background: var(--surf2);
  font-size: 12.5px;
}
.who:hover { background: var(--line); }
.who b { font-weight: 700; }
.av {
  width: 22px; height: 22px; border-radius: 50%;
  background: var(--ac-bg); color: var(--ac-dk);
  font-size: 10.5px; font-weight: 800;
  display: flex; align-items: center; justify-content: center;
}
.ghost.lg { background: var(--ac-bg); color: var(--ac-dk); font-weight: 700; }
.ghost.lg:hover { filter: brightness(.96); background: var(--ac-bg); }

@media (max-width: 768px) {
  .mobile-preview-button { display: none; }
  .account-actions { display: none; }
  .mobile-menu-button { display: inline-flex; }
  .mobile-header-menu {
    position: absolute;
    top: 60px;
    left: 0;
    right: 0;
    display: grid;
    grid-template-columns: repeat(2, minmax(0, 1fr));
    gap: 7px;
    padding: 12px var(--margin-mobile) 16px;
    border-top: 1px solid var(--line);
    border-bottom: 1px solid var(--line);
    background: color-mix(in srgb, var(--bg) 96%, transparent);
    box-shadow: var(--sh2);
    backdrop-filter: blur(14px);
  }
  .mobile-menu-link,
  .mobile-menu-logout {
    min-height: 44px;
    display: flex;
    align-items: center;
    justify-content: center;
    border-radius: var(--r-md);
    background: var(--surf2);
    color: var(--tx2);
    font-family: var(--font-head);
    font-size: 12.5px;
    font-weight: 800;
  }
  .mobile-menu-link.on,
  .mobile-account-link { background: var(--ac-bg); color: var(--ac-dk); }
  .mobile-menu-logout { color: var(--busy); background: var(--busy-bg); }
}

/*
  좁은 데스크톱에서는 모바일 메뉴 대신 압축한 상단 메뉴를 유지한다.
  주요 탐색을 우선하기 위해 모바일 미리보기와 계정 이름만 감춘다.
*/
@media (max-width: 768px) and (hover: hover) and (pointer: fine) {
  .mobile-preview-button { display: none; }
  .account-actions { display: contents; }
  .mobile-menu-button,
  .mobile-header-menu { display: none; }
  .who { padding: 6px; }
  .who b { display: none; }
  .ghost { padding: 8px 10px; }
}

@media (prefers-reduced-motion: reduce) {
  .mobile-menu-button span { transition: none; }
}
</style>
