<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, ref } from 'vue'
import { addCalendarDays, formatCalendarDate } from '../../utils/format.js'

const props = defineProps<{
  date?: string
  time?: string
  minDate: string
  maxDate: string
}>()
const emit = defineEmits<{
  'update:date': [value: string | undefined]
  'update:time': [value: string | undefined]
}>()

const root = ref<HTMLElement>()
const timeTrigger = ref<HTMLButtonElement>()
const timeOpen = ref(false)
const dropdownPosition = ref({ top: '0px', left: '0px' })

const tripDates = computed(() => {
  if (!props.minDate || !props.maxDate || props.minDate > props.maxDate) return []
  const dates: Array<{ value: string; label: string }> = []
  for (let value = props.minDate; value <= props.maxDate; value = addCalendarDays(value, 1)) {
    const label = formatCalendarDate(value, { month: 'numeric', day: 'numeric', weekday: 'short' })
    dates.push({ value, label })
  }
  return dates
})

const timeOptions = computed(() => {
  const options: string[] = []
  for (let total = 7 * 60; total <= 22 * 60; total += 30) {
    const hour = Math.floor(total / 60)
    const minute = total % 60
    options.push(`${String(hour).padStart(2, '0')}:${String(minute).padStart(2, '0')}`)
  }
  if (props.time && !options.includes(props.time)) options.unshift(props.time)
  return options
})

function formatTime(value: string) {
  const [hour, minute] = value.split(':').map(Number)
  return `${hour < 12 ? '오전' : '오후'} ${String(hour % 12 || 12).padStart(2, '0')}:${String(minute).padStart(2, '0')}`
}

const timeLabel = computed(() => props.time ? formatTime(props.time) : '시간 선택')

function selectDate(value: string) {
  emit('update:date', props.date === value ? undefined : value)
}

function toggleTime() {
  if (timeOpen.value) {
    timeOpen.value = false
    return
  }
  const trigger = timeTrigger.value
  if (trigger) {
    const rect = trigger.getBoundingClientRect()
    const dropdownHeight = 250
    const top = rect.bottom + dropdownHeight + 7 <= window.innerHeight
      ? rect.bottom + 6
      : Math.max(12, rect.top - dropdownHeight - 6)
    const left = Math.min(Math.max(12, rect.left), window.innerWidth - 214)
    dropdownPosition.value = { top: `${top}px`, left: `${Math.max(12, left)}px` }
  }
  timeOpen.value = true
}

function selectTime(value?: string) {
  emit('update:time', value)
  timeOpen.value = false
}

function closeOnOutsideClick(event: MouseEvent) {
  if (!root.value?.contains(event.target as Node)) timeOpen.value = false
}

const closeDropdown = () => { timeOpen.value = false }
onMounted(() => {
  document.addEventListener('click', closeOnOutsideClick)
  window.addEventListener('resize', closeDropdown)
  window.addEventListener('scroll', closeDropdown)
})
onBeforeUnmount(() => {
  document.removeEventListener('click', closeOnOutsideClick)
  window.removeEventListener('resize', closeDropdown)
  window.removeEventListener('scroll', closeDropdown)
})
</script>

