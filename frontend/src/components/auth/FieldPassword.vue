<script setup>
/**
 * 비밀번호 입력.
 *
 * 규칙 안내를 **막는 조건**과 **권장 사항**으로 갈라 보여준다.
 * 붙여넣기와 보기 토글을 막지 않는다 — OWASP Authentication Cheat Sheet 는
 * "Allow users to paste into the username, password, and MFA fields" 라고 명시한다.
 * 붙여넣기를 막으면 비밀번호 관리자를 못 쓰게 되어 오히려 약한 비밀번호를 쓰게 된다.
 */
import { computed, ref, watch, useId } from 'vue'
import { checkPassword, checkNewPassword, hasAllowedPasswordCharacters, PASSWORD_CHARACTER_MESSAGE, passwordChecklist, estimateStrength, STRENGTH_LABEL, PASSWORD_POLICY } from '../security/passwordPolicy.js'
import { checkBreached } from '../security/breachCheck.js'
import AppIcon from '../common/AppIcon.vue'

const props = defineProps({
  modelValue: { type: String, default: '' },
  label: { type: String, default: '비밀번호' },
  autocomplete: { type: String, default: 'new-password' },
  error: { type: String, default: '' },
  /** true 면 규칙 체크리스트 + 강도 막대를 보여준다 (가입·재설정용) */
  showGuidance: { type: Boolean, default: false },
  /** 유출 조회 대상 (가입·재설정에서만) */
  checkBreach: { type: Boolean, default: false },
  context: { type: Object, default: () => ({}) },
  required: { type: Boolean, default: true }
})
const emit = defineEmits(['update:modelValue', 'blur', 'enter', 'breach'])

const uid = useId()
const inputId = computed(() => `pw-${uid}`)
const msgId = computed(() => `pwm-${uid}`)
const reveal = ref(false)
const inputRejected = ref(false)
let lastAcceptedValue = props.modelValue || ''
let selectionBeforeInput = null

watch(() => props.modelValue, v => { lastAcceptedValue = v || '' }, { flush: 'sync' })

function onBeforeInput (event) {
  selectionBeforeInput = [event.target.selectionStart, event.target.selectionEnd]
  if (event.data && !hasAllowedPasswordCharacters(event.data) && event.cancelable) {
    event.preventDefault()
    inputRejected.value = true
  }
}

function onPaste (event) {
  const text = event.clipboardData?.getData('text')
  // 일부 문자만 제거하면 사용자가 의도한 비밀번호와 달라지므로 붙여넣기 전체를 거절한다.
  if (text && !hasAllowedPasswordCharacters(text)) {
    event.preventDefault()
    inputRejected.value = true
  }
}

function onInput (event) {
  const input = event.target
  if (input.value && !hasAllowedPasswordCharacters(input.value)) {
    // 취소할 수 없는 IME 조합·자동완성 입력도 화면과 모델에 남기지 않는다.
    input.value = lastAcceptedValue
    if (selectionBeforeInput?.[0] != null) input.setSelectionRange(...selectionBeforeInput)
    inputRejected.value = true
    return
  }
  inputRejected.value = false
  lastAcceptedValue = input.value
  emit('update:modelValue', input.value)
}

function onEnter (event) {
  // 한글 조합을 확정하는 Enter는 로그인/가입 요청으로 전달하지 않는다.
  if (event.isComposing || event.keyCode === 229) {
    event.preventDefault()
    return
  }
  emit('enter')
}

const value = computed(() => props.modelValue || '')
const isNewPassword = computed(() => props.autocomplete === 'new-password')
const characterError = computed(() => isNewPassword.value && value.value && !hasAllowedPasswordCharacters(value.value)
  ? PASSWORD_CHARACTER_MESSAGE : '')
const local = computed(() => (isNewPassword.value ? checkNewPassword : checkPassword)(value.value, props.context))
const checklist = computed(() => [
  ...(isNewPassword.value ? [{ key: 'characters', required: true, label: '영문·숫자·특수문자만 (공백 제외)', pass: hasAllowedPasswordCharacters(value.value) }] : []),
  ...passwordChecklist(value.value)
])
const strength = computed(() => estimateStrength(value.value))

const breach = ref({ status: 'idle', breached: false, count: 0 })
let breachTimer = null

watch(value, v => {
  if (!props.checkBreach) return
  breach.value = { status: 'idle', breached: false, count: 0 }
  clearTimeout(breachTimer)
  // 입력이 멈춘 뒤에만 조회한다 — 글자마다 요청을 보내지 않는다
  if (!v || !local.value.ok) return
  breachTimer = setTimeout(async () => {
    breach.value = { status: 'checking', breached: false, count: 0 }
    const r = await checkBreached(v)
    breach.value = r
    emit('breach', r)
  }, 450)
})

const barWidth = computed(() => `${((strength.value.score + 1) / 5) * 100}%`)
const barClass = computed(() => ['s0', 's1', 's2', 's3', 's4'][strength.value.score])

