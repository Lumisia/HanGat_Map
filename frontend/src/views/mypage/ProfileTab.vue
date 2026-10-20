<script setup>
/**
 * 설정 (요구사항 정의서 MY_009 · MY_010 · MY_011) + 보안 · 표시 설정.
 */
import { computed, reactive, ref } from 'vue'
import { useRouter } from 'vue-router'
import BaseModal from '../../components/common/BaseModal.vue'
import FieldText from '../../components/auth/FieldText.vue'
import FieldPassword from '../../components/auth/FieldPassword.vue'
import ThemeToggle from '../../components/layout/ThemeToggle.vue'
import NotificationPreferences from '../../components/mypage/NotificationPreferences.vue'
import WithdrawalModal from '../../components/mypage/WithdrawalModal.vue'
import { useAuthStore } from '../../stores/auth.js'
import { useUiStore } from '../../stores/ui.js'
import { useApiError } from '../../composables/useApiError.js'
import { fmtFull, fmtDateTime } from '../../utils/format.js'
import { resetPassword, sendResetCode, verifyResetCode } from '../../api/userAuth.js'
import { checkPassword, normalizePassword } from '../../components/security/passwordPolicy.js'
import { checkBirthDate, todayISODate, BIRTH_MIN_YEAR, checkNickname } from '../../utils/validators.js'
import { ApiError } from '../../api/errors.js'

const auth = useAuthStore()
const ui = useUiStore()
const router = useRouter()
const toMessage = useApiError()

const user = computed(() => auth.user)
const withdrawalOpen = ref(false)
const birth = computed(() => (user.value?.birthDate ? fmtFull(user.value.birthDate) : '등록하지 않았어요'))

/* ── MY_011 닉네임 변경 ── */
const editingNick = ref(false)
const nickDraft = ref('')
const nickError = ref('')
const nickBusy = ref(false)

/** 저장 전에 화면에서 먼저 걸러 준다 (판정은 API 가 다시 한다) */
const nickLocal = computed(() => checkNickname(nickDraft.value))
const nickChanged = computed(() =>
  nickDraft.value.trim() !== (user.value?.nickname || '')
)

function openNickEdit () {
  nickDraft.value = user.value?.nickname || ''
  nickError.value = ''
  editingNick.value = true
}

async function saveNick () {
  if (!nickLocal.value.ok) { nickError.value = nickLocal.value.message; return }
  nickBusy.value = true
  nickError.value = ''
  try {
    await auth.updateNickname(nickDraft.value.trim())
    ui.toast('닉네임을 바꿨어요')
    editingNick.value = false
  } catch (e) {
    if (e instanceof ApiError && e.detail?.nickname) nickError.value = e.detail.nickname
    else {
      const msg = toMessage(e)
      if (msg) nickError.value = msg
    }
  } finally {
    nickBusy.value = false
  }
}

/* ── 생년월일 변경 ──
   이름과 달리 **비우는 것도 정상 동작**이다. 선택 항목이라 지울 수 있어야 한다. */
const editingBirth = ref(false)
const birthDraft = ref('')
const birthError = ref('')
const birthBusy = ref(false)

/** input[type=date] 의 선택 가능 범위 — 달력 UI 자체에서 미래를 못 고르게 한다 */
const birthMax = todayISODate()
const birthMin = `${BIRTH_MIN_YEAR}-01-01`

/** 저장 전에 화면에서 먼저 알려준다 (서버 판정은 updateBirthDate 가 다시 한다) */
const birthLocal = computed(() => checkBirthDate(birthDraft.value))

function openBirthEdit () {
  birthDraft.value = user.value?.birthDate || ''
  birthError.value = ''
  editingBirth.value = true
}

async function saveBirth () {
  if (!birthLocal.value.ok) { birthError.value = birthLocal.value.message; return }
  birthBusy.value = true
  try {
    await auth.updateBirthDate(birthDraft.value)
    ui.toast(birthDraft.value ? '생년월일을 바꿨어요' : '생년월일을 지웠어요')
    editingBirth.value = false
  } catch (e) {
    if (e instanceof ApiError && e.detail?.birthDate) birthError.value = e.detail.birthDate
    else {
      const msg = toMessage(e)
      if (msg) birthError.value = msg
    }
  } finally {
    birthBusy.value = false
  }
}

/* ── 이메일 코드 기반 비밀번호 재설정 ── */
const pwOpen = ref(false)
const pwStep = ref('code')
const pwBusy = ref(false)
const pwError = ref('')
const pwFields = reactive({})
const pwForm = reactive({ code: '', password: '', passwordConfirm: '' })
const pwRequestId = ref(null)
const pwTicket = ref(null)
const pwMaskedEmail = ref('')
const breachState = ref({ breached: false })

