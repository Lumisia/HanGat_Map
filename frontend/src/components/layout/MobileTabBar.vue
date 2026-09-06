<script setup>
/**
 * 모바일 하단 탭바.
 *
 * ⚠️ 왜 만들었나
 *    768px 이하에서 헤더가 **가로로 236px 넘쳤다**(실측). `.nav{overflow-x:auto}` 로
 *    스크롤은 됐지만, 마이페이지·로그아웃에 닿으려면 헤더를 옆으로 밀어야 했다.
 *    좁은 화면에서 주요 이동 경로가 화면 밖에 있는 건 사실상 못 쓰는 것과 같다.
 *    그래서 이동은 아래로 내리고, 헤더에는 브랜드·테마·계정만 남겼다.
 *
 * 항목은 헤더 탭과 **같다.** 모바일에서만 다른 정보 구조를 쓰면
 * 화면을 돌렸을 때 메뉴가 달라져 혼란스럽다.
 *
 * iOS 홈 인디케이터를 피하려고 `env(safe-area-inset-bottom)` 만큼 아래를 띄운다.
 * (index.html 의 viewport 에 `viewport-fit=cover` 가 있어야 이 값이 0 이 아니다.)
 */
import { computed, ref, watch } from 'vue'
import { useRoute } from 'vue-router'
import { useAuthStore } from '../../stores/auth.js'
import { useUiStore } from '../../stores/ui.js'
import { listAlerts } from '../../api/mypage.js'
import AppIcon from '../common/AppIcon.vue'
import { NAV_TABS, isTabActive } from '../../config/navTabs.js'

const route = useRoute()
const auth = useAuthStore()
const ui = useUiStore()

/*
  탭 목록은 헤더와 **같은 파일**에서 온다 (config/navTabs.js).
  통합 전에는 여기에 따로 배열이 있어서 한쪽만 고치면 메뉴가 갈라졌다.
*/
const TABS = NAV_TABS

const unread = ref(0)

async function loadUnread () {
  if (!auth.isLoggedIn) {
    unread.value = 0
    return
  }
  try {
    unread.value = (await listAlerts({ onlyUnread: true })).unread
  } catch {
    unread.value = 0
  }
}

watch(() => [auth.user?.userId, route.fullPath, ui.alertsVersion], loadUnread, { immediate: true })

/** `/mypage/reviews` 같은 하위 경로에서도 마이페이지 탭이 켜져야 한다 (판정은 navTabs.js) */
const isOn = computed(() => tab => isTabActive(tab, route.path))
</script>

<template>
  <nav class="mtabbar" aria-label="주요 메뉴">
    <RouterLink
      v-for="t in TABS"
      :key="t.to"
      :to="t.to"
      class="mt"
      :class="{ on: isOn(t) }"
      :aria-current="isOn(t) ? 'page' : undefined"
    >
      <span class="ic">
        <AppIcon :name="t.icon" :size="22" :fill="isOn(t)" />
        <span v-if="t.badge && unread" class="dot" aria-hidden="true" />
      </span>
      <span class="lb">{{ t.label }}</span>
      <span v-if="t.badge && unread" class="sr-only">읽지 않은 알림 {{ unread }}건</span>
    </RouterLink>
  </nav>
</template>

<style scoped>
.mtabbar {
  display: none;   /* 기본은 숨김 — 아래 미디어 쿼리에서만 켠다 */
  position: fixed; left: 0; right: 0; bottom: 0; z-index: 1250;
  background: color-mix(in srgb, var(--bg) 92%, transparent);
  backdrop-filter: blur(12px); -webkit-backdrop-filter: blur(12px);
  border-top: 1px solid var(--line);
  padding-bottom: env(safe-area-inset-bottom, 0px);
}

.mt {
  flex: 1;
  display: flex; flex-direction: column; align-items: center; justify-content: center;
  gap: 3px;
  /* 터치 타깃 — WCAG 2.5.5 의 44px 를 채운다 */
  min-height: 56px; padding: 7px 4px 8px;
  color: var(--tx3);
  font-family: var(--font-head); font-size: 10.5px; font-weight: 700; letter-spacing: -.01em;
  transition: color .15s;
}
.mt.on { color: var(--ac); }
.mt:active { background: var(--surf2); }

.ic { position: relative; display: flex; }
.dot {
  position: absolute; top: -2px; right: -3px;
  width: 7px; height: 7px; border-radius: 50%;
  /* 알림 표시는 전부 주색 하나로 통일 (헤더 종 배지·마이페이지 탭 배지와 같은 색) */
  background: var(--ac);
  border: 1.5px solid var(--bg); box-sizing: content-box;
}
.lb { line-height: 1; }

@media (max-width: 768px) {
  .mtabbar { display: flex; }
}

/*
  개발자 도구를 옆에 열어 뷰포트만 좁아진 데스크톱은 모바일이 아니다.
  마우스처럼 정밀한 포인터와 hover가 있으면 하단 탭을 다시 숨긴다.
*/
@media (max-width: 768px) and (hover: hover) and (pointer: fine) {
  .mtabbar { display: none; }
}
</style>
