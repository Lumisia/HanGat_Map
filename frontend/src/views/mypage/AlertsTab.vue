<script setup>
/** 서버의 전체 알림을 유형별로 일곱 개씩 조회한다. 삭제는 현재 필터에 국한되지 않는다. */
import { computed, onMounted, ref, watch } from 'vue'
import { useRouter } from 'vue-router'
import { useNotificationStore } from '../../stores/notifications.js'
import { useNotificationInbox } from '../../composables/useNotificationInbox.js'
import { getBackendSessionVersion } from '../../api/backendClient.js'
import { NOTIFICATIONS_ENABLED, NOTIFICATION_FILTERS, notificationDestination, notificationTypeLabel, notificationActionLabel } from '../../api/notifications.js'
import { fmtRelative } from '../../utils/format.js'

const notifications = useNotificationStore()
const router = useRouter()
const { items, number, totalPages, totalElements, category, loading, error, load, filter } = useNotificationInbox()
const actionError = ref('')
const busy = ref(false)
const disabled = computed(() => busy.value || notifications.busy)
const pageButtons = computed(() => {
  const start = Math.max(0, Math.min(number.value - 2, totalPages.value - 5))
  return Array.from({ length: Math.min(5, totalPages.value) }, (_, i) => start + i)
})
onMounted(() => load())
watch(() => notifications.revision, () => { if (!busy.value) void load() })

async function open (item) {
  if (disabled.value) return
  const epoch = getBackendSessionVersion()
  busy.value = true; actionError.value = ''
  try {
    if (!item.readAt) {
      await notifications.markRead(item.id)
      item.readAt = new Date().toISOString()
    }
    if (epoch === getBackendSessionVersion()) await router.push(notificationDestination(item))
  } catch (failure) { actionError.value = failure.message || '알림을 읽음으로 표시하지 못했어요.' }
  finally { busy.value = false }
}
async function act (operation) {
  if (disabled.value) return
  busy.value = true; actionError.value = ''
  try { await operation(); await load() }
  catch (failure) { actionError.value = failure.message || '알림을 처리하지 못했어요. 다시 시도해 주세요.' }
  finally { busy.value = false }
}
function deleteAll () {
  if (disabled.value) return
  if (window.confirm('필터와 페이지에 관계없이 모든 알림을 삭제할까요? 삭제한 알림은 다시 볼 수 없어요.')) {
    void act(() => notifications.removeAll())
  }
}
</script>

<template>
  <section class="notification-inbox" aria-labelledby="notification-title">
    <header class="inbox-header">
      <div><h2 id="notification-title">알림 내역</h2><p>여행의 변화와 코스 생성 결과를 확인하세요.</p></div>
      <div v-if="NOTIFICATIONS_ENABLED" class="inbox-actions">
        <button type="button" class="btn" :disabled="disabled || !notifications.unread" @click="act(() => notifications.markAllRead())">모두 읽음</button>
        <button type="button" class="btn danger" :disabled="disabled" @click="deleteAll">모두 삭제</button>
      </div>
    </header>
    <RouterLink :to="{ name: 'my-profile' }" class="settings-link">알림 수신 설정</RouterLink>
    <p v-if="!NOTIFICATIONS_ENABLED" class="empty">알림 서비스 준비 중이에요.</p>
    <template v-else>
      <div class="filters" role="group" aria-label="알림 유형">
        <button v-for="option in NOTIFICATION_FILTERS" :key="option.value" type="button"
          :aria-pressed="category === option.value" :class="{ selected: category === option.value }"
          :disabled="disabled" @click="filter(option.value)">{{ option.label }}</button>
      </div>
      <div class="list-status">
        <span>{{ totalElements }}개의 알림</span>
        <span v-if="loading && items.length" role="status">확인 중…</span>
      </div>
      <p v-if="error || actionError" class="error" role="alert">
        {{ actionError || error }}
        <button v-if="error" type="button" class="retry" :disabled="loading" @click="load()">다시 시도</button>
      </p>
      <p v-if="loading && !items.length" class="empty" role="status">알림을 불러오고 있어요.</p>
      <p v-else-if="!error && !items.length" class="empty">{{ category === 'ALL' ? '아직 도착한 알림이 없어요.' : '이 유형의 알림이 없어요.' }}</p>
      <ol class="inbox-list">
        <li v-for="item in items" :key="item.id" :class="{ unread: !item.readAt }">
          <button type="button" class="notification-open" :disabled="disabled" @click="open(item)">
            <span class="kind">{{ notificationTypeLabel(item.type) }}</span>
            <span class="heading"><strong>{{ item.title }}</strong><span v-if="!item.readAt" class="unread-label">안 읽음</span></span>
            <span class="message">{{ item.message }}</span>
            <time :datetime="item.createdAt">{{ fmtRelative(item.createdAt) }}</time>
            <span v-if="notificationActionLabel(item)" class="action">{{ notificationActionLabel(item) }}</span>
          </button>
          <button type="button" class="delete-one" :aria-label="`${item.title} 알림 삭제`" :disabled="disabled"
            @click="act(() => notifications.remove(item.id))">×</button>
        </li>
      </ol>
      <nav v-if="totalPages > 1" class="pagination" aria-label="알림 페이지">
        <button type="button" :disabled="number === 0 || loading || disabled" @click="load(number - 1)">이전</button>
        <button v-for="page in pageButtons" :key="page" type="button" :aria-current="page === number ? 'page' : undefined"
          :disabled="loading || disabled" @click="load(page)">{{ page + 1 }}</button>
        <button type="button" :disabled="number + 1 >= totalPages || loading || disabled" @click="load(number + 1)">다음</button>
      </nav>
      <p v-if="totalPages > 1" class="page-summary">{{ number + 1 }} / {{ totalPages }} 페이지</p>
    </template>
  </section>
