<script setup>
/**
 * 비밀번호 재설정 (요구사항 정의서 USER_003).
 *
 * 한 화면 3단계다. **URL 에 아무 값도 싣지 않는다** — 코드도 티켓도 메모리에만 둔다.
 *   1) 이메일 입력 → 메일로 6자리 영문·숫자 코드
 *   2) 코드 입력 → 확인되면 마스킹된 이메일 공개 + 재설정 티켓
 *   3) 새 비밀번호 설정
 *
 * 정의서와 다른 점(임시 비밀번호 → 코드, 마스킹 이메일 노출 시점)은
 * api/auth.js 의 USER_003 블록과 ../../../보안.md 에 근거와 함께 적어 두었다.
 */
import { computed, onBeforeUnmount, reactive, ref } from 'vue'
import { useRouter } from 'vue-router'
import AuthLayout from '../../components/auth/AuthLayout.vue'
import FieldText from '../../components/auth/FieldText.vue'
import FieldPassword from '../../components/auth/FieldPassword.vue'
import {
  sendResetCode, verifyResetCode, resetPassword
} from '../../api/userAuth.js'
import {
  isValidEmail, checkEmail,
  EMAIL_INPUT_FILTER, EMAIL_INPUT_MESSAGE
} from '../../utils/validators.js'
import { checkNewPassword as checkPassword, hasAllowedPasswordCharacters, PASSWORD_CHARACTER_MESSAGE } from '../../components/security/passwordPolicy.js'
import { useUiStore } from '../../stores/ui.js'
import { ApiError } from '../../api/errors.js'
import { AUTH_HERO_IMAGES } from '../../data/authHeroImages.js'

const RESET_CODE_LENGTH = 6
const RESET_CODE_FILTER = /[^2-9A-HJ-NP-Za-hj-np-z]/g
const router = useRouter()
const ui = useUiStore()

const step = ref(1)
const busy = ref(false)
const serverError = ref('')

const form = reactive({ email: '', code: '' })
const touched = reactive({})

/* 코드·티켓은 화면 메모리에만 둔다. 새로고침하면 처음부터 다시. */
const requestId = ref(null)
const ticket = ref(null)
const account = ref(null)

/* 남은 시간 카운트다운 */
const deadline = ref(0)
const now = ref(Date.now())
let timer = null
function startCountdown (ms) {
  // now 를 함께 갱신하지 않으면, 폼을 채우는 데 걸린 시간만큼 남은 시간이 부풀어 보인다
  now.value = Date.now()
  deadline.value = now.value + ms
  clearInterval(timer)
  timer = setInterval(() => { now.value = Date.now() }, 1000)
}
onBeforeUnmount(() => clearInterval(timer))

const remain = computed(() => Math.max(0, deadline.value - now.value))
const remainText = computed(() => {
  const s = Math.ceil(remain.value / 1000)
  return `${Math.floor(s / 60)}:${String(s % 60).padStart(2, '0')}`
})
const expired = computed(() => deadline.value > 0 && remain.value === 0)

/* ── 1단계 ── */
const emailError = computed(() => {
  if (!touched.email) return ''
  if (!form.email.trim()) return '가입한 이메일을 입력해 주세요'
  // 허용 문자가 좁아진 만큼(@ . 만) 왜 막혔는지까지 알려준다
  return checkEmail(form.email).message
})
const canRequest = computed(() => isValidEmail(form.email) && !busy.value)

async function sendCode () {
  touched.email = 1
  serverError.value = ''
  if (!canRequest.value) return
  busy.value = true
  try {
    const res = await sendResetCode(form.email.trim())
    requestId.value = res.requestId
    form.code = ''
    startCountdown(res.expiresInMs)
    step.value = 2
  } catch (e) {
    serverError.value = e instanceof ApiError ? e.message : '요청을 보내지 못했어요'
  } finally {
    busy.value = false
  }
}

/* ── 2단계 ── */
const normalizedCode = computed(() => form.code.trim().toUpperCase())
const canVerify = computed(() =>
  normalizedCode.value.length === RESET_CODE_LENGTH && !busy.value && !expired.value
)

function onCodeInput (v) {
  form.code = String(v)
    .replace(RESET_CODE_FILTER, '')
    .toUpperCase()
    .slice(0, RESET_CODE_LENGTH)
}

async function submitCode () {
  serverError.value = ''
  if (!canVerify.value) return
  busy.value = true
  try {
    const res = await verifyResetCode({
      code: normalizedCode.value,
      requestId: requestId.value
    })
    ticket.value = res.ticket
    account.value = res
    startCountdown(res.expiresInMs)
    step.value = 3
  } catch (e) {
    serverError.value = e instanceof ApiError ? e.message : '확인하지 못했어요'
    form.code = ''
  } finally {
    busy.value = false
  }
}