const pwCheck = computed(() => checkPassword(pwForm.password, {
  email: user.value?.email, nickname: user.value?.nickname
}))
const normalizedPwCode = computed(() => String(pwForm.code || '')
  .replace(/[^2-9A-HJ-NP-Za-hj-np-z]/g, '')
  .toUpperCase()
  .slice(0, 6))
const canVerifyPwCode = computed(() =>
  normalizedPwCode.value.length === 6 && !pwBusy.value
)
const canResetPw = computed(() =>
  pwCheck.value.ok &&
  !breachState.value.breached &&
  normalizePassword(pwForm.password) === normalizePassword(pwForm.passwordConfirm) &&
  !!pwForm.passwordConfirm &&
  !pwBusy.value
)

async function openPasswordReset () {
  pwOpen.value = true
  pwStep.value = 'sending'
  pwBusy.value = true
  pwError.value = ''
  Object.assign(pwForm, { code: '', password: '', passwordConfirm: '' })
  try {
    const result = await sendResetCode(user.value.email)
    pwRequestId.value = result.requestId
    pwStep.value = 'code'
  } catch (e) {
    pwStep.value = 'code'
    pwError.value = e instanceof ApiError ? e.message : '인증 코드를 보내지 못했어요.'
  } finally {
    pwBusy.value = false
  }
}

function onPasswordCodeInput (value) {
  pwForm.code = String(value || '')
    .replace(/[^2-9A-HJ-NP-Za-hj-np-z]/g, '')
    .toUpperCase()
    .slice(0, 6)
}

async function submitPasswordCode () {
  pwError.value = ''
  if (!canVerifyPwCode.value) return
  pwBusy.value = true
  try {
    const result = await verifyResetCode({
      code: normalizedPwCode.value,
      requestId: pwRequestId.value
    })
    pwTicket.value = result.ticket
    pwMaskedEmail.value = result.maskedEmail
    pwStep.value = 'password'
  } catch (e) {
    pwError.value = e instanceof ApiError ? e.message : '인증 코드를 확인하지 못했어요.'
    pwForm.code = ''
  } finally {
    pwBusy.value = false
  }
}

async function submitPasswordReset () {
  pwError.value = ''
  Object.keys(pwFields).forEach(k => { pwFields[k] = '' })
  if (!canResetPw.value) return
  pwBusy.value = true
  try {
    await resetPassword({
      ticket: pwTicket.value,
      password: pwForm.password,
      passwordConfirm: pwForm.passwordConfirm
    })
    auth.handleUnauthorized(null, 'PASSWORD_RESET')
    ui.toast('비밀번호를 바꿨어요. 새 비밀번호로 로그인해 주세요')
    pwOpen.value = false
    await router.replace({ name: 'login' })
  } catch (e) {
    if (e instanceof ApiError && e.detail) {
      Object.entries(e.detail).forEach(([k, v]) => { pwFields[k] = v })
      pwError.value = e.message
    } else {
      const msg = toMessage(e)
      if (msg) pwError.value = msg
    }
  } finally {
    pwBusy.value = false
  }
}

/* ── MY_010 로그아웃 ── */
async function onLogout () {
  await auth.logout()
  ui.toast('로그아웃했어요')
  router.push({ name: 'login' })
}

</script>