const message = computed(() => {
  if (inputRejected.value) return { text: PASSWORD_CHARACTER_MESSAGE, kind: 'err' }
  if (characterError.value) return { text: characterError.value, kind: 'err' }
  if (props.error) return { text: props.error, kind: 'err' }
  if (breach.value.breached) {
    return {
      text: `공개된 유출 목록에 ${breach.value.count.toLocaleString('ko-KR')}번 나온 비밀번호예요. 다른 걸 써주세요`,
      kind: 'err'
    }
  }
  if (breach.value.status === 'unavailable') {
    return { text: breach.value.reason + ' — 가입은 계속할 수 있어요', kind: '' }
  }
  if (local.value.warnings.length) return { text: local.value.warnings[0], kind: '' }
  return null
})
</script>

<template>
  <div class="fld">
    <label class="fld-lab" :for="inputId">
      {{ props.label }}<span v-if="props.required" class="req" aria-hidden="true">*</span>
      <span v-if="props.required" class="sr-only">(필수)</span>
    </label>

    <div class="fld-box" :class="{ err: !!props.error || !!characterError || breach.breached }">
      <!-- 시안: 입력창 왼쪽에 자물쇠 아이콘 -->
      <AppIcon name="lock" class="fld-ico" />
      <input
        :id="inputId"
        :type="reveal ? 'text' : 'password'"
        :value="value"
        :autocomplete="props.autocomplete"
        :maxlength="PASSWORD_POLICY.maxLength"
        spellcheck="false"
        autocapitalize="none"
        autocorrect="off"
        :aria-invalid="props.error || characterError || breach.breached ? 'true' : undefined"
        :aria-describedby="message || props.showGuidance ? msgId : undefined"
        @beforeinput="onBeforeInput"
        @paste="onPaste"
        @input="onInput"
        @compositionend="onInput"
        @blur="emit('blur')"
        @keydown.enter="onEnter"
      >
      <button
        class="peek"
        type="button"
        :aria-pressed="String(reveal)"
        :aria-label="reveal ? '비밀번호 가리기' : '비밀번호 보기'"
        @click="reveal = !reveal"
      ><AppIcon :name="reveal ? 'eyeOff' : 'eye'" :size="18" /></button>
    </div>

    <div v-if="props.showGuidance && value" class="guide" :id="msgId">
      <div class="meter" role="img" :aria-label="`비밀번호 강도 ${STRENGTH_LABEL[strength.score]}`">
        <span class="track"><i :class="barClass" :style="{ width: barWidth }" /></span>
        <b :class="barClass">{{ STRENGTH_LABEL[strength.score] }}</b>
        <span v-if="breach.status === 'checking'" class="chk">유출 확인 중…</span>
        <span v-else-if="breach.status === 'ok' && !breach.breached && value" class="chk ok">유출 이력 없음</span>
      </div>

      <ul class="rules">
        <li
          v-for="r in checklist"
          :key="r.key"
          :class="{ on: r.pass, opt: !r.required }"
        >
          <i class="mk" :class="r.pass ? 'calm' : (r.required ? 'off' : 'opt')" aria-hidden="true" />
          {{ r.label }}<span v-if="!r.required" class="tag">권장</span>
        </li>
      </ul>
    </div>

    <p v-if="message" class="fld-msg" :class="message.kind" :id="props.showGuidance ? undefined : msgId">
      {{ message.text }}
    </p>
  </div>
</template>

<style scoped>
.peek {
  color: var(--tx3); flex-shrink: 0;
  padding: 6px; border-radius: var(--rp);
  display: flex; align-items: center;
}
.peek:hover { color: var(--ac-dk); background: var(--ac-bg); }

.guide { margin-top: 9px; }

.meter { display: flex; align-items: center; gap: 8px; margin-bottom: 8px; }
.track { flex: 1; height: 4px; border-radius: 2px; background: var(--surf2); overflow: hidden; }
.track > i { display: block; height: 100%; border-radius: 2px; transition: width .25s, background .25s; }
.meter b { font-size: 11px; font-weight: 800; width: 46px; text-align: right; }
.s0, .s1 { background: var(--busy); color: var(--busy); }
.s2 { background: var(--mid); color: var(--mid); }
.s3, .s4 { background: var(--calm); color: var(--calm); }
.chk { font-size: 11px; color: var(--tx3); }
.chk.ok { color: var(--calm); font-weight: 600; }

.rules { display: flex; flex-wrap: wrap; gap: 4px 14px; }
.rules li {
  display: flex; align-items: center; gap: 6px;
  font-size: 11.5px; font-weight: 600; color: var(--tx2);
}
.rules li.on { color: var(--calm); }
.rules li.opt { color: var(--tx3); font-weight: 500; }

/*
  상태 표시를 체크 이모지 대신 서비스의 혼잡 핀 모양으로 쓴다.
  지도·목록·후기에서 이미 쓰는 조형이라 새 언어를 만들지 않는다.
*/
.mk {
  width: 8px; height: 8px; border-radius: 50%; flex-shrink: 0;
  border: 1.5px solid var(--surf); box-sizing: content-box;
}
.mk.calm { background: var(--calm-st); box-shadow: 0 0 0 2px rgba(47, 125, 85, .28); }
.mk.off { background: var(--line2); }
.mk.opt { background: transparent; border-color: var(--line2); }

.tag {
  font-size: 10px; font-weight: 700; color: var(--tx3);
  background: var(--surf2); border-radius: var(--rp); padding: 1px 6px; margin-left: 2px;
}
</style>
