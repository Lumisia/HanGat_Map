<script setup>
/**
 * Google·Kakao OAuth 콜백과 이메일 소유권 확인을 한 화면 상태로 처리한다.
 * 공급자 UID·OAuth 토큰·flow 원문은 브라우저 코드가 읽지 않고 HttpOnly 쿠키로만 전달된다.
 */
import { computed, onBeforeUnmount, onMounted, reactive, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import AuthLayout from '../../components/auth/AuthLayout.vue'
import FieldText from '../../components/auth/FieldText.vue'
import { useAuthStore } from '../../stores/auth.js'
import { useUiStore } from '../../stores/ui.js'
import {
  cancelOAuthFlow,
  completeOAuthLink,
  getOAuthFlow,
  sendOAuthLinkCode,
  sendOAuthSignupCode,
  verifyOAuthCode
} from '../../api/userAuth.js'
import {
  pushRecentLogin,
  writeLastProvider
} from '../../api/auth.js'
import { ApiError } from '../../api/errors.js'
import { checkEmail, checkNickname, isValidEmail } from '../../utils/validators.js'
import { stripQueryParams, takeHandoff } from '../../utils/handoff.js'
import {
  normalizeOAuthCode,
  oauthScreenForStep,
  safeOAuthReturnTo
} from './oauthFlow.js'
import { AUTH_HERO_IMAGES } from '../../data/authHeroImages.js'

const route = useRoute()
const router = useRouter()
const auth = useAuthStore()
const ui = useUiStore()

const screen = ref('working')
const flow = ref(null)
const busy = ref(false)
const error = ref('')
const form = reactive({ email: '', nickname: '', code: '' })
const touched = reactive({ email: false, nickname: false })

const handoff = takeHandoff('oauth-login') || {}
const returnTo = safeOAuthReturnTo(handoff.returnTo)

const deadline = ref(0)
const now = ref(Date.now())
let timer = null

function startCountdown (expiresIn) {
  clearInterval(timer)
  now.value = Date.now()
  deadline.value = now.value + Math.max(0, Number(expiresIn) || 0)
  timer = setInterval(() => { now.value = Date.now() }, 1000)
}

onBeforeUnmount(() => clearInterval(timer))

const remainText = computed(() => {
  if (!deadline.value) return ''
  const seconds = Math.max(0, Math.ceil((deadline.value - now.value) / 1000))
  return `${Math.floor(seconds / 60)}:${String(seconds % 60).padStart(2, '0')}`
})
const expired = computed(() => !!deadline.value && deadline.value <= now.value)
const isGoogle = computed(() => flow.value?.provider === 'GOOGLE')

const emailError = computed(() => {
  if (!touched.email || isGoogle.value) return ''
  if (!form.email.trim()) return '인증할 이메일을 입력해 주세요'
  return checkEmail(form.email).message
})
const nicknameResult = computed(() => checkNickname(form.nickname))
const nicknameError = computed(() => {
  if (!touched.nickname) return ''
  return nicknameResult.value.ok ? '' : nicknameResult.value.message
})
const canSendSignupCode = computed(() =>
  (isGoogle.value || isValidEmail(form.email)) &&
  nicknameResult.value.ok &&
  !busy.value
)
const canVerifyCode = computed(() =>
  normalizeOAuthCode(form.code).length === 6 && !expired.value && !busy.value
)

const title = computed(() => ({
  working: '소셜 로그인을 확인하고 있어요',
  profile: '가입 정보를 확인해 주세요',
  'link-confirmation': '기존 계정이 있어요',
  code: '이메일 인증 코드를 입력해 주세요',
  'verified-link-confirmation': '기존 계정에 연결할까요?',
  completed: '로그인을 마쳤어요',
  cancelled: '소셜 로그인을 취소했어요',
  failure: '소셜 로그인에 실패했어요'
})[screen.value] || '소셜 로그인')

function applyFlowResult (result) {
  flow.value = { ...(flow.value || {}), ...(result || {}) }
  screen.value = oauthScreenForStep(flow.value.nextStep)
  if (result?.expiresIn) startCountdown(result.expiresIn)
  if (isGoogle.value && flow.value.providerEmail) form.email = flow.value.providerEmail
}

async function finishLogin (login = null) {
  if (login?.user) {
    auth.user = login.user
    auth.ready = true
    auth.endedReason = null
  } else if (!auth.user) {
    await auth.restoreAfterOAuth()
  }

  if (auth.user) pushRecentLogin(auth.user, { rememberEmail: false })
  const provider = String(flow.value?.provider || handoff.provider || '').toLowerCase()
  if (['google', 'kakao'].includes(provider)) writeLastProvider(provider)

  screen.value = 'completed'
  ui.toast(`${auth.displayName}님, 반가워요`)
  auth.returnTo = null
  await router.replace(returnTo)
}

onMounted(async () => {
  const result = String(route.query.result || '')
  stripQueryParams(['result'])

  if (result === 'failure') {
    screen.value = 'failure'
    error.value = '소셜 로그인 제공자 인증을 완료하지 못했어요. 다시 시도해 주세요.'
    return
  }

  try {
    if (result === 'login') {
      await finishLogin()
      return
    }

    if (result !== 'onboarding') {
      throw new ApiError(400, 'INVALID_OAUTH_CALLBACK', '올바르지 않은 소셜 로그인 접근이에요.')
    }

    applyFlowResult(await getOAuthFlow())
  } catch (e) {
    screen.value = 'failure'
    error.value = e instanceof ApiError ? e.message : '소셜 로그인 상태를 확인하지 못했어요.'
  }
})

async function sendSignupCode () {
  touched.email = true
  touched.nickname = true
  error.value = ''
  if (!canSendSignupCode.value) return

  busy.value = true
  try {
    applyFlowResult(await sendOAuthSignupCode({
      email: isGoogle.value ? null : form.email.trim(),
      nickname: form.nickname.trim()
    }))
  } catch (e) {
    error.value = e instanceof ApiError ? e.message : '인증 코드를 보내지 못했어요.'
  } finally {
    busy.value = false
  }
}

async function agreeToLinkAndSendCode () {
  error.value = ''
  busy.value = true
  try {
    applyFlowResult(await sendOAuthLinkCode())
  } catch (e) {
    error.value = e instanceof ApiError ? e.message : '연결 인증 코드를 보내지 못했어요.'
  } finally {
    busy.value = false
  }
}

function onCodeInput (value) {
  form.code = normalizeOAuthCode(value)
}

async function submitCode () {
  error.value = ''
  if (!canVerifyCode.value) return

  busy.value = true
  try {
    const result = await verifyOAuthCode(normalizeOAuthCode(form.code))
    if (result.login) {
      await finishLogin(result.login)
      return
    }
    form.code = ''
    applyFlowResult(result)
  } catch (e) {
    error.value = e instanceof ApiError ? e.message : '인증 코드를 확인하지 못했어요.'
    form.code = ''
  } finally {
    busy.value = false
  }
}

async function completeVerifiedLink () {
  error.value = ''
  busy.value = true
  try {
    await finishLogin(await completeOAuthLink())
  } catch (e) {
    error.value = e instanceof ApiError ? e.message : '기존 계정에 연결하지 못했어요.'
  } finally {
    busy.value = false
  }
}

async function cancel () {
  busy.value = true
  try {
    await cancelOAuthFlow()
  } catch {
    // 취소는 멱등 동작이므로 서버 흐름이 이미 끝났어도 로그인 화면으로 이동한다.
  } finally {
    busy.value = false
    await router.replace({ name: 'login', query: { redirect: returnTo } })
  }
}
</script>

<template>
  <AuthLayout :title="title" :hero-images="AUTH_HERO_IMAGES">
    <p v-if="screen === 'working'" class="body">잠시만 기다려 주세요.</p>

    <form v-else-if="screen === 'profile'" novalidate @submit.prevent="sendSignupCode">
      <p class="body">
        {{ isGoogle
          ? 'Google에서 확인한 이메일로 한갓지도 인증 코드를 보내드려요.'
          : 'Kakao에서는 이메일을 받지 않으므로 사용할 이메일을 직접 인증해 주세요.' }}
      </p>
      <FieldText
        v-model="form.email"
        label="이메일"
        type="email"
        required
        :disabled="isGoogle"
        placeholder="hangat@example.com"
        autocomplete="email"
        inputmode="email"
        :error="emailError"
        :hint="isGoogle ? 'Google에서 확인된 이메일이라 바꿀 수 없어요.' : '인증 코드를 받을 주소예요.'"
        icon="mail"
        @blur="touched.email = true"
      />
      <FieldText
        v-model="form.nickname"
        label="닉네임"
        required
        placeholder="후기와 공유 화면에 표시돼요"
        :maxlength="50"
        :error="nicknameError"
        @blur="touched.nickname = true"
      />
      <p v-if="error" class="srv" role="alert">{{ error }}</p>
      <button class="cta" type="submit" :disabled="!canSendSignupCode">
        {{ busy ? '보내는 중…' : '인증 코드 받기' }}
      </button>
      <button class="btn2 full" type="button" :disabled="busy" @click="cancel">취소</button>
    </form>

    <template v-else-if="screen === 'link-confirmation'">
      <p class="body">
        소셜 계정 이메일과 같은 한갓지도 계정
        <b>{{ flow?.maskedExistingEmail }}</b>이 있어요.
      </p>
      <p class="body muted">
        이 계정의 이메일로 코드를 보내 소유권을 확인한 뒤 연결합니다.
      </p>
      <p v-if="error" class="srv" role="alert">{{ error }}</p>
      <button class="cta" type="button" :disabled="busy" @click="agreeToLinkAndSendCode">
        {{ busy ? '보내는 중…' : '코드 받고 기존 계정에 연결' }}
      </button>
      <button class="btn2 full" type="button" :disabled="busy" @click="cancel">
        연결하지 않고 로그인 화면으로
      </button>
    </template>

    <template v-else-if="screen === 'code'">
      <p class="body">
        이메일로 보낸 <b>영문자·숫자 6자리 코드</b>를 입력해 주세요.
      </p>
      <form novalidate @submit.prevent="submitCode">
        <div class="fld">
          <label class="fld-lab" for="oauth-code">인증 코드<span class="req">*</span></label>
          <div class="fld-box code" :class="{ err: !!error }">
            <input
              id="oauth-code"
              class="code-in tnum"
              type="text"
              :value="form.code"
              maxlength="6"
              inputmode="text"
              autocomplete="one-time-code"
              placeholder="A2B3C4"
              spellcheck="false"
              @input="onCodeInput($event.target.value)"
            >
            <span v-if="remainText" class="timer" :class="{ out: expired }">
              {{ expired ? '만료' : remainText }}
            </span>
          </div>
          <p v-if="error" class="fld-msg err" role="alert">{{ error }}</p>
          <p v-else class="fld-msg">코드는 5번 틀리거나 시간이 지나면 다시 받아야 해요.</p>
        </div>
        <button class="cta" type="submit" :disabled="!canVerifyCode">
          {{ busy ? '확인하는 중…' : '코드 확인' }}
        </button>
      </form>
      <button class="btn2 full" type="button" :disabled="busy" @click="cancel">취소</button>
    </template>

    <template v-else-if="screen === 'verified-link-confirmation'">
      <p class="body">
        인증한 이메일은 기존 한갓지도 계정 <b>{{ flow?.maskedExistingEmail }}</b>과 같아요.
      </p>
      <p class="body muted">
        이미 이메일 소유권을 확인했으므로 코드를 다시 보내지 않습니다.
      </p>
      <p v-if="error" class="srv" role="alert">{{ error }}</p>
      <button class="cta" type="button" :disabled="busy" @click="completeVerifiedLink">
        {{ busy ? '연결하는 중…' : '기존 계정에 연결하고 로그인' }}
      </button>
      <button class="btn2 full" type="button" :disabled="busy" @click="cancel">
        연결하지 않고 로그인 화면으로
      </button>
    </template>

    <template v-else-if="screen === 'completed'">
      <p class="body ok">로그인을 마쳤어요. 이전 화면으로 이동합니다.</p>
    </template>

    <template v-else>
      <p class="body">{{ error || '이 소셜 로그인 흐름은 더 이상 사용할 수 없어요.' }}</p>
      <button class="cta" type="button" @click="cancel">로그인 화면으로</button>
    </template>
  </AuthLayout>
</template>

<style scoped>
.body { margin-bottom: 14px; color: var(--tx2); font-size: 13px; line-height: 1.8; }
.body b { color: var(--tx); font-weight: 800; }
.body.muted { color: var(--tx3); font-size: 12px; }
.body.ok { color: var(--calm); font-weight: 700; }
.full { width: 100%; margin-top: 10px; }
.srv {
  margin-bottom: 14px; padding: 3px 0 3px 12px;
  border-left: 2px solid var(--busy); color: var(--busy);
  font-size: 12px; font-weight: 600; line-height: 1.55;
}
.fld-box.code { padding: 6px 12px 6px 16px; }
.code-in {
  padding: 6px 0; font-size: 26px !important; font-weight: 800;
  letter-spacing: .28em; text-transform: uppercase;
}
.code-in::placeholder { color: var(--line2); letter-spacing: .28em; }
.timer { flex-shrink: 0; color: var(--tx3); font-size: 12px; font-weight: 700; }
.timer.out { color: var(--busy); }
</style>
