<script setup>
/**
 * 헤더 알림 요약. App.vue가 관리하는 공유 알림함을 사용한다.
 * SSE 수신 내용은 이동 권한이 아니다. 실제 코스와 작업 API에서 소유권을 검사한다.
 */
import { computed, onBeforeUnmount, onMounted, ref, watch, nextTick, useId } from 'vue'
import { useRoute } from 'vue-router'
import { useAuthStore } from '../../stores/auth.js'
import { useUiStore } from '../../stores/ui.js'
import { useNotificationStore } from '../../stores/notifications.js'
import { NOTIFICATIONS_ENABLED } from '../../api/notifications.js'
import { fmtRelative } from '../../utils/format.js'
import AppIcon from '../common/AppIcon.vue'

const auth = useAuthStore()
const ui = useUiStore()
const route = useRoute()

const uid = useId()
const panelId = computed(() => `bell-${uid}`)

const open = ref(false)
const notifications = useNotificationStore()
const items = computed(() => notifications.items)
const unread = computed(() => notifications.unread)
const loading = computed(() => notifications.loading)
const rootEl = ref(null)

/** 패널에는 최근 것만 보여준다. 전체는 알림 내역 화면에서 본다. */
const PREVIEW_MAX = 4
const preview = computed(() => items.value.slice(0, PREVIEW_MAX))

// 로그아웃하거나 화면을 옮기면 열린 패널을 닫는다
watch(() => [auth.isLoggedIn, route.fullPath], () => { open.value = false })

async function toggle () {
  open.value = !open.value
  if (open.value) {
    await nextTick()
    rootEl.value?.querySelector('.panel')?.focus()
  }
}

function onDocPointer (e) {
  if (!open.value) return
  if (!rootEl.value?.contains(e.target)) open.value = false
}
function onKey (e) {
  if (e.key === 'Escape' && open.value) {
    open.value = false
    rootEl.value?.querySelector('.bell')?.focus()
  }
}

onMounted(() => {
  document.addEventListener('pointerdown', onDocPointer)
  document.addEventListener('keydown', onKey)
})
onBeforeUnmount(() => {
  document.removeEventListener('pointerdown', onDocPointer)
  document.removeEventListener('keydown', onKey)
})

async function openAlert (a) {
  if (!a.readAt) {
    try {
      await notifications.markRead(a.id)
    } catch { ui.toast('읽음 처리를 하지 못했어요. 다시 눌러 주세요.') }
  }
}

async function readAll () {
  try {
    await notifications.markAllRead()
    ui.toast('알림을 읽음으로 표시했어요')
  } catch {
    ui.toast('읽음 처리를 하지 못했어요')
  }
}

async function clearHeader () {
  try {
    await notifications.clearHeader()
  } catch {
    ui.toast('알림을 숨기지 못했어요. 다시 시도해 주세요.')
  }
}

const label = computed(() =>
  unread.value ? `알림 ${unread.value}건 (읽지 않음)` : '알림'
)
</script>

<template>
  <div v-if="auth.isLoggedIn" ref="rootEl" class="bwrap">
    <button
      class="bell"
      type="button"
      :aria-label="label"
      :aria-expanded="String(open)"
      :aria-controls="open ? panelId : undefined"
      @click="toggle"
    >
      <!-- 종 -->
      <svg width="18" height="18" viewBox="0 0 24 24" aria-hidden="true">
        <path
          d="M6.5 10a5.5 5.5 0 0 1 11 0c0 3.2.7 4.7 1.4 5.6.4.5 0 1.2-.6 1.2H5.7c-.6 0-1-.7-.6-1.2.7-.9 1.4-2.4 1.4-5.6Z"
          fill="none" stroke="currentColor" stroke-width="1.8" stroke-linejoin="round"
        />
        <path d="M10 19.3a2.2 2.2 0 0 0 4 0" fill="none" stroke="currentColor" stroke-width="1.8" stroke-linecap="round" />
      </svg>
      <span v-if="unread" class="bcnt tnum">{{ unread > 9 ? '9+' : unread }}</span>
    </button>

    <div v-if="open" :id="panelId" class="panel" tabindex="-1" role="dialog" aria-label="알림">
      <div class="ph">
        <b>알림</b>
        <button v-if="unread" class="sw" type="button" :disabled="notifications.busy" @click="readAll">모두 읽음</button>
        <button v-else-if="items.length" class="sw" type="button" :disabled="notifications.busy" @click="clearHeader">모두 삭제</button>
      </div>

      <p v-if="!NOTIFICATIONS_ENABLED" class="pmsg">알림 서비스 준비 중이에요.</p>
      <p v-else-if="notifications.error && !items.length" class="pmsg" role="alert">{{ notifications.error }}</p>
      <p v-else-if="loading && !items.length" class="pmsg">불러오는 중…</p>
      <div v-else-if="!items.length" class="pmsg empty-notifications"><AppIcon name="bell" :size="28" /><p>알림이 없습니다.</p></div>

      <ul v-else class="plist">
        <li v-for="a in preview" :key="a.id">
          <button class="prow" type="button" :disabled="notifications.busy" :class="{ unread: !a.readAt }" @click="openAlert(a)">
            <span class="sev" aria-hidden="true" />
            <span class="ptx">
              <span class="pt">{{ a.title }}</span>
              <span class="pd">
                {{ a.message }}
              </span>
            </span>
            <span class="pw">{{ fmtRelative(a.createdAt) }}</span>
          </button>
        </li>
      </ul>

      <RouterLink class="pall" :to="{ name: 'my-alerts' }" @click="open = false">
        알림 내역 전체 보기
        <AppIcon name="arrowRight" :size="14" />
      </RouterLink>
    </div>
  </div>