<template>
  <section class="wrap">
    <!-- MY_009 프로필 -->
    <section class="blk">
      <h2 class="sect">프로필</h2>
      <dl class="defs">
        <div><dt>이메일</dt><dd>{{ user?.email }}</dd></div>
        <div>
          <dt>생년월일</dt>
          <dd>
            {{ birth }}
            <button class="sw" @click="openBirthEdit">변경</button>
          </dd>
        </div>
        <div>
          <dt>닉네임</dt>
          <dd>
            {{ user?.nickname }}
            <button class="sw" @click="openNickEdit">변경</button>
          </dd>
        </div>
      </dl>
      <p class="note">
        닉네임은 후기와 공유 화면에 보여요. 생년월일은 선택 정보라 비워 둘 수 있어요.
      </p>
    </section>

    <!-- 표시 -->
    <section class="blk">
      <h2 class="sect">화면</h2>
      <div class="row">
        <div>
          <div class="rt">테마</div>
          <p class="note">시스템을 고르면 기기 설정을 따라가요.</p>
        </div>
        <div class="display-controls">
          <ThemeToggle variant="full" />
        </div>
      </div>
    </section>

    <NotificationPreferences />

    <!-- 보안 -->
    <section class="blk">
      <h2 class="sect">보안</h2>
      <dl class="defs">
        <div>
          <dt>계정 상태</dt>
          <dd :class="user?.statusCode === 'ACTIVE' ? 'ok' : 'bad'">
            {{ user?.statusCode === 'ACTIVE' ? '정상 이용 중' : user?.statusCode }}
          </dd>
        </div>
        <div>
          <dt>이메일 인증</dt>
          <dd :class="user?.emailVerified ? 'ok' : 'bad'">
            {{ user?.emailVerified ? '완료' : '아직 안 함' }}
          </dd>
        </div>
        <div><dt>가입일</dt><dd>{{ user?.createdAt ? fmtFull(user.createdAt) : '-' }}</dd></div>
        <div><dt>마지막 로그인</dt><dd>{{ user?.lastLoginAt ? fmtDateTime(user.lastLoginAt) : '-' }}</dd></div>
      </dl>

      <p v-if="user?.demoAccount" class="note">데모 계정의 비밀번호는 변경할 수 없어요.</p>
      <div v-else class="acts">
        <button class="btn2 primary" :disabled="pwBusy" @click="openPasswordReset">
          비밀번호 변경
        </button>
      </div>
    </section>

    <!-- MY_010 로그아웃 -->
    <section class="blk">
      <h2 class="sect">로그아웃</h2>
      <p class="note">
        이 기기의 토큰을 지우고 서버 세션도 끊어요. 지도와 AI 코스는 로그아웃 뒤에도 쓸 수 있어요.
      </p>
      <div class="acts">
        <button class="btn2 danger" @click="onLogout">로그아웃</button>
      </div>
    </section>

    <section class="blk withdrawal-section">
      <h2 class="sect">회원탈퇴</h2>
      <p v-if="user?.demoAccount" class="note">데모 계정은 회원탈퇴할 수 없어요.</p>
      <template v-else>
        <p class="note">탈퇴 후 30일 이내에는 다시 로그인해 취소할 수 있어요. 30일이 지나면 계정과 관련 데이터가 영구 삭제돼요.</p>
        <div class="acts"><button class="btn2 danger" @click="withdrawalOpen = true">회원탈퇴</button></div>
      </template>
    </section>
    <WithdrawalModal v-if="withdrawalOpen" @close="withdrawalOpen = false" />

    <!-- MY_011 닉네임 변경 -->
    <BaseModal v-if="editingNick" title="닉네임 변경" @close="editingNick = false">
      <p class="msub">후기와 공유 화면에 보이는 이름이에요. 이미 쓰는 닉네임은 쓸 수 없어요.</p>
      <FieldText
        v-model="nickDraft"
        label="닉네임"
        required
        :maxlength="20"
        :error="nickError || (nickDraft && !nickLocal.ok ? nickLocal.message : '')"
        hint="2~20자, 공백 없이"
        autocomplete="nickname"
        icon="person"
        @enter="saveNick"
      />
      <div class="macts">
        <button class="btn2" :disabled="nickBusy" @click="editingNick = false">취소</button>
        <button
          class="btn2 primary"
          :disabled="nickBusy || !nickLocal.ok || !nickChanged"
          @click="saveNick"
        >{{ nickBusy ? '저장하는 중…' : '저장' }}</button>
      </div>
    </BaseModal>

    <!-- 생년월일 변경 -->
    <BaseModal v-if="editingBirth" title="생년월일 변경" @close="editingBirth = false">
      <p class="msub">마이페이지에만 표시돼요. 비워 두면 등록을 지웁니다.</p>
      <FieldText
        v-model="birthDraft"
        label="생년월일"
        type="date"
        :min="birthMin"
        :max="birthMax"
        :error="birthError || (birthDraft && !birthLocal.ok ? birthLocal.message : '')"
        @enter="saveBirth"
      />
      <div class="macts">
        <button class="btn2" :disabled="birthBusy" @click="editingBirth = false">취소</button>
        <button class="btn2 primary" :disabled="birthBusy || !birthLocal.ok" @click="saveBirth">
          {{ birthBusy ? '저장하는 중…' : '저장' }}
        </button>
      </div>
    </BaseModal>

    <!-- 비밀번호 변경 -->
    <BaseModal
      v-if="pwOpen"
      title="비밀번호 변경"
      @close="pwOpen = false"
    >
      <p v-if="pwStep === 'sending'" class="msub">계정 이메일로 인증 코드를 보내고 있어요.</p>

      <template v-else-if="pwStep === 'code'">
        <p class="msub">
          <b>{{ user?.email }}</b>로 영문자·숫자 6자리 코드를 보냈어요.
          계정 소유권을 확인한 뒤 새 비밀번호를 설정합니다.
        </p>
        <div class="fld">
          <label class="fld-lab" for="profile-reset-code">인증 코드<span class="req">*</span></label>
          <div class="fld-box code" :class="{ err: !!pwError }">
            <input
              id="profile-reset-code"
              class="code-in tnum"
              type="text"
              :value="pwForm.code"
              maxlength="6"
              autocomplete="one-time-code"
              placeholder="A2B3C4"
              @input="onPasswordCodeInput($event.target.value)"
              @keydown.enter="submitPasswordCode"
            >
          </div>
        </div>
        <p v-if="pwError" class="srv" role="alert">{{ pwError }}</p>
        <div class="macts">
          <button class="btn2" :disabled="pwBusy" @click="pwOpen = false">취소</button>
          <button class="btn2 primary" :disabled="!canVerifyPwCode" @click="submitPasswordCode">
            {{ pwBusy ? '확인하는 중…' : '코드 확인' }}
          </button>
        </div>
      </template>

      <template v-else>
        <p class="msub">
          <b>{{ pwMaskedEmail }}</b> 계정의 새 비밀번호를 입력해 주세요.
          완료하면 현재 기기를 포함한 모든 세션이 끊겨 다시 로그인해야 합니다.
        </p>
        <FieldPassword
          v-model="pwForm.password"
          label="새 비밀번호"
          autocomplete="new-password"
          show-guidance
          check-breach
          :context="{ email: user?.email, nickname: user?.nickname }"
          :error="pwFields.password || ''"
          @breach="breachState = $event"
        />
        <FieldPassword
          v-model="pwForm.passwordConfirm"
          label="새 비밀번호 확인"
          autocomplete="new-password"
          :error="pwFields.passwordConfirm || ''"
          @enter="submitPasswordReset"
        />
        <p v-if="pwError" class="srv" role="alert">{{ pwError }}</p>
        <div class="macts">
          <button class="btn2" :disabled="pwBusy" @click="pwOpen = false">취소</button>
          <button class="btn2 primary" :disabled="!canResetPw" @click="submitPasswordReset">
            {{ pwBusy ? '바꾸는 중…' : '비밀번호 바꾸기' }}
          </button>
        </div>
      </template>
    </BaseModal>
  </section>