<template>
  <div ref="root" class="fixed-schedule-picker" @keydown.esc="timeOpen = false">
    <div class="date-chip-field">
      <div class="schedule-field-head">
        <span>방문 날짜</span>
        <button v-if="date" type="button" @click="emit('update:date', undefined)">선택 해제</button>
      </div>
      <div class="trip-date-chips" aria-label="방문 날짜 선택">
        <button
          v-for="tripDate in tripDates"
          :key="tripDate.value"
          type="button"
          :class="{ selected: tripDate.value === date }"
          :aria-pressed="tripDate.value === date"
          :title="tripDate.value"
          @click="selectDate(tripDate.value)"
        >{{ tripDate.label }}</button>
      </div>
    </div>

    <div class="time-field">
      <span class="schedule-field-label">방문 시간</span>
      <button ref="timeTrigger" type="button" class="schedule-trigger" :class="{ selected: time }" aria-haspopup="listbox" :aria-expanded="timeOpen" @click="toggleTime">
        <svg viewBox="0 0 24 24" aria-hidden="true"><circle cx="12" cy="12" r="9"/><path d="M12 7v5l3 2"/></svg>
        <span>{{ timeLabel }}</span>
        <span class="schedule-chevron">⌄</span>
      </button>
    </div>

    <Teleport to="body">
      <div v-if="timeOpen" class="compact-time-dropdown" :style="dropdownPosition" role="listbox" aria-label="방문 시간 선택" @click.stop>
        <button v-if="time" type="button" class="clear-time" @click="selectTime(undefined)">시간 선택 해제</button>
        <button
          v-for="option in timeOptions"
          :key="option"
          type="button"
          role="option"
          :aria-selected="option === time"
          :class="{ selected: option === time }"
          @click="selectTime(option)"
        >{{ formatTime(option) }}</button>
      </div>
    </Teleport>
  </div>
</template>

<style scoped>
.fixed-schedule-picker{display:grid;grid-template-columns:minmax(0,1fr) 170px;align-items:end;gap:12px;margin-top:10px}.date-chip-field,.time-field{display:grid;gap:7px;min-width:0}.schedule-field-head{display:flex;align-items:center;justify-content:space-between;gap:8px;color:var(--course-text-2);font-size:.76rem;font-weight:700}.schedule-field-head button{border:0;background:none;color:var(--course-accent-dark);font-size:.66rem;font-weight:800}.trip-date-chips{display:flex;gap:6px;overflow-x:auto;padding:1px 0 4px;scrollbar-width:thin}.trip-date-chips button{flex:0 0 auto;border:0!important;border-radius:10px!important;background:var(--course-surface)!important;padding:9px 11px!important;color:var(--course-text-2)!important;font-size:.72rem!important;font-weight:700}.trip-date-chips button:hover{background:var(--course-accent-bg)!important;color:var(--course-accent-dark)!important}.trip-date-chips button.selected{background:var(--course-accent)!important;color:var(--course-on-ac)!important}.schedule-field-label{color:var(--course-text-2);font-size:.76rem;font-weight:700}.schedule-trigger{display:flex;align-items:center;gap:8px;width:100%;min-height:39px;border:1px solid transparent!important;border-radius:11px!important;background:var(--course-surface)!important;padding:8px 10px!important;color:var(--course-text-3)!important;text-align:left}.schedule-trigger:hover,.schedule-trigger[aria-expanded=true]{border-color:var(--course-line-2)!important}.schedule-trigger.selected{color:var(--course-text)!important}.schedule-trigger svg{flex:0 0 17px;width:17px;height:17px;fill:none;stroke:var(--course-accent-dark);stroke-linecap:round;stroke-linejoin:round;stroke-width:1.8}.schedule-trigger>span:nth-child(2){overflow:hidden;flex:1;text-overflow:ellipsis;white-space:nowrap}.schedule-chevron{color:var(--course-text-3)}
.compact-time-dropdown{position:fixed;z-index:80;display:grid;width:202px;max-height:244px;overflow-y:auto;border:1px solid var(--course-line);border-radius:11px;background:var(--course-surface);padding:6px;box-shadow:0 12px 30px rgba(30,37,48,.14)}.compact-time-dropdown button{border:0;border-radius:8px;background:transparent;padding:8px 10px;color:var(--course-text-2);font-size:.73rem;text-align:left}.compact-time-dropdown button:hover{background:var(--course-accent-bg);color:var(--course-accent-dark)}.compact-time-dropdown button.selected{background:var(--course-accent);color:var(--course-on-ac);font-weight:800}.compact-time-dropdown .clear-time{margin-bottom:4px;border-bottom:1px solid var(--course-line);border-radius:6px;color:var(--course-accent-dark);font-weight:800}
@media(max-width:560px){.fixed-schedule-picker{grid-template-columns:1fr}.trip-date-chips{padding-bottom:6px}.time-field{max-width:200px}}
</style>