</template>

<style scoped>
.bwrap { position: relative; display: flex; }

.bell {
  position: relative;
  width: 34px; height: 34px; border-radius: 50%;
  display: inline-flex; align-items: center; justify-content: center;
  color: var(--tx2); flex-shrink: 0;
  transition: background .15s, color .15s;
}
.bell:hover { background: var(--surf2); color: var(--tx); }

/* 배지도 같은 주색을 쓴다 — 알림 표시는 종·점·배지·탭 배지가 전부 한 색이다 */
.bcnt {
  position: absolute; top: -1px; right: -2px;
  min-width: 15px; height: 15px; padding: 0 4px;
  border-radius: var(--rp);
  background: var(--ac); color: var(--on-ac);
  font-size: 9.5px; font-weight: 800; line-height: 15px; text-align: center;
  border: 1.5px solid var(--bg); box-sizing: content-box;
}

.panel {
  position: absolute; top: calc(100% + 10px); right: 0; z-index: 1400;
  width: 316px; max-width: calc(100vw - 24px);
  background: var(--surf); border: 1px solid var(--line);
  border-radius: var(--r); box-shadow: var(--sh2);
  padding: 6px;
  outline: none;
}
.panel:focus-visible { outline: 2px solid var(--ac); outline-offset: 2px; }

.ph {
  display: flex; align-items: center; justify-content: space-between;
  padding: 8px 10px 10px;
}
.ph b { font-family: var(--font-head); font-size: 13px; font-weight: 800; }

.pmsg { padding: 10px 10px 16px; font-size: 12px; color: var(--tx3); }
.empty-notifications { display: grid; justify-items: center; gap: 12px; padding: 28px 10px; }

.plist { display: flex; flex-direction: column; }
.prow {
  width: 100%; display: flex; align-items: flex-start; gap: 9px;
  padding: 10px; border-radius: var(--r-df); text-align: left;
  transition: background .12s;
}
.prow:hover { background: var(--surf2); }

/*
  점은 **한 가지 색만** 쓴다.
  중요도별로 빨강/앰버를 나눠 쓰니 목록이 알록달록해지고,
  정작 알아야 할 "안 읽음"이 색 속에 묻혔다.
  이제 색은 읽음 여부만 나타낸다 — 안 읽음은 주색, 읽음은 흐린 경계색.
  중요도는 정렬 순서로 이미 드러난다 (HIGH 가 위로).
*/
.sev {
  width: 7px; height: 7px; border-radius: 50%; margin-top: 5px; flex-shrink: 0;
  background: var(--line2);
}
.prow.unread .sev { background: var(--ac); }

.ptx { flex: 1; min-width: 0; display: flex; flex-direction: column; gap: 2px; }
.pt {
  font-size: 12.5px; font-weight: 700; color: var(--tx2);
  overflow: hidden; text-overflow: ellipsis; white-space: nowrap;
}
.prow.unread .pt { color: var(--tx); font-weight: 800; }
.pd {
  font-size: 11px; color: var(--tx3);
  overflow: hidden; text-overflow: ellipsis; white-space: nowrap;
}
.pw { font-size: 10.5px; color: var(--tx3); flex-shrink: 0; margin-top: 1px; }

.pall {
  display: flex; align-items: center; justify-content: center; gap: 5px;
  margin-top: 4px; padding: 11px;
  border-top: 1px solid var(--rule);
  font-family: var(--font-head); font-size: 12px; font-weight: 700; color: var(--ac);
}
.pall:hover { background: var(--ac-bg); border-radius: var(--r-df); }

@media (max-width: 768px) {
  /*
    좁은 화면에서는 **뷰포트 기준**으로 놓는다.
    종 버튼 오른쪽에 계정·로그아웃이 더 있어서, 버튼 기준으로 오른쪽을 맞추면
    패널이 왼쪽으로 밀려 화면 밖으로 나간다 (375px 실측 left -74px).
    헤더는 모바일에서 60px 이고 top:0 에 고정돼 있다.
  */
  .panel {
    position: fixed;
    top: calc(60px + 8px);
    left: var(--margin-mobile);
    right: var(--margin-mobile);
    width: auto;
    max-width: none;
  }
}
</style>