</template>

<style scoped>
.wrap { max-width: 620px; }
.force {
  background: var(--busy-bg); color: var(--busy);
  font-size: 12.5px; font-weight: 700; line-height: 1.6;
  border-radius: 12px; padding: 12px 14px; margin-bottom: 20px;
}

.blk { margin-bottom: 30px; }
.blk .sect { margin-bottom: 4px; }

.defs { margin-bottom: 10px; }
.defs > div {
  display: flex; align-items: baseline; gap: 14px;
  padding: 11px 0; border-bottom: 1px solid var(--rule);
}
.defs dt { font-size: 12px; color: var(--tx3); font-weight: 600; width: 92px; flex-shrink: 0; }
.defs dd { font-size: 13px; font-weight: 600; flex: 1; display: flex; align-items: center; gap: 10px; }
.defs dd.ok { color: var(--calm); }
.defs dd.bad { color: var(--busy); }

.row { display: flex; align-items: center; gap: 16px; padding: 12px 0; }
.row > div:first-child { flex: 1; }
.rt { font-size: 13px; font-weight: 700; }
.display-controls { display: flex; align-items: center; gap: 8px; flex-shrink: 0; }
.display-controls .seg { width: 210px; flex-shrink: 0; }

.note { margin-top: 4px; }
.note b { color: var(--tx2); font-weight: 700; }

.acts { display: flex; gap: 8px; margin-top: 14px; }
.dev { opacity: .85; }

.msub { font-size: 12.5px; color: var(--tx2); line-height: 1.65; margin-bottom: 16px; }
.macts { display: flex; gap: 8px; margin-top: 4px; }
.macts > * { flex: 1; }
.srv {
  border-left: 2px solid var(--busy); padding: 3px 0 3px 12px;
  color: var(--busy); font-size: 12px; font-weight: 600; margin-bottom: 12px;
}
.fld-box.code { padding: 6px 12px 6px 16px; }
.code-in {
  padding: 6px 0; font-size: 24px !important; font-weight: 800;
  letter-spacing: .28em; text-transform: uppercase;
}

@media (max-width: 640px) {
  .defs > div { flex-direction: column; gap: 4px; }
  .defs dt { width: auto; }
  .row { flex-direction: column; align-items: stretch; }
  .display-controls { width: 100%; }
  .display-controls .seg { width: auto; flex: 1; }
}
</style>
