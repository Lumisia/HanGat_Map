<script setup>
/* MAP_004 날짜 선택 — 버튼을 누르면 달력이 열리고, 오늘부터 30일까지만 고를 수 있다 */
import { ref, computed, nextTick, onMounted, onBeforeUnmount } from 'vue'
import { state } from '@/stores/mapStore'
import { today, at, fmt, monthKey, FORECAST_DAYS } from '@/utils/date'

const open = ref(false)
const cursor = ref(new Date(today().getFullYear(), today().getMonth(), 1))
const trigger = ref(null)
const dialog = ref(null)

const label = computed(() => fmt(at(state.di)))
const title = computed(() => `${cursor.value.getFullYear()}년 ${cursor.value.getMonth() + 1}월`)
const lastDay = computed(() => at(FORECAST_DAYS - 1))
const canPrev = computed(() => monthKey(cursor.value) > monthKey(today()))
const canNext = computed(() => monthKey(cursor.value) < monthKey(lastDay.value))

/** 달력 한 판 — 앞의 빈칸(lead) + 날짜들 */
const cells = computed(() => {
  const y = cursor.value.getFullYear(), m = cursor.value.getMonth()
  const lead = new Date(y, m, 1).getDay()
  const days = new Date(y, m + 1, 0).getDate()
  const out = Array.from({ length: lead }, () => null)
  for (let d = 1; d <= days; d++) {
    const dt = new Date(y, m, d)
    const k = Math.round((dt - today()) / 864e5)
    out.push({ d, k, w: dt.getDay(), ok: k >= 0 && k < FORECAST_DAYS })
  }
  return out
})

async function show() {
  const d = at(state.di)
  cursor.value = new Date(d.getFullYear(), d.getMonth(), 1)
  open.value = true
  await nextTick()
  // 선택된 날짜에 포커스, 없으면 × - 쉼표 선택자 하나로 쓰면 문서 순서상 앞에 있는 × 가 항상 잡혔다(최종점검 #37)
  ;(dialog.value?.querySelector('.cd.on:not(:disabled)') ?? dialog.value?.querySelector('.cal-x'))?.focus()
}
async function close() {
  if (!open.value) return
  open.value = false
  await nextTick()
  trigger.value?.focus()
}
const shiftMonth = n => {
  cursor.value = new Date(cursor.value.getFullYear(), cursor.value.getMonth() + n, 1)
}
function pick(k) { state.di = k; close() }
/* '오늘' - 오늘을 고르고 닫는다(날짜 칸을 누른 것과 같은 동작). 이미 오늘이 선택돼 있고 오늘 달을 보고 있으면 버튼 자체를 숨긴다.
   달만 넘겨 둔 상태면 보인다 - 눌러서 돌아온다(2026-09-17 사용자 요청) */
const atToday = computed(() => state.di === 0 && monthKey(cursor.value) === monthKey(today()))

/** 방향키로 날짜 사이를 옮긴다(WAI-ARIA 달력 패턴) - ←→ 하루, ↑↓ 일주일, Home 오늘, End 마지막 날.
    달을 넘어가면 그 달로 넘기고 그 날짜에 커서를 둔다. Tab 은 전처럼 다음 버튼으로(최종점검 #37) */
const KEY_STEP = { ArrowLeft: -1, ArrowRight: 1, ArrowUp: -7, ArrowDown: 7 }
async function onGridKey(e) {
  const from = +e.target.dataset?.k
  if (!(e.key in KEY_STEP) && e.key !== 'Home' && e.key !== 'End') return
  if (Number.isNaN(from)) return
  e.preventDefault()
  const to = e.key === 'Home' ? 0 : e.key === 'End' ? FORECAST_DAYS - 1 : from + KEY_STEP[e.key]
  if (to < 0 || to >= FORECAST_DAYS) return   // 예보 범위 밖으로는 안 간다
  const d = at(to)
  if (monthKey(d) !== monthKey(cursor.value)) {
    cursor.value = new Date(d.getFullYear(), d.getMonth(), 1)
    await nextTick()
  }
  dialog.value?.querySelector(`.cd[data-k="${to}"]`)?.focus()
}

function trapFocus(e) {
  if (e.key !== 'Tab' || !dialog.value) return
  const focusable = [...dialog.value.querySelectorAll('button:not(:disabled)')]
  if (!focusable.length) return
  const first = focusable[0], last = focusable[focusable.length - 1]
  if (e.shiftKey && document.activeElement === first) {
    e.preventDefault()
    last.focus()
  } else if (!e.shiftKey && document.activeElement === last) {
    e.preventDefault()
    first.focus()
  }
}

const onKey = e => { if (e.key === 'Escape' && open.value) close() }
onMounted(() => document.addEventListener('keydown', onKey))
onBeforeUnmount(() => document.removeEventListener('keydown', onKey))
</script>

