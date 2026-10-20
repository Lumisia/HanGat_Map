<script setup>
/**
 * 로그인 (요구사항 정의서 USER_001).
 *
 * 보안 관련 결정
 *  · 실패 메시지는 **한 종류**로 통일한다 — 계정 존재 여부가 새지 않게 (ASVS 6.3.8)
 *  · 로그인 폼에서 **비밀번호 구성 규칙을 검사하지 않는다**.
 *    이전 정책으로 만든 비밀번호를 가진 회원이 로그인 자체를 못 하게 되기 때문.
 *  · 이메일은 사용자가 직접 기억하기를 켠 경우에만 다시 채운다.
 *  · 시도가 쌓이면 서버(목)가 응답을 늦추고, 일정 횟수를 넘기면 423 으로 잠근다.
 */
import { computed, onMounted, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import AuthLayout from '../../components/auth/AuthLayout.vue'
import FieldText from '../../components/auth/FieldText.vue'
import FieldPassword from '../../components/auth/FieldPassword.vue'
import BrandMark from '../../components/auth/BrandMark.vue'
import { useAuthStore } from '../../stores/auth.js'
import { useUiStore } from '../../stores/ui.js'
import {
  pushRecentLogin, readRecentLogins, writeLastProvider
} from '../../api/auth.js'
import { oauthAuthorizationUrl } from '../../api/userAuth.js'
import { isValidEmail, checkEmail, EMAIL_INPUT_FILTER, EMAIL_INPUT_MESSAGE } from '../../utils/validators.js'
import { putHandoff } from '../../utils/handoff.js'
import { ApiError } from '../../api/errors.js'
import { AUTH_HERO_IMAGES } from '../../data/authHeroImages.js'
import { safeLoginReturnTo } from '../../utils/loginReturn.js'
import demoProfile from '../../assets/images/demo-profile-v1.png'

const SOCIALS = [
  { key: 'kakao', label: '카카오로 시작하기' },
  { key: 'google', label: '구글로 시작하기' }
]

const auth = useAuthStore()
const ui = useUiStore()
const route = useRoute()
const router = useRouter()

const email = ref('')
const password = ref('')
const rememberEmail = ref(false)
const touched = ref({ email: false })
const serverError = ref('')
const locked = ref(false)
const demoBusy = ref(false)
async function loginDemo () {
  if (auth.loading || demoBusy.value) return
  demoBusy.value = true; serverError.value = ''; locked.value = false
  try {
    await auth.loginDemo()
    const to = redirectTo.value
    auth.returnTo = null
    ui.toast('한갓지도 데모계정으로 로그인했어요')
    await router.replace(to)
  } catch (e) {
    serverError.value = e?.status === 429 || e?.code === 3003
      ? '요청이 많아요. 잠시 후 다시 시도해 주세요.' : '데모 체험을 시작하지 못했어요. 잠시 후 다시 시도해 주세요.'
  } finally { demoBusy.value = false }
}

onMounted(() => {
  const first = readRecentLogins().find(r => r.email)
  if (first) {
    email.value = first.email
    rememberEmail.value = true
  }
})

const emailError = computed(() => {
  if (!touched.value.email) return ''
  if (!email.value.trim()) return '이메일을 입력해 주세요'
  // 허용 문자가 좁아진 만큼(@ . 만) 왜 막혔는지까지 알려준다
  return checkEmail(email.value).message
})

const canSubmit = computed(() =>
  isValidEmail(email.value) && !!password.value && !auth.loading
)

const redirectTo = computed(() => safeLoginReturnTo(route.query.redirect ?? auth.returnTo))

async function submit () {
  touched.value.email = true
  serverError.value = ''
  locked.value = false
  if (!canSubmit.value) return
  try {
    const result = await auth.login({
      email: email.value.trim(),
      password: password.value
    })
    if (result.recoveryRequired) {
      password.value = ''
      await router.replace({ name: 'withdrawal-recovery' })
      return
    }
    pushRecentLogin(auth.user, { rememberEmail: rememberEmail.value })
    writeLastProvider('email')
    ui.toast(`${auth.displayName}님, 반가워요`)
    const to = redirectTo.value
    auth.returnTo = null
    router.replace(to)
  } catch (e) {
    if (e instanceof ApiError) {
      serverError.value = e.message
      locked.value = e.status === 423 || e.status === 429
    } else {
      serverError.value = '로그인하지 못했어요. 잠시 뒤 다시 시도해 주세요'
    }
    password.value = ''
  }
}

function startSocialLogin (provider) {
  putHandoff('oauth-login', { provider, returnTo: redirectTo.value })
  window.location.assign(oauthAuthorizationUrl(provider))
}
</script>

<template>
  <AuthLayout
    back-to="/home"
    back-label="홈으로 돌아가기"
    title="환영합니다"
    lead="나만의 제주 여행 코스를 저장하고, 혼잡도를 피해 여유로운 여행을 계획해보세요."
    :hero-images="AUTH_HERO_IMAGES"
  >
    <p v-if="route.query.withdrawn === '1'" class="notice" role="status">
      회원탈퇴가 처리되었어요. 안내 메일을 확인해 주세요. 탈퇴 시점부터 30일 이내에 로그인하면 취소할 수 있어요.
    </p>
    <p v-else-if="route.query.withdrawal === 'cancelled'" class="notice" role="status">
      회원탈퇴를 취소했어요. 다시 로그인해 주세요.
    </p>
    <p v-else-if="route.query.withdrawal === 'kept'" class="notice" role="status">
      탈퇴 상태를 유지했어요. 기존 삭제 예정 시각은 바뀌지 않아요.
    </p>
    <p v-if="route.query.redirect" class="notice">
      로그인하면 보던 화면으로 돌아가요.
    </p>
    <p v-else-if="auth.endedReason === 'SESSION_REVOKED'" class="notice warn">
      보안을 위해 이전 세션을 모두 끊었어요. 다시 로그인해 주세요.
    </p>

    <form novalidate @submit.prevent="submit">
      <FieldText
        v-model="email"
        label="이메일"
        type="email"
        required
        placeholder="hangat@example.com"
        autocomplete="username"
        inputmode="email"
        :error="emailError"
        :filter="EMAIL_INPUT_FILTER"
        :filter-message="EMAIL_INPUT_MESSAGE"
        icon="mail"
        @blur="touched.email = true"
      />

      <FieldPassword
        v-model="password"
        label="비밀번호"
        autocomplete="current-password"
        @enter="submit"
      />

      <!-- 시안: 기억하기 체크박스와 비밀번호 찾기를 한 줄 양끝에 둔다 -->
      <div class="opts">
        <label class="remember">
          <input v-model="rememberEmail" type="checkbox">
          <span>이 기기에 이메일 기억하기</span>
        </label>
        <RouterLink to="/find-password" class="forgot">비밀번호 찾기</RouterLink>
      </div>

      <p v-if="serverError" class="srv" :class="{ hard: locked }" role="alert">{{ serverError }}</p>

      <button class="cta" type="submit" :disabled="!canSubmit">
        {{ auth.loading ? '확인하는 중…' : '이메일로 로그인' }}
      </button>
    </form>

    <div class="demo-entry">
      <button class="demo-login" type="button" :disabled="auth.loading || demoBusy" @click="loginDemo">
        <span class="demo-avatar"><img :src="demoProfile" alt="" /><i /></span>
        <span class="demo-copy"><strong>{{ demoBusy ? '데모 계정으로 로그인 중…' : '한갓지도 데모계정으로 바로 로그인' }}</strong><small>지도·코스와 날씨·혼잡도 정보를 체험해 보세요</small></span>
        <span class="demo-arrow" aria-hidden="true">›</span>
      </button>
    </div>
    <div class="divider"><span>또는 소셜 로그인</span></div>
    <div class="socials">
      <div v-for="s in SOCIALS" :key="s.key" class="slot">
        <button
          class="social"
          :class="s.key"
          type="button"
          @click="startSocialLogin(s.key)"
        >
          <BrandMark :name="s.key" />{{ s.label }}
        </button>
      </div>
    </div>

    <template #footer>
      <p class="links">
        아직 계정이 없으신가요? <RouterLink to="/signup">회원가입</RouterLink>
      </p>

    </template>
  </AuthLayout>
</template>

<style scoped>
.notice {
  border-left: 2px solid var(--ac);
  padding: 2px 0 2px 12px;
  font-size: 12px; color: var(--tx2); line-height: 1.55; margin-bottom: 20px;
}
.notice.warn { border-color: var(--busy); color: var(--busy); }

/* 시안: 기억하기 ↔ 비밀번호 찾기를 한 줄 양끝에 */
.opts {
  display: flex; align-items: center; justify-content: space-between;
  gap: 12px; margin: -4px 0 22px;
}
.remember {
  display: flex; align-items: center; gap: 8px;
  font-size: 13px; color: var(--tx2); cursor: pointer;
}
.remember input { accent-color: var(--ac); width: 16px; height: 16px; }
.forgot {
  font-size: 13px; font-weight: 600; color: var(--ac);
  text-underline-offset: 4px;
}
.forgot:hover { text-decoration: underline; }

.socials { display: flex; flex-direction: column; gap: 10px; }
.slot { position: relative; }

.srv {
  border-left: 2px solid var(--busy);
  padding: 3px 0 3px 12px;
  color: var(--busy); font-size: 12px; font-weight: 600; line-height: 1.55;
  margin-bottom: 14px;
}
.srv.hard { background: var(--busy-bg); border-radius: 0 8px 8px 0; padding: 9px 12px; }

/* 시안: "아직 계정이 없으신가요? 회원가입" 을 가운데 한 줄로 */
.links { text-align: center; font-size: 14px; color: var(--tx2); }
.links a { color: var(--ac); font-weight: 700; text-underline-offset: 4px; }
.links a:hover { text-decoration: underline; }

.demo-entry {
  margin-top: 28px;
}
.demo-entry > p {
  margin-bottom: 10px;
  color: var(--tx3);
  font-size: 11.5px;
  line-height: 1.55;
  text-align: center;
}
.demo-login {
  position:relative; gap:12px; text-align:left; cursor:pointer;
  width: 100%; min-height: 84px;
  display: flex; align-items: center; justify-content: space-between;
  padding: 16px;
  border: 1px solid color-mix(in srgb, var(--ac) 34%, var(--rule));
  border-radius: 16px;
  background: var(--ac-bg);
  color: var(--ac-dk);
  font-family: var(--font-head);
  font-size: 13px; font-weight: 800;
  transition: border-color .18s ease, background .18s ease, transform .18s ease;
}
.demo-login:hover:not(:disabled) {
  border-color: var(--ac);
  background: color-mix(in srgb, var(--ac-bg) 72%, var(--bg));
  transform: translateY(-1px);
}
.demo-login:disabled { opacity: .58; cursor: wait; }
.demo-avatar { position:relative; flex-shrink:0; width:44px; height:44px; border-radius:50%; background:var(--surf); border:2px solid var(--surf); }
.demo-avatar img { width:100%; height:100%; object-fit:contain; border-radius:50%; }
.demo-avatar i { position:absolute; bottom:-1px; right:-1px; width:11px; height:11px; background:var(--ac); border:2px solid var(--surf); border-radius:50%; }
.demo-copy { flex:1; min-width:0; } .demo-copy strong { display:block; font-size:12px; line-height:1.6; color:var(--tx); } .demo-copy small { display:block; font-size:10px; font-weight:400; color:var(--tx2); line-height:1.6; margin-top:2px; }
.demo-arrow { display:grid; place-items:center; flex-shrink:0; width:30px; height:30px; border-radius:50%; background:color-mix(in srgb,var(--ac) 14%,transparent); font-size:23px; }
.demo-login:focus-visible { outline:3px solid var(--ac); outline-offset:4px; }
@media(max-width:380px) { .demo-login { padding:14px 10px; gap:8px; } .demo-avatar { width:36px; height:36px; } }

.demo { margin-top: 22px; padding-top: 16px; border-top: 1px solid var(--rule); }
.demo p { font-size: 11.5px; color: var(--tx2); line-height: 1.9; }
.demo .muted { color: var(--tx3); }
code {
  font-family: ui-monospace, SFMono-Regular, Menlo, monospace;
  font-size: 11px; background: var(--surf2); padding: 2px 6px; border-radius: 5px;
}
.demo .sw { margin-left: 6px; }
</style>