function backToRequest () {
  step.value = 1
  serverError.value = ''
  form.code = ''
  requestId.value = null
  clearInterval(timer)
  deadline.value = 0
}

/* ── 3단계 ── */
const pwForm = reactive({ password: '', passwordConfirm: '' })
const pwFields = reactive({})
const breachState = ref({ breached: false })

const pw = computed(() => checkPassword(pwForm.password))
const pwError = computed(() => {
  if (pwFields.password) return pwFields.password
  if (!touched.password || !pwForm.password) return ''
  return pw.value.ok ? '' : pw.value.errors[0]
})
const confirmError = computed(() => {
  if (pwFields.passwordConfirm) return pwFields.passwordConfirm
  if (!touched.passwordConfirm) return ''
  if (!pwForm.passwordConfirm) return '한 번 더 입력해 주세요'
  if (!hasAllowedPasswordCharacters(pwForm.passwordConfirm)) return PASSWORD_CHARACTER_MESSAGE
  if (pwForm.password !== pwForm.passwordConfirm) {
    return '비밀번호가 서로 달라요'
  }
  return ''
})
const canReset = computed(() =>
  pw.value.ok &&
  !breachState.value.breached &&
  !!pwForm.passwordConfirm &&
  pwForm.password === pwForm.passwordConfirm &&
  !busy.value
)

async function submitPassword () {
  touched.password = 1
  touched.passwordConfirm = 1
  serverError.value = ''
  Object.keys(pwFields).forEach(k => { pwFields[k] = '' })
  if (!canReset.value) return
  busy.value = true
  try {
    await resetPassword({ ticket: ticket.value, ...pwForm })
    clearInterval(timer)
    step.value = 4
    ui.toast('비밀번호를 바꿨어요')
    setTimeout(() => router.replace({ name: 'login' }), 1500)
  } catch (e) {
    if (e instanceof ApiError && e.detail) {
      Object.entries(e.detail).forEach(([k, v]) => { pwFields[k] = v })
      serverError.value = e.message
    } else {
      serverError.value = e instanceof ApiError ? e.message : '바꾸지 못했어요'
    }
  } finally {
    busy.value = false
  }
}

const TITLES = {
  1: '비밀번호 재설정',
  2: '메일로 보낸 코드를 입력해 주세요',
  3: '새 비밀번호 설정',
  4: '비밀번호를 바꿨어요'
}
const LEADS = {
  1: '가입할 때 쓴 이메일을 입력하면 6자리 코드를 보내드려요.',
  2: '',
  3: '앞으로 이 비밀번호로 로그인해요.',
  4: ''
}
</script>