<template>
  <button ref="trigger" class="datebtn" aria-haspopup="dialog" :aria-expanded="open"
    aria-controls="map-calendar-dialog" @click="show">
    <svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor"
      stroke-width="2" stroke-linecap="round"><rect x="3.5" y="5" width="17" height="16" rx="2.6" />
      <path d="M3.5 10h17M8 3v4M16 3v4" /></svg>
    <b>{{ label }}</b><span class="ar">▾</span>
  </button>

  <Teleport to="body">
    <div v-if="open" class="map-calendar-backdrop" @click.self="close">
      <div id="map-calendar-dialog" ref="dialog" class="map-calendar" role="dialog" aria-modal="true"
        aria-labelledby="map-calendar-title" @keydown="trapFocus">
        <div class="cal-h">
          <button aria-label="이전 달" :disabled="!canPrev" @click="shiftMonth(-1)">‹</button>
          <b id="map-calendar-title">{{ title }}</b>
          <button aria-label="다음 달" :disabled="!canNext" @click="shiftMonth(1)">›</button>
        </div>
        <button class="cal-x" aria-label="닫기" @click="close">×</button>
        <!-- 오늘에서 벗어났을 때만 왼쪽 위에 조용히 뜬다(× 와 대칭). 달력 앱들의 '오늘' 자리 -->
        <button v-if="!atToday" type="button" class="cal-today" aria-label="오늘로 돌아가기" @click="pick(0)">오늘</button>
        <div class="cal-wd">
          <span class="sun">일</span><span>월</span><span>화</span><span>수</span>
          <span>목</span><span>금</span><span class="sat">토</span>
        </div>
        <div class="cal-grid" @keydown="onGridKey">
          <template v-for="(c, i) in cells" :key="i">
            <span v-if="!c"></span>
            <button v-else class="cd" :data-k="c.k"
              :class="{ sun: c.w === 0, sat: c.w === 6, on: c.k === state.di }"
              :disabled="!c.ok" @click="pick(c.k)">{{ c.d }}</button>
          </template>
        </div>
        <p class="cal-note">오늘부터 30일까지의 혼잡 예보를 볼 수 있어요</p>
      </div>
    </div>
  </Teleport>
</template>

<style scoped>
.map-calendar-backdrop{position:fixed;inset:0;z-index:2500;background:rgba(15,25,35,.45);
  display:flex;align-items:center;justify-content:center;padding:20px}
.map-calendar{position:relative;background:var(--surf);border-radius:18px;box-shadow:var(--sh2);
  width:326px;max-width:100%;padding:18px 18px 14px;color:var(--tx)}
.cal-h{display:flex;align-items:center;justify-content:center;gap:10px;margin-bottom:13px}
.cal-h b{font-size:15px;font-weight:800;min-width:104px;text-align:center;letter-spacing:-.02em}
.cal-h button{width:28px;height:28px;border-radius:9px;color:var(--tx2);font-size:18px;line-height:1}
.cal-h button:hover:not(:disabled){background:var(--surf2)}
.cal-h button:disabled{opacity:.22;cursor:default}
.cal-x{position:absolute;top:13px;right:13px;width:26px;height:26px;border-radius:8px;
  color:var(--tx3);font-size:18px;line-height:1}
.cal-x:hover{background:var(--surf2)}
.cal-wd,.cal-grid{display:grid;grid-template-columns:repeat(7,1fr);gap:2px}
.cal-wd span{text-align:center;font-size:11px;font-weight:700;color:var(--tx3);padding:3px 0 5px}
.cal-wd .sun{color:var(--busy)}
.cal-wd .sat{color:#3f7fd0}
.cal-grid .cd{height:38px;border-radius:10px;font-size:13.5px;font-weight:600;color:var(--tx)}
.cal-grid .cd.sun{color:var(--busy)}
.cal-grid .cd.sat{color:#3f7fd0}
.cal-grid .cd:hover:not(:disabled){background:var(--ac-bg);color:var(--ac-dk)}
.cal-grid .cd:disabled{opacity:.26;cursor:default}
.cal-grid .cd.on{background:var(--ac);color:var(--on-ac);font-weight:800;box-shadow:0 3px 10px rgba(0,184,163,.35)}
.cal-note{font-size:11px;color:var(--tx3);text-align:center;margin-top:12px}
.cal-today{position:absolute;top:13px;left:13px;height:26px;padding:0 11px;border-radius:13px;
  background:var(--surf2);border:1px solid var(--line2);color:var(--tx2);font-size:11.5px;font-weight:700;letter-spacing:-.01em}
.cal-today:hover{background:var(--ac-bg);border-color:var(--ac);color:var(--ac-dk)}
.cal-today:focus-visible{outline:2px solid var(--ac);outline-offset:2px}
</style>
