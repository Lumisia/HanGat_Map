<script setup>
/** 마이페이지: 왼쪽 프로필·메뉴, 오른쪽 활동 콘텐츠. 기존 API와 계정 보호 유지. */
import { computed, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import { useRoute } from 'vue-router'
import { useAuthStore } from '../../stores/auth.js'
import { useUiStore } from '../../stores/ui.js'
import CourseService from '../../services/CourseService'
import { useNotificationStore } from '../../stores/notifications.js'
import { listMyReviews } from '../../api/myActivity.js'
import { countFavorites } from '../../api/favorites.js'
import { getBackendSessionVersion } from '../../api/backendClient.js'
import AppIcon from '../../components/common/AppIcon.vue'
import ProfileImageEditor from '../../components/mypage/ProfileImageEditor.vue'

const auth = useAuthStore()
const ui = useUiStore()
const route = useRoute()

const notifications = useNotificationStore()
const unread = computed(() => notifications.unread)
const courseCount = ref(null)
const reviewCount = ref(null)
const favoriteCount = ref(null)
let statsVersion = 0
onBeforeUnmount(() => { statsVersion += 1 })

async function loadStats () {
  const version = ++statsVersion
  const epoch = getBackendSessionVersion()
  // 개수만 필요하므로 첫 페이지의 항목 하나만 요청한다.
  const [c, r, f] = await Promise.allSettled([
    CourseService.getSavedCourses(0, 1),
    listMyReviews({ size: 1 }),
    countFavorites()
  ])
  if (version !== statsVersion || epoch !== getBackendSessionVersion()) return
  courseCount.value = c.status === 'fulfilled' && c.value.ok ? c.value.totalElements : null
  reviewCount.value = r.status === 'fulfilled' ? r.value.totalElements : null
  favoriteCount.value = f.status === 'fulfilled' ? f.value : null
}

onMounted(loadStats)
watch(() => [route.fullPath, ui.alertsVersion], loadStats)
watch(() => auth.user?.userId, () => {
  statsVersion += 1
  reviewCount.value = null
  favoriteCount.value = null
  courseCount.value = null
  if (auth.user) loadStats()
}, { flush: 'sync' })


const TABS = [
  { name: 'my-reviews', label: '작성한 리뷰', icon: 'album', match: p => p === '/mypage/reviews' },
  { name: 'my-favorites', label: '찜한 장소', icon: 'bookmark', match: p => p === '/mypage/favorites' },
  { name: 'my-alerts', label: '알림 내역', icon: 'inbox', match: p => p === '/mypage/alerts', dot: true },
  { name: 'my-profile', label: '설정', icon: 'tune', match: p => p === '/mypage/profile' }
]
</script>

<template>
  <main class="doc mypage">
    <div class="doc-in cols">
      <aside class="side">
        <section class="hero" aria-label="내 프로필">
          <div class="profile-top">
            <ProfileImageEditor />
            <div class="profile-intro">
              <h1>{{ auth.displayName }}</h1>
              <p class="sub">나의 제주 여행과 소중한 기록을 모아 보세요.</p>
            </div>
          </div>
          <div class="stats">
            <span class="stat"><span class="lbl">저장한 코스</span><b>{{ courseCount ?? '–' }}<small>개</small></b></span>
            <span class="stat"><span class="lbl">작성한 리뷰</span><b>{{ reviewCount ?? '–' }}<small>개</small></b></span>
            <span class="stat"><span class="lbl">새 알림</span><b class="unread-count">{{ unread }}<small>건</small></b></span>
          </div>
        </section>
        <nav class="tabs thin" aria-label="마이페이지 메뉴">
          <RouterLink v-for="t in TABS" :key="t.name" :to="{ name: t.name }"
            class="mtab" :class="{ on: t.match(route.path) }">
            <AppIcon :name="t.icon" :size="19" />
            <span class="txt">{{ t.label }}</span>
            <span v-if="t.name === 'my-reviews' && reviewCount != null" class="nav-count">{{ reviewCount }}</span>
            <span v-if="t.name === 'my-favorites' && favoriteCount != null" class="nav-count">{{ favoriteCount }}</span>
            <span v-if="t.dot && unread" class="nav-count new" :aria-label="`읽지 않은 알림 ${unread}건`">{{ unread }} 신규</span>
          </RouterLink>
        </nav>
      </aside>
      <div class="body"><RouterView @reviews-changed="loadStats" @favorites-changed="loadStats" /></div>
    </div>
  </main>
</template>

<style scoped>
.mypage {
  --mp-bg: #f8fafc; --surf: #fff; --surf2: #f8fafc; --line: #e2e8f0;
  --tx: #0f172a; --tx2: #52627a; --tx3: #64748b;
  --ac: #246b45; --ac-dk: #1a5234; --ac-bg: #eff9f3;
  --mp-shadow: 0 1px 3px #0f172a08, 0 6px 20px #0f172a03;
  background: var(--mp-bg); color: var(--tx); padding: 40px 32px 80px;
  min-height: calc(100vh - 80px);
}
.cols { max-width: 1280px; display: grid; grid-template-columns: minmax(280px, 384px) minmax(0, 1fr); gap: 32px; align-items: start; }
.side, .body { min-width: 0; }
.side { display: grid; gap: 20px; }
.hero, .tabs { background: var(--surf); border: 1px solid var(--line); border-radius: 16px; box-shadow: var(--mp-shadow); }
.hero { padding: 24px; }
.profile-top { display: flex; align-items: flex-start; justify-content: space-between; gap: 12px; margin-bottom: 20px; }
.profile-top :deep(.profile-photo) { width: 100px; }
.profile-top :deep(.photo-ring) { width: 80px; height: 80px; border-radius: 18px; padding: 3px; background: var(--surf2); border: 1px solid var(--line); }
.profile-top :deep(.avatar) { border-radius: 14px; }
.profile-top :deep(.photo-note) { font-size: 10px; }
.profile-top :deep(.photo-button) { font-size: 11px; padding: 5px 10px; min-height: 32px; }
.profile-intro { flex: 1; min-width: 0; padding-top: 8px; }
.profile-top :deep(.profile-photo) { flex-shrink: 0; }
h1 { font-size: 20px; font-weight: 800; letter-spacing: -.03em; overflow-wrap: anywhere; margin: 0 0 8px; }
.sub { color: var(--tx2); font-size: 13px; line-height: 1.7; margin: 0; }
.stats { display: grid; grid-template-columns: repeat(3, minmax(0, 1fr)); background: var(--surf2); border: 1px solid var(--line); border-radius: 12px; padding: 14px 0; }
.stat { display: grid; gap: 5px; text-align: center; font-variant-numeric: tabular-nums; }
.stat + .stat { border-left: 1px solid var(--line); }
.lbl { font-size: 11px; color: var(--tx2); }
.stat b { font-size: 15px; }
.stat small { font-size: 11px; font-weight: 400; margin-left: 3px; }
.stat .unread-count { color: var(--ac); }
.tabs { display: flex; flex-direction: column; gap: 4px; padding: 8px; }
.mtab { display: flex; align-items: center; gap: 12px; padding: 12px 16px; min-height: 46px; color: var(--tx2); border: 1px solid transparent; border-radius: 11px; font-size: 14px; font-weight: 600; }
.mtab:hover { background: var(--surf2); }
.mtab.on { background: var(--ac-bg); border-color: #b9e8cd; color: var(--ac-dk); }
.mtab:focus-visible { outline: 2px solid var(--ac); outline-offset: 2px; }
.txt { flex: 1; white-space: nowrap; }
.nav-count { background: var(--surf2); border-radius: 99px; padding: 2px 7px; font-size: 11px; font-variant-numeric: tabular-nums; }
.on .nav-count { background: #cef1de; color: #1a5234; }
.nav-count.new { color: var(--busy); background: var(--busy-bg); }
.body :deep(.bar-top), .body :deep(.blk), .body :deep(.inbox-header) { padding: 24px; background: var(--surf); border: 1px solid var(--line); border-radius: 16px; box-shadow: var(--mp-shadow); margin-bottom: 24px; }
.body :deep(.sect) { font-size: 19px; letter-spacing: -.025em; }
.body :deep(.sect)::after { display: none; }
.body :deep(.cnt) { display: inline-block; color: var(--ac); background: var(--ac-bg); border: 1px solid #b9e8cd; border-radius: 99px; padding: 2px 8px; font-size: 12px; vertical-align: middle; }
.body :deep(.card) { border-radius: 16px; border-color: var(--line); box-shadow: var(--mp-shadow); }
.body :deep(.wrap) { max-width: none; }
.body :deep(.defs dd) { min-width: 0; overflow-wrap: anywhere; }
.body :deep(.cards) { gap: 16px; grid-template-columns: repeat(auto-fill, minmax(min(220px, 100%), 1fr)); }
.body :deep(.cards .card) { padding: 12px; }
.body :deep(.th) { height: 156px !important; }
.body :deep(.inbox-list li) { background: var(--surf); border-radius: 14px; }
:global(html[data-theme="dark"] .mypage) { --mp-bg: #1e292f; --surf: #29383f; --surf2: #243139; --line: #42535e; --tx: #edf3f7; --tx2: #c0ced9; --tx3: #a3b5c3; --ac: #91d7b0; --ac-dk: #b5e6c9; --ac-bg: #213f32; }
@media (prefers-color-scheme: dark) {
  :global(html:not([data-theme="light"]) .mypage) { --mp-bg: #1e292f; --surf: #29383f; --surf2: #243139; --line: #42535e; --tx: #edf3f7; --tx2: #c0ced9; --tx3: #a3b5c3; --ac: #91d7b0; --ac-dk: #b5e6c9; --ac-bg: #213f32; }
}
@media (max-width: 1023px) {
  .mypage { padding: 24px 20px 48px; }
  .cols { grid-template-columns: minmax(0, 1fr); gap: 24px; }
  .side { gap: 16px; }
  .tabs { flex-direction: row; overflow-x: auto; }
  .mtab { flex-shrink: 0; padding: 10px 12px; gap: 7px; }
  .hero { padding: 20px; }
  .profile-top { margin-bottom: 12px; }
}
@media (max-width: 480px) {
  .mypage { padding: 20px 16px calc(80px + env(safe-area-inset-bottom, 0px)); }
  .cols { gap: 20px; }
  .hero { padding: 20px; }
  .profile-top :deep(.profile-photo) { width: 80px; }
  .body :deep(.bar-top), .body :deep(.blk), .body :deep(.inbox-header) { padding: 18px; margin-bottom: 16px; }
  .body :deep(.sect) { font-size: 18px; }
}
</style>