<template>
  <AuthLayout back-to="/login" back-label="로그인으로 돌아가기" :title="TITLES[step]" :lead="LEADS[step]" :hero-images="AUTH_HERO_IMAGES">
    <!-- ── 1단계: 코드 요청 ── -->
    <form v-if="step === 1" novalidate @submit.prevent="sendCode">
      <FieldText
        v-model="form.email"
        label="아이디 (가입 이메일)"
        type="email"
        required
        placeholder="hangat@example.com"
        autocomplete="username"
        inputmode="email"
        :error="emailError"
        :filter="EMAIL_INPUT_FILTER"
        :filter-message="EMAIL_INPUT_MESSAGE"
        icon="mail"
        @blur="touched.email = 1"
      />
      <p v-if="serverError" class="srv" role="alert">{{ serverError }}</p>

      <button class="cta" type="submit" :disabled="!canRequest">
        {{ busy ? '보내는 중…' : '인증 코드 받기' }}
      </button>
    </form>

    <!-- ── 2단계: 코드 확인 ── -->
    <template v-else-if="step === 2">
      <p class="body">
        입력하신 정보와 일치하는 계정이 있다면 그 주소로 <b>{{ RESET_CODE_LENGTH }}자리 영문·숫자 코드</b>를 보냈어요.
      </p>
      <p class="body muted">
        일치 여부는 알려드리지 않아요. 이 화면에서 계정 유무를 알 수 있으면
        가입자 명단을 훑는 데 쓰일 수 있거든요.
      </p>

      <form novalidate @submit.prevent="submitCode">
        <div class="fld">
          <label class="fld-lab" for="reset-code">
            인증 코드<span class="req" aria-hidden="true">*</span>
          </label>
          <div class="fld-box code" :class="{ err: !!serverError }">
            <input
              id="reset-code"
              class="code-in tnum"
              type="text"
              :value="form.code"
              inputmode="text"
              autocomplete="one-time-code"
              :maxlength="RESET_CODE_LENGTH"
              placeholder="A2B3C4"
              spellcheck="false"
              :aria-describedby="serverError ? 'code-msg' : 'code-hint'"
              @input="onCodeInput($event.target.value)"
            >
            <span class="timer tnum" :class="{ out: expired }">{{ expired ? '만료' : remainText }}</span>
          </div>
          <p v-if="serverError" id="code-msg" class="fld-msg err" role="alert">{{ serverError }}</p>
          <p v-else id="code-hint" class="fld-msg">
            코드는 10분 동안만 쓸 수 있고, 5번 틀리면 만료돼요.
          </p>
        </div>

        <button class="cta" type="submit" :disabled="!canVerify">
          {{ busy ? '확인하는 중…' : '코드 확인' }}
        </button>
      </form>

      <div class="acts">
        <button class="btn2" type="button" @click="backToRequest">코드 다시 받기</button>
      </div>

    </template>

    <!-- ── 3단계: 새 비밀번호 ── -->
    <template v-else-if="step === 3">
      <div class="who">
        <span class="lbl">계정</span>
        <b>{{ account.maskedEmail }}</b>
        <span class="exp tnum">{{ remainText }} 남음</span>
      </div>

      <form novalidate @submit.prevent="submitPassword">
        <FieldPassword
          v-model="pwForm.password"
          label="새 비밀번호"
          autocomplete="new-password"
          show-guidance
          check-breach
          :error="pwError"
          @blur="touched.password = 1"
          @breach="breachState = $event"
        />
        <FieldPassword
          v-model="pwForm.passwordConfirm"
          label="새 비밀번호 확인"
          autocomplete="new-password"
          :error="confirmError"
          @blur="touched.passwordConfirm = 1"
          @enter="submitPassword"
        />

        <p v-if="serverError" class="srv" role="alert">{{ serverError }}</p>

        <button class="cta" type="submit" :disabled="!canReset">
          {{ busy ? '바꾸는 중…' : '비밀번호 바꾸기' }}
        </button>
      </form>
    </template>

    <!-- ── 완료 ── -->
    <template v-else>
      <p class="body">
        <b>{{ account.maskedEmail }}</b> 계정의 비밀번호를 바꿨어요.<br>
        로그인되어 있던 다른 기기는 모두 로그아웃했어요.
      </p>
      <p class="body muted">로그인 화면으로 이동할게요.</p>
    </template>

    <template #footer>
      <p class="links">
        비밀번호가 기억났나요? <RouterLink to="/login">로그인</RouterLink>
      </p>
    </template>
  </AuthLayout>
</template>

<style scoped>
.body { font-size: 13px; color: var(--tx2); line-height: 1.8; margin-bottom: 10px; }
.body.muted { font-size: 12px; color: var(--tx3); }
.body b { color: var(--tx); font-weight: 800; }

/* 코드 입력 — 자릿수가 눈에 들어오도록 크게 띄우고 자간을 벌린다 */
.fld-box.code { padding: 6px 12px 6px 16px; }
.code-in {
  font-size: 26px !important; font-weight: 800; letter-spacing: .34em;
  padding: 6px 0;
}
.code-in::placeholder { letter-spacing: .34em; color: var(--line2); font-weight: 800; }
.timer { font-size: 12px; font-weight: 700; color: var(--tx3); flex-shrink: 0; }
.timer.out { color: var(--busy); }

.acts { display: flex; gap: 8px; margin-top: 12px; }
.acts > * { flex: 1; text-align: center; }

.who {
  display: flex; align-items: baseline; gap: 10px;
  padding: 12px 0; margin-bottom: 18px;
  border-top: 1px solid var(--rule); border-bottom: 1px solid var(--rule);
}
.who b { font-size: 15px; font-weight: 800; letter-spacing: -.02em; }
.who .exp { margin-left: auto; font-size: 11px; color: var(--tx3); }

.dev { margin-top: 22px; padding-top: 16px; border-top: 1px solid var(--rule); }
.dev .note { font-size: 11.5px; color: var(--tx3); line-height: 1.7; margin-top: 6px; }
.code-out {
  margin-top: 10px; text-align: center;
  font-size: 24px; font-weight: 800; letter-spacing: .12em;
  color: var(--ac-dk); background: var(--ac-bg);
  border-radius: 12px; padding: 10px;
}

.srv {
  border-left: 2px solid var(--busy); padding: 3px 0 3px 12px;
  color: var(--busy); font-size: 12px; font-weight: 600; margin-bottom: 14px;
}
.links { text-align: center; font-size: 14px; color: var(--tx2); }
.links a { font-weight: 700; color: var(--ac); text-underline-offset: 4px; }
.links a:hover { text-decoration: underline; }
</style>