</template>

<style scoped>
.notification-inbox { color: var(--tx); }
.inbox-header, .heading, .list-status { display: flex; align-items: center; justify-content: space-between; gap: 12px; }
.inbox-header { flex-wrap: wrap; }
h2 { font-size: 22px; margin: 0 0 8px; }
.inbox-header p, time, .list-status, .page-summary { color: var(--tx3); font-size: 12px; line-height: 1.7; }
.settings-link { display: inline-block; margin-top: 12px; color: var(--ac); font-size: 13px; text-decoration: underline; }
.inbox-actions, .filters, .pagination { display: flex; flex-wrap: wrap; gap: 8px; }
.btn { padding: 9px 12px; border: 1px solid var(--line); border-radius: var(--rp); color: var(--ac); font-size: 12px; white-space: nowrap; }
.btn.danger { color: var(--busy); }
.filters { margin: 24px 0 16px; }
.filters button { padding: 8px 14px; border-radius: var(--rp); background: var(--surf2); color: var(--tx2); font-size: 13px; }
.filters button.selected { background: var(--ac); color: var(--on-ac); }
.inbox-list { list-style: none; padding: 0; margin: 8px 0 20px; }
.inbox-list li { position: relative; border-bottom: 1px solid var(--line); }
.inbox-list li.unread { background: var(--ac-bg); }
.notification-open { display: block; width: 100%; padding: 18px 44px 18px 14px; text-align: left; color: inherit; }
.heading { align-items: baseline; flex-wrap: wrap; gap: 6px; }
.kind { display: block; margin-bottom: 6px; font-size: 12px; color: var(--ac); }
.message { display: block; margin: 8px 0; white-space: pre-line; overflow-wrap: anywhere; line-height: 1.65; font-size: 13px; }
.unread-label, .action { color: var(--ac); font-size: 12px; }
.action { display: block; margin-top: 10px; font-weight: 700; }
.delete-one { position: absolute; right: 4px; top: 4px; width: 40px; height: 40px; border-radius: 50%; color: var(--tx3); font-size: 24px; }
.delete-one:hover { background: var(--surf2); color: var(--busy); }
.empty { padding: 32px 0; color: var(--tx3); font-size: 13px; }
.error { color: var(--busy); font-size: 13px; margin-top: 12px; }
.retry { margin-left: 8px; text-decoration: underline; }
.pagination { justify-content: center; gap: 4px; }
.pagination button { min-width: 36px; height: 36px; padding: 0 8px; border-radius: var(--r-df); color: var(--tx2); }
.pagination [aria-current="page"] { background: var(--ac); color: var(--on-ac); }
.page-summary { text-align: center; margin-top: 8px; }
button:disabled { opacity: .5; cursor: not-allowed; }
button:focus-visible { outline: 2px solid var(--ac); outline-offset: 2px; }
</style>
