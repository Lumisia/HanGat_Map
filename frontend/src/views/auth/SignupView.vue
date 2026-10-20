<script setup>
/**
 * 회원가입 (요구사항 정의서 USER_002).
 *
 * 정의서와 달라진 곳 — 이유는 ../../../보안.md 와 api/auth.js 주석에 있다
 *  · **이메일 중복 확인 버튼을 없앴다.**
 *    "이 이메일 쓸 수 있어요/없어요"를 알려주는 API 는 가입자 명단을 통째로 뽑아낼 수 있는
 *    열거 오라클이다 (OWASP: "This user ID is already in use." 는 잘못된 응답 예시).
 *    중복 금지 자체는 서버가 그대로 지킨다. 이미 가입된 주소면 계정을 만들지 않고
 *    같은 화면으로 넘어간 뒤, 그 주소로 안내 메일이 간다.
 *  · 닉네임 중복 확인은 남겼다. 닉네임은 후기·공유 화면에 그대로 노출되는 공개 식별자라
 *    존재 여부가 새어도 추가로 잃을 게 없다. 대신 호출량을 제한한다.
 *  · 새 비밀번호는 영문·숫자·특수문자만 허용하고 기존 길이·조합·강도·유출 검사를 유지한다.
 */
import { computed, reactive, ref } from 'vue'
import { useRouter } from 'vue-router'
import AuthLayout from '../../components/auth/AuthLayout.vue'
import FieldText from '../../components/auth/FieldText.vue'
import FieldPassword from '../../components/auth/FieldPassword.vue'
import { signup, checkNicknameAvailable } from '../../api/userAuth.js'
import {
  checkNickname, isValidEmail, checkEmail,
  EMAIL_INPUT_FILTER, EMAIL_INPUT_MESSAGE
} from '../../utils/validators.js'
import { checkNewPassword as checkPassword, hasAllowedPasswordCharacters, PASSWORD_CHARACTER_MESSAGE } from '../../components/security/passwordPolicy.js'
import { putHandoff } from '../../utils/handoff.js'
import { ApiError } from '../../api/errors.js'
import { AUTH_HERO_IMAGES } from '../../data/authHeroImages.js'

const router = useRouter()

const form = reactive({
  email: '', password: '', passwordConfirm: '',
  nickname: ''
})
const touched = reactive({})
const serverFields = reactive({})
const serverError = ref('')
const busy = ref(false)
const breachState = ref({ status: 'idle', breached: false })

const dupNick = reactive({ state: 'idle', message: '' })

const pw = computed(() => checkPassword(form.password, {
  email: form.email, nickname: form.nickname
}))
const nick = computed(() => checkNickname(form.nickname))

const emailError = computed(() => {
  if (serverFields.email) return serverFields.email
  if (!touched.email) return ''
  if (!form.email.trim()) return '이메일을 입력해 주세요'
  // 허용 문자가 좁아진 만큼(@ . 만) 왜 막혔는지까지 알려준다
  return checkEmail(form.email).message
})

const pwError = computed(() => {
  if (serverFields.password) return serverFields.password
  if (!touched.password || !form.password) return ''
  return pw.value.ok ? '' : pw.value.errors[0]
})

const pwConfirmError = computed(() => {
  if (serverFields.passwordConfirm) return serverFields.passwordConfirm
  if (!touched.passwordConfirm) return ''
  if (!form.passwordConfirm) return '비밀번호 확인을 입력해 주세요'
  if (!hasAllowedPasswordCharacters(form.passwordConfirm)) return PASSWORD_CHARACTER_MESSAGE
  if (form.password !== form.passwordConfirm) {
    return '비밀번호가 서로 달라요'
  }
  return ''
})

const nickError = computed(() => {
  if (serverFields.nickname) return serverFields.nickname
  if (!touched.nickname) return ''
  if (!nick.value.ok) return nick.value.message
  if (dupNick.state === 'taken') return dupNick.message
  return ''
})
const nickOk = computed(() => (dupNick.state === 'ok' ? '쓸 수 있는 닉네임이에요' : ''))

const canSubmit = computed(() =>
  isValidEmail(form.email) &&
  pw.value.ok &&
  !breachState.value.breached &&
  !!form.passwordConfirm &&
  form.password === form.passwordConfirm &&
  nick.value.ok &&
  dupNick.state !== 'taken' &&
  !busy.value
)

async function verifyNicknameDup () {
  touched.nickname = 1
  serverFields.nickname = ''
  if (!nick.value.ok) return
  dupNick.state = 'checking'
  try {
    const res = await checkNicknameAvailable(form.nickname)
    dupNick.state = res.available ? 'ok' : 'taken'
    dupNick.message = res.available ? '' : '이미 사용 중인 닉네임이에요'
  } catch (e) {
    dupNick.state = 'idle'
    if (e instanceof ApiError && e.status === 429) serverError.value = e.message
  }
}

async function submit () {
  Object.assign(touched, { email: 1, password: 1, passwordConfirm: 1, nickname: 1 })
  Object.keys(serverFields).forEach(k => { serverFields[k] = '' })
  serverError.value = ''
  if (!canSubmit.value) return

  busy.value = true
  try {
    await signup({ ...form })
    // 이메일은 URL에 싣지 않고 다음 화면이 한 번만 읽는 메모리 handoff로 넘긴다.
    putHandoff('signup', { email: form.email.trim() })
    router.replace({ name: 'signup-done' })
  } catch (e) {
    if (e instanceof ApiError && e.detail) {
      Object.entries(e.detail).forEach(([k, v]) => { serverFields[k] = v })
      serverError.value = e.message
    } else {
      serverError.value = e instanceof ApiError ? e.message : '가입하지 못했어요. 잠시 뒤 다시 시도해 주세요'
    }
  } finally {
    busy.value = false
  }
}
</script>

<template>
  <AuthLayout
    back-to="/login"
    back-label="로그인으로 돌아가기"
    title="한갓지도 시작하기"
    lead="가입하면 코스 저장 · 장소 찜 · 리뷰 작성을 쓸 수 있어요."
    :hero-images="AUTH_HERO_IMAGES"
  >
    <form novalidate @submit.prevent="submit">
      <FieldText
        v-model="form.email"
        label="이메일"
        type="email"
        required
        placeholder="hangat@example.com"
        autocomplete="email"
        inputmode="email"
        :error="emailError"
        :filter="EMAIL_INPUT_FILTER"
        :filter-message="EMAIL_INPUT_MESSAGE"
        icon="mail"
        hint="영문·숫자와 @ . 만 쓸 수 있어요."
        @blur="touched.email = 1"
      />

      <FieldPassword
        v-model="form.password"
        label="비밀번호"
        autocomplete="new-password"
        show-guidance
        check-breach
        :context="{ email: form.email, nickname: form.nickname }"
        :error="pwError"
        @blur="touched.password = 1"
        @breach="breachState = $event"
      />

      <FieldPassword
        v-model="form.passwordConfirm"
        label="비밀번호 확인"
        autocomplete="new-password"
        :error="pwConfirmError"
        @blur="touched.passwordConfirm = 1"
      />

      <FieldText
        v-model="form.nickname"
        label="닉네임"
        required
        placeholder="후기에 표시될 이름"
        :maxlength="20"
        :error="nickError"
        :ok="nickOk"
        @blur="verifyNicknameDup"
      >
        <template #suffix>
          <button class="sw" type="button" @click="verifyNicknameDup">
            {{ dupNick.state === 'checking' ? '확인 중' : '중복 확인' }}
          </button>
        </template>
      </FieldText>

      <p v-if="serverError" class="srv" role="alert">{{ serverError }}</p>

      <button class="cta" type="submit" :disabled="!canSubmit">
        {{ busy ? '가입하는 중…' : '가입하고 인증 메일 받기' }}
      </button>
    </form>

    <template #footer>
      <p class="links">
        이미 계정이 있나요? <RouterLink to="/login">로그인</RouterLink>
      </p>
    </template>
  </AuthLayout>
</template>

<style scoped>
.srv {
  border-left: 2px solid var(--busy);
  padding: 3px 0 3px 12px;
  color: var(--busy); font-size: 12px; font-weight: 600; line-height: 1.55;
  margin-bottom: 14px;
}
.links { text-align: center; font-size: 14px; color: var(--tx2); }
.links a { font-weight: 700; color: var(--ac); text-underline-offset: 4px; }
.links a:hover { text-decoration: underline; }
</style>
