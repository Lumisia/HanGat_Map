/**
 * 비밀번호 정책.
 *
 * ─────────────────────────────────────────────────────────────
 * 근거 (원문 확인함)
 * ─────────────────────────────────────────────────────────────
 * NIST SP 800-63B-4 §3.1.1.2
 *   · "Verifiers and CSPs SHALL require passwords that are used as a single-factor
 *      authentication mechanism to be a minimum of 15 characters in length."
 *   · "Verifiers and CSPs SHALL NOT impose other composition rules
 *      (e.g., requiring mixtures of different character types) for passwords."
 *   · "Verifiers and CSPs SHOULD permit a maximum password length of at least 64 characters."
 *   · "Verifiers SHALL compare the prospective secret against a blocklist that contains
 *      known commonly used, expected, or compromised passwords."
 *   · "Verifiers and CSPs SHALL NOT require subscribers to change passwords periodically."
 *
 * OWASP ASVS 5.0
 *   · 6.2.1  최소 8자, 15자 강력 권고
 *   · 6.2.5  "passwords of any composition can be used, without rules limiting the type of
 *             characters permitted. There must be no requirement for a minimum number of
 *             upper or lower case characters, numbers, or special characters."
 *   · 6.2.9  64자 이상 허용
 *   · 6.2.12 가입·변경 시 유출 비밀번호 대조
 *
 * OWASP Password Storage Cheat Sheet
 *   · "bcrypt has a maximum length input length of 72 bytes for most implementations."
 *
 * 개인정보의 안전성 확보조치 기준 [시행 2025.10.31.] 고시 제2025-9호
 *   · 제5조⑤ "정보주체의 인증수단을 안전하게 적용하고 관리하여야 한다"
 *     → 2023년 개정으로 **비밀번호 작성규칙(문자 종류 조합) 조항이 사라졌다.**
 *       국내법상으로도 조합 강제의 근거가 없다.
 *
 * ─────────────────────────────────────────────────────────────
 * 요구사항 정의서와의 충돌
 * ─────────────────────────────────────────────────────────────
 * xlsx USER_002: "비밀번호는 최소12글자, 특수문자1~3개, 대문자, 숫자를 넣어야한다."
 *
 *   ① "최소 12글자"  → 그대로 12자. (NIST 의 15자 SHALL 보다 낮다 — minLength 주석 참고)
 *   ② "대문자 · 숫자" → 강제하지 않고 **권장 문구로만** 보여준다 (NIST SHALL NOT / ASVS 6.2.5).
 *   ③ "특수문자 1~3개" → **상한을 없앴다.** 상한 3개는 강한 비밀번호를 거부한다.
 *      예: "correct-horse-battery-staple!!!!" 는 특수문자 4개라 정의서 규칙으로는 불합격이지만
 *      실제로는 훨씬 안전하다. 보안을 낮추는 규칙이라 그대로 둘 수 없었다.
 *
 * 정의서 문구 그대로 동작시켜야 하면 `enforceComposition: true` 하나만 바꾸면 된다.
 * 두 모드 모두 테스트가 있다 (passwordPolicy.spec.js).
 *
 * ─────────────────────────────────────────────────────────────
 * 판정 방식: 정규식
 * ─────────────────────────────────────────────────────────────
 * 형식 판정은 **정책에서 생성한 정규식 한 줄**로 한다 (`passwordRegex()`).
 * 서버(Spring `@Pattern`, DB CHECK 제약 등)로 문자열을 그대로 복사해 쓸 수 있다.
 *
 *   기본 모드      ^[\s\S]{12,128}$
 *   정의서 문구 모드 ^(?=[\s\S]*\p{Lu})(?=[\s\S]*\p{Nd})(?=(?:[\s\S]*[!-\/:-@\[-`{-~]){1})
 *                   (?!(?:[\s\S]*[!-\/:-@\[-`{-~]){4})[\s\S]{12,128}$
 *
 * 다만 **정규식만으로는 안전한 비밀번호를 가릴 수 없다.** 아래 셋은 패턴으로 표현이 불가능하다.
 *   ① 유출 목록 대조(HIBP)  ② 계정 정보 포함 여부  ③ 강도 추정
 * 정규식은 형식을, 이 셋은 내용을 본다. 둘 다 통과해야 `ok: true` 다.
 */

import { detectAttackPattern } from './attackPatterns.js'

/* ══════════════════════════════════════════════════════════════════
   정규식 규칙표

   비밀번호 판정을 **선언적인 정규식 한 곳**에 모았다. 이렇게 두면
     · 규칙이 코드 여기저기 흩어지지 않고
     · 서버(Spring `@Pattern`, DB CHECK 제약 등)로 **문자열 그대로 복사**할 수 있고
     · 규칙이 바뀌었을 때 고칠 자리가 한 군데다.

   ⚠️ 정규식으로 **표현할 수 없는** 조건이 있다. 아래 세 가지는 규칙표 밖에서 처리한다.
     ① 유출 목록 대조(HIBP)      — 외부 데이터셋 조회라 패턴으로 못 만든다
     ② 계정 정보 포함 여부        — 이메일·닉네임·이름은 사용자마다 다르다
     ③ 강도 추정                  — 확률·엔트로피 계산이라 정규식의 영역이 아니다
   공격 페이로드 차단(blockAttackPatterns)도 규칙표 밖이다 → components/security/attackPatterns.js
   패턴 하나로는 표현할 수 없고, 인코딩을 벗겨 낸 변형까지 훑어야 하기 때문이다.
   "정규식만으로 안전한 비밀번호를 가릴 수 있다"는 건 사실이 아니다.
   정규식은 **형식**을 보고, 위 셋은 **내용**을 본다.

   ⚠️ ReDoS(정규식 서비스 거부) 주의
   아래 패턴은 전부 **중첩 수량자가 없어** 입력 길이에 선형이다.
   `(a+)+` 같은 형태를 절대 넣지 말 것. 또 길이 상한을 **먼저** 확인해
   과도하게 긴 입력이 다른 패턴에 닿지 않게 한다(checkPassword 참고).
   ══════════════════════════════════════════════════════════════════ */

/**
 * 특수문자: 영문/숫자/공백이 아닌 출력 가능한 ASCII.
 *
 * `[^\p{L}\p{N}\s]` 처럼 유니코드로 넓히지 않았다.
 * 요구사항 정의서가 말하는 "특수문자"는 자판에 있는 기호를 뜻하고,
 * `·` `—` 같은 문장부호나 이모지까지 세면 개수 상한(1~3개) 판정이 사용자 예상과 어긋난다.
 */
const SPECIAL_SOURCE = '[!-\\/:-@\\[-`{-~]'
const SPECIAL_RE = new RegExp(SPECIAL_SOURCE, 'g')

/**
 * 고정 패턴표.
 * 길이·조합처럼 정책값에 따라 달라지는 것은 `buildPasswordRegex()` 가 만든다.
 */
export const PASSWORD_PATTERNS = {
  /** 대문자 — 한글·일본어처럼 대소문자가 없는 문자만 쓰면 절대 만족할 수 없다 */
  upper: /\p{Lu}/u,
  lower: /\p{Ll}/u,
  digit: /\p{Nd}/u,
  special: new RegExp(SPECIAL_SOURCE, 'u'),

  /** 공백만 있는 값 */
  blankOnly: /^\s*$/u,

  /**
   * 제어문자(줄바꿈·탭·NUL 등).
   * 붙여넣기 사고로 섞이면 화면에는 안 보이는데 해시는 달라져 "맞는데 안 되는" 상황이 된다.
   */
  controlChar: /\p{Cc}/u,

  /**
   * **눈에 보이지 않는 문자 전체.**
   *   \p{Cc} 제어문자        NUL · TAB · LF · CR · NEL(U+0085)
   *   \p{Zl}\p{Zp} 줄/문단 구분  U+2028 · U+2029
   *   \p{Cf} 형식 문자        ZWSP(U+200B) · BOM(U+FEFF) · SHY(U+00AD) · RLO(U+202E)
   *
   * 왜 막는가 — 특수문자를 막는 것과는 성격이 완전히 다르다.
   *   · 사용자가 **자기 비밀번호를 볼 수도, 다시 칠 수도 없다.** 다른 기기에서 영영 못 들어간다
   *   · U+202E(RLO)는 표시 순서를 뒤집는다. 화면의 글자와 실제 값이 달라진다
   *   · 웹페이지에서 복사하면 NBSP·ZWSP 가 딸려 오는 일이 흔하다
   *     (NBSP·전각 공백은 NFKC 정규화가 일반 공백으로 바꿔 주므로 문제되지 않는다)
   *
   * **ZWJ(U+200D)는 예외로 허용한다.** 가족 이모지 같은 정상 이모지 시퀀스의 결합자라
   * (예: MAN + ZWJ + WOMAN + ZWJ + GIRL) 막으면 멀쩡한 비밀번호가 거부된다.
   *
   * ⚠️ 이 패턴은 정규식 리터럴로 쓰지 않고 문자열로 조립한다.
   *    리터럴 안에 ZWJ 를 날문자로 두면 **소스에서 보이지 않아** 편집·복사 중에
   *    조용히 사라지거나 늘어난다. (이 파일을 쓰다가 실제로 겪었다)
   */
  invisible: new RegExp('[\\p{Cc}\\p{Zl}\\p{Zp}]|(?!\\u200D)\\p{Cf}', 'u'),

  /** 같은 글자 4번 이상 반복 — aaaa, 1111 */
  repeatedChar: /(.)\1{3,}/u,

  /** 짧은 조각의 단순 반복 — abcabcabc, 121212 (역참조 1회, 선형) */
  repeatedUnit: /^(.{1,4}?)\1+$/u
}

/**
 * 키보드·알파벳·숫자 나열 4연속 (정·역방향).
 * 리터럴만 늘어놓은 교대(alternation)라 백트래킹이 폭발하지 않는다.
 */
const SEQUENCE_SOURCES = ['abcdefghijklmnopqrstuvwxyz', '01234567890', 'qwertyuiop', 'asdfghjkl', 'zxcvbnm']

function buildSequencePattern (windowSize = 4) {
  const parts = new Set()
  for (const seq of SEQUENCE_SOURCES) {
    const rev = [...seq].reverse().join('')
    for (const s of [seq, rev]) {
      for (let i = 0; i + windowSize <= s.length; i++) {
        parts.add(s.slice(i, i + windowSize).replace(/[.*+?^${}()|[\]\\]/g, '\\$&'))
      }
    }
  }
  return new RegExp(`(?:${[...parts].join('|')})`, 'iu')
}

PASSWORD_PATTERNS.sequence = buildSequencePattern()

export const PASSWORD_POLICY = {
  /**
   * 최소 길이 12자.
   *
   * ⚠️ 이 값은 **NIST 권고보다 낮다.** 정직하게 밝힌다.
   *   · NIST SP 800-63B-4 §3.1.1.2 는 단일요소 인증에 **15자를 SHALL** 로 요구한다.
   *   · OWASP ASVS 5.0 6.2.1 은 "at least 8 characters … a minimum of 15 characters is
   *     strongly recommended" 이므로 12자는 **ASVS 는 만족, NIST 는 미달**이다.
   *   · 요구사항 정의서 USER_002 의 "최소 12글자"에 맞춘 값이다(제품 결정).
   *
   * 짧아진 만큼 다른 방어로 메운다:
   *   · 유출 목록 대조(HIBP)를 통과 조건으로 유지 — 길이보다 실제 차단력이 크다
   *   · 강도 추정 하한(minStrength) 유지
   *   · 로그인 시도 제한(components/security/rateLimit.js)으로 온라인 추측 공격 차단
   * MFA 를 도입하면 NIST 기준으로도 8자 이상이면 되므로 이 편차는 해소된다.
   */
  minLength: 12,
  /** 해시 연산 DoS 상한. ASVS 6.2.9(64자 이상 허용)를 만족한다. */
  maxLength: 128,
  /** bcrypt 72바이트 절단 경고 임계. 막지는 않고 알려만 준다. */
  bcryptByteLimit: 72,
  /**
   * 문자 종류 조합을 **강제**한다 (대문자·소문자·숫자·특수문자).
   *
   * ⚠️ 이 설정은 NIST·OWASP 권고를 정면으로 거스른다. 정직하게 밝힌다.
   *   · NIST SP 800-63B-4 §3.1.1.2
   *     "Verifiers and CSPs SHALL NOT impose other composition rules
   *      (e.g., requiring mixtures of different character types) for passwords."
   *     → **SHALL NOT**. 권고가 아니라 금지다.
   *   · OWASP ASVS 5.0 6.2.5
   *     "There must be no requirement for a minimum number of upper or lower case
   *      characters, numbers, or special characters."
   *   · 국내 고시(개인정보의 안전성 확보조치 기준)에도 2023년 개정 이후
   *     문자 종류 조합 조항이 **없다.** 국내법상 근거도 없다.
   *
   * 그런데도 켠 이유: **요구사항 정의서 USER_002 문구**와, 화면에 조건을
   * 명시해 달라는 제품 요구다. 규격을 따르는 쪽을 택했다(제품 결정).
   *
   * 실제로 잃는 것 — 조합 강제는 사용자를 `Password1!` 류의 예측 가능한 패턴으로 몬다.
   * 그래서 이 설정을 켜는 대신 아래 방어를 **그대로 유지**한다.
   *   · HIBP 유출 목록 대조(checkBreach)   ← 실제 차단력은 여기서 나온다
   *   · 강도 추정 하한(minStrength)
   *   · 로그인 시도 제한
   * 로그인 시에는 조합을 검사하지 않는다 — 기존 회원이 잠기면 안 된다(auth.js login 주석).
   *
   * 되돌리려면 이 한 줄만 false. 두 모드 모두 테스트가 있다.
   */
  enforceComposition: true,
  /**
   * specialMax: 3 은 **정의서 문구 그대로**다. 이 상한은 더 강한 비밀번호를 거부한다 —
   * `Correct-Horse-Battery-2026!!!!` 는 특수문자 4개라 불합격이지만 실제로는 훨씬 안전하다.
   * 회귀 테스트(passwordPolicy.spec.js)에 이 모순을 그대로 박아 뒀다.
   * 상한만 풀려면 specialMax 를 Infinity 로 두면 된다.
   */
  composition: { requireUpper: true, requireLower: true, requireDigit: true, specialMin: 1, specialMax: 3 },
  /** 유출 비밀번호 대조 (NIST SHALL / ASVS 6.2.12) */
  checkBreach: true,
  /** 통과에 필요한 최소 강도 점수 (0~4) */
  minStrength: 2,
  /**
   * SQL·NoSQL·LDAP·XSS·JNDI 같은 **공격 페이로드 형태**를 비밀번호로 쓰지 못하게 한다.
   *
   * ⚠️ 이건 보안 강화가 아니다. 정직하게 밝힌다.
   *   · 비밀번호는 해시된 뒤 파라미터 바인딩으로만 전달되므로,
   *     원문에 따옴표가 있든 없든 SQL 인젝션은 발생하지 않는다 (CWE-89).
   *   · OWASP Input Validation Cheat Sheet 는 이런 차단 목록을
   *     "massively flawed approach … trivial for an attacker to bypass" 라고 못 박는다.
   *   · OWASP Authentication Cheat Sheet 는 "Allow usage of all characters" 라고 한다.
   *   → 즉 **OWASP 권고에서 의도적으로 벗어난 설정**이다(제품 결정).
   *
   * 그럼에도 켜 둔 실익은 운영 쪽이다 — WAF·프록시가 페이로드를 담은 요청을 떨궈
   * "가입은 됐는데 로그인이 안 되는" 계정이 생기는 사고를 막고, 로그 오염을 줄인다.
   * 실사용 비용은 낮다(정상 비밀번호 28종 오탐 0 — attackPatterns.spec.js).
   *
   * 끄려면 이 한 줄만 false 로 바꾸면 된다. 두 모드 모두 테스트가 있다.
   */
  blockAttackPatterns: true
}

/**
 * 유니코드 정규화.
 * 한글 입력기·iOS·안드로이드가 같은 글자를 다른 코드포인트로 넘길 수 있다.
 * 정규화하지 않으면 가입 때와 로그인 때 해시가 달라져 "맞는 비밀번호인데 안 되는" 사고가 난다.
 * 서버에서도 **같은 정규화를 해야** 한다.
 */
export function normalizePassword (pw) {
  return String(pw ?? '').normalize('NFKC')
}

export function countSpecials (pw) {
  return (normalizePassword(pw).match(SPECIAL_RE) || []).length
}

export function byteLength (pw) {
  return new TextEncoder().encode(normalizePassword(pw)).length
}

/**
 * 코드포인트 기준 길이.
 * `'👍'.length === 2` 다(UTF-16 서로게이트). 이모지를 넣은 사용자가
 * "12자를 넣었는데 왜 안 되냐"는 상황을 막으려면 코드포인트로 세야 한다.
 * `u` 플래그를 준 정규식의 `{n,m}` 도 코드포인트를 세므로 둘이 일치한다.
 * **서버도 같은 기준으로 세야 한다.**
 */
export function codePointLength (pw) {
  return [...normalizePassword(pw)].length
}

/* ────────────────────── 정책 → 정규식 ────────────────────── */

let cachedRegex = null
let cachedKey = ''

/**
 * 현재 정책을 **정규식 한 줄**로 만든다. 서버로 그대로 옮길 수 있는 형태다.
 *
 * 기본 모드 (enforceComposition: false)
 *   ^[\s\S]{12,128}$
 *
 * 정의서 문구 모드 (enforceComposition: true)
 *   ^(?=[\s\S]*\p{Lu})(?=[\s\S]*\p{Nd})(?![\s\S]*(?:[!-\/:-@\[-`{-~][\s\S]*){4})(?=[\s\S]*[!-\/:-@\[-`{-~])[\s\S]{12,128}$
 *
 * 설계 메모
 *  · `.` 대신 `[\s\S]` 를 쓴다. `.` 는 줄바꿈을 건너뛰어 길이 계산이 어긋난다.
 *  · 각 조건을 **선행 탐색(lookahead)** 으로 분리했다. 조건이 서로 순서에 얽매이지 않고,
 *    하나씩 켜고 끌 수 있으며, 백트래킹이 조건 수에 선형이다.
 *  · 특수문자 **상한**은 부정 선행 탐색으로 표현한다 —
 *    "특수문자가 (상한+1)개 이상 나타나면 실패".
 *
 * @param {object} policy 기본값은 현재 PASSWORD_POLICY
 * @returns {{source:string, flags:string, regex:RegExp}}
 */
export function buildPasswordRegex (policy = PASSWORD_POLICY) {
  const { minLength, maxLength, enforceComposition, composition: c } = policy
  const parts = []

  if (enforceComposition) {
    if (c.requireUpper) parts.push('(?=[\\s\\S]*\\p{Lu})')
    if (c.requireLower) parts.push('(?=[\\s\\S]*\\p{Ll})')
    if (c.requireDigit) parts.push('(?=[\\s\\S]*\\p{Nd})')
    if (c.specialMin > 0) {
      // 최소 n개: 특수문자가 n번 등장
      parts.push(`(?=(?:[\\s\\S]*${SPECIAL_SOURCE}){${c.specialMin}})`)
    }
    if (Number.isFinite(c.specialMax)) {
      // 최대 n개: (n+1)번 등장하면 실패
      parts.push(`(?!(?:[\\s\\S]*${SPECIAL_SOURCE}){${c.specialMax + 1}})`)
    }
  }

  /*
    ⚠️ `$` 를 쓰지 않는다.
    JS 도 Java 도 `$` 는 **문자열 맨 끝의 줄바꿈 앞**에서 매칭된다.
    `^[\s\S]{12,128}$` 은 129자(128자 + 끝 줄바꿈)를 통과시킨다.
    checkPassword 는 길이를 먼저 재서 막지만, 이 정규식은
    서버로 그대로 복사해 쓰라고 만든 것이라 **정규식 자체가** 정확해야 한다.
    JS 에 `\z` 가 없으므로 "뒤에 어떤 문자도 없음" 을 부정 선행 탐색으로 쓴다.
  */
  const source = `^${parts.join('')}[\\s\\S]{${minLength},${maxLength}}(?![\\s\\S])`
  return { source, flags: 'u', regex: new RegExp(source, 'u') }
}

/** 현재 정책의 정규식 (정책값이 바뀌면 자동으로 다시 만든다) */
export function passwordRegex () {
  const p = PASSWORD_POLICY
  const key = [
    p.minLength, p.maxLength, p.enforceComposition,
    p.composition.requireUpper, p.composition.requireDigit,
    p.composition.specialMin, p.composition.specialMax
  ].join('|')
  if (key !== cachedKey) {
    cachedRegex = buildPasswordRegex(p)
    cachedKey = key
  }
  return cachedRegex
}

/* ────────────────────── 로컬 차단 목록 ────────────────────── */

/**
 * 온라인 조회(HIBP)가 실패하거나 꺼져 있을 때를 위한 최소 방어선.
 * 전수 목록이 아니다 — 실제 차단력은 breachCheck.js 의 HIBP 조회에서 나온다.
 * 한국어권에서 흔한 조합(키보드 나열, 자판 배열, 서비스명)을 함께 넣었다.
 */
const COMMON_PASSWORDS = new Set([
  'password', 'password1', 'password123', 'passw0rd', 'p@ssw0rd', 'p@ssword',
  '123456', '1234567', '12345678', '123456789', '1234567890', '12345678910',
  'qwerty', 'qwerty123', 'qwertyuiop', 'asdfghjkl', 'zxcvbnm', 'qazwsx', 'qwer1234',
  '1q2w3e4r', '1q2w3e4r5t', '1qaz2wsx', '1q2w3e4r!', 'q1w2e3r4', 'a1s2d3f4',
  'iloveyou', 'admin', 'administrator', 'root', 'toor', 'letmein', 'welcome',
  'monkey', 'dragon', 'sunshine', 'princess', 'football', 'baseball', 'master',
  'abc123', 'abcd1234', 'aaaaaa', 'aaaaaaaa', '111111', '000000', '666666', '888888',
  'test1234', 'test123', 'temp1234', 'changeme', 'secret', 'default', 'guest',
  'samsung', 'korea', 'seoul', 'jeju', 'hangat', 'hangatjido', 'hangat1234',
  'daehanminguk', 'sarang', 'saranghae', 'wkdrjs', 'dkssud', 'ehdgkrhk'
])

/** 서비스 문맥 단어 — NIST 가 블록리스트에 넣으라고 지목한 부류 */
const CONTEXT_WORDS = ['hangat', 'hangatjido', '한갓', '한갓지도', 'jeju', '제주', 'jejudo']

/** 부분 문자열 검사용 — 6자 이상만 본다(짧은 단어는 오탐이 너무 많다) */
const COMMON_SUBSTRINGS = [...COMMON_PASSWORDS].filter(w => w.length >= 6)

/**
 * 흔한 비밀번호가 **본체**로 들어간 경우를 찾는다.
 * `password123456789` 처럼 흔한 단어에 숫자만 덧댄 형태를 잡기 위한 것.
 * 전체 길이의 40% 이상을 차지할 때만 차단으로 본다.
 * 그보다 짧게 섞인 경우(예: snapdragon-lantern-2026)는 차단하지 않고 강도만 깎는다.
 * @returns {{blocking:boolean, word:string|null}}
 */
function commonWordHit (lower) {
  let best = null
  for (const w of COMMON_SUBSTRINGS) {
    if (lower.includes(w) && (!best || w.length > best.length)) best = w
  }
  if (!best) return { blocking: false, word: null }
  return { blocking: best.length / lower.length >= 0.4, word: best }
}

/* ────────────────────── 강도 추정 ────────────────────── */

/** 키보드·알파벳·숫자 나열 포함 여부 (정규식표 사용) */
function hasRun (value) {
  return PASSWORD_PATTERNS.sequence.test(value)
}

function poolSize (pw) {
  let n = 0
  if (PASSWORD_PATTERNS.lower.test(pw)) n += 26
  if (PASSWORD_PATTERNS.upper.test(pw)) n += 26
  if (PASSWORD_PATTERNS.digit.test(pw)) n += 10
  if (PASSWORD_PATTERNS.special.test(pw)) n += 32
  if (/\s/u.test(pw)) n += 1
  if (/[^\x00-\x7F]/u.test(pw)) n += 128 // 한글 등 — 보수적으로 낮게 잡는다
  return Math.max(n, 1)
}

/**
 * 강도 추정 (0~4).
 *
 * ⚠️ zxcvbn 같은 사전 기반 추정기가 아니다. 의존성 없이 돌리려고
 *    문자 풀 엔트로피에서 반복·나열·문맥어 패턴만 감점하는 방식으로 만들었다.
 *    실제 크래킹 난이도와는 다를 수 있다. 최종 방어선은 HIBP 유출 조회다.
 */
export function estimateStrength (raw) {
  const pw = normalizePassword(raw)
  if (!pw) return { score: 0, bits: 0, notes: [] }

  const lower = pw.toLowerCase()
  const notes = []
  const len = codePointLength(pw)

  let bits = Math.log2(poolSize(pw)) * len

  // 같은 글자 반복 (aaaa, 1111) — Set 도 코드포인트 단위라 기준이 맞는다
  const uniqueRatio = new Set(pw).size / len
  if (uniqueRatio < 0.5) {
    bits *= 0.55
    notes.push('같은 글자가 많이 반복돼요')
  }

  // 키보드·알파벳 나열
  if (hasRun(lower)) {
    bits *= 0.6
    notes.push('연속된 키보드 나열이 들어 있어요')
  }

  // 짧은 조각의 단순 반복 (abcabcabc)
  if (PASSWORD_PATTERNS.repeatedUnit.test(pw)) {
    bits *= 0.4
    notes.push('같은 조각이 반복돼요')
  }

  // 서비스명 포함
  if (CONTEXT_WORDS.some(w => lower.includes(w))) {
    bits *= 0.5
    notes.push('서비스 이름이 들어 있어요')
  }

  // 흔한 비밀번호가 일부로 섞인 경우
  if (commonWordHit(lower).word) {
    bits *= 0.5
    notes.push('많이 쓰이는 단어가 들어 있어요')
  }

  // 끝에 숫자만 붙인 패턴 (password2026)
  if (/^[a-zA-Z가-힣]+[0-9!@#$]{1,4}$/.test(pw)) {
    bits *= 0.6
    notes.push('단어 뒤에 숫자·기호만 붙인 형태예요')
  }

  const score = bits < 28 ? 0 : bits < 45 ? 1 : bits < 60 ? 2 : bits < 80 ? 3 : 4
  return { score, bits: Math.round(bits), notes }
}

export const STRENGTH_LABEL = ['매우 약함', '약함', '보통', '강함', '매우 강함']

export const PASSWORD_CHARACTER_MESSAGE = '영문, 숫자, 키보드 특수문자만 사용할 수 있어요 (공백 제외)'

/** 새 비밀번호 원문 검사. 전각 문자 등을 영문으로 바꿔서 통과시키지 않는다. */
export function hasAllowedPasswordCharacters (raw) {
  return /^[\x21-\x7E]+$/.test(String(raw ?? ''))
}

/** 가입·재설정에만 적용해 기존 비밀번호로 로그인하는 동작은 바꾸지 않는다. */
export function checkNewPassword (raw, context = {}) {
  const result = checkPassword(raw, context)
  if (!raw || hasAllowedPasswordCharacters(raw)) return result
  return { ...result, ok: false, matchedRegex: false, errors: [PASSWORD_CHARACTER_MESSAGE, ...result.errors] }
}

/* ────────────────────── 검사 ────────────────────── */

/**
 * 로컬(네트워크 없이) 검사.
 * @param {string} raw
 * @param {{email?:string, nickname?:string, name?:string}} context 계정 정보 포함 여부 확인용
 */
export function checkPassword (raw, context = {}) {
  const pw = normalizePassword(raw)
  const lower = pw.toLowerCase()
  const p = PASSWORD_POLICY
  const errors = []
  const warnings = []

  if (!pw || PASSWORD_PATTERNS.blankOnly.test(pw)) {
    return {
      ok: false,
      errors: [pw ? '공백만으로는 만들 수 없어요' : '비밀번호를 입력해 주세요'],
      warnings: [],
      strength: estimateStrength(''),
      matchedRegex: false
    }
  }

  const len = codePointLength(pw)

  /*
    ⚠️ 길이 상한을 **가장 먼저** 본다.
    다른 패턴을 돌리기 전에 과도하게 긴 입력을 잘라내야
    정규식 처리 비용으로 서비스를 밀어내는 공격(ReDoS 계열)을 막을 수 있다.
  */
  if (len > p.maxLength) {
    return {
      ok: false,
      errors: [`${p.maxLength}자를 넘을 수 없어요`],
      warnings: [],
      strength: { score: 0, bits: 0, notes: [] },
      matchedRegex: false
    }
  }

  /* ── 형식 판정: 정책 정규식 한 방 ── */
  const { regex } = passwordRegex()
  const matchedRegex = regex.test(pw)

  // 통과/실패만으로는 왜 막혔는지 알 수 없으므로, 실패했을 때만 세부 패턴으로 이유를 찾는다
  if (!matchedRegex) {
    if (len < p.minLength) {
      errors.push(`${p.minLength}자 이상이어야 해요 (지금 ${len}자)`)
    }
    if (p.enforceComposition) {
      const c = p.composition
      const specials = countSpecials(pw)
      if (c.requireUpper && !PASSWORD_PATTERNS.upper.test(pw)) errors.push('대문자를 넣어주세요')
      if (c.requireLower && !PASSWORD_PATTERNS.lower.test(pw)) errors.push('소문자를 넣어주세요')
      if (c.requireDigit && !PASSWORD_PATTERNS.digit.test(pw)) errors.push('숫자를 넣어주세요')
      if (specials < c.specialMin) errors.push(`특수문자를 ${c.specialMin}개 이상 넣어주세요`)
      if (specials > c.specialMax) errors.push(`특수문자는 ${c.specialMax}개까지만 쓸 수 있어요`)
    }
    // 어느 패턴도 짚어내지 못했다면(있어선 안 되지만) 일반 문구로 막는다
    if (!errors.length) errors.push('비밀번호 형식을 확인해 주세요')
  }

  if (PASSWORD_PATTERNS.invisible.test(pw)) {
    errors.push('보이지 않는 문자(줄바꿈·탭·너비 없는 공백 등)는 넣을 수 없어요')
  }

  /*
    공격 페이로드 형태 차단.
    NFKC 정규화된 값(pw)을 넘긴다 — 전각 따옴표(＇)가 정규화로 `'` 가 되므로
    전각으로 우회하는 경로를 한 겹 줄인다.
    ⚠️ 개별 문자를 막는 게 아니다. `'` `"` `;` 하나만 든 비밀번호는 그대로 통과한다.
  */
  if (p.blockAttackPatterns) {
    const attack = detectAttackPattern(pw)
    if (attack.hit) {
      errors.push(`공격 문자열로 자주 쓰이는 형태(${attack.label})가 들어 있어요. 다른 비밀번호를 써주세요`)
    }
  }

  if (byteLength(pw) > p.bcryptByteLimit) {
    // 막지는 않는다. 서버 해시 방식에 따라 뒷부분이 무시될 수 있다는 사실만 알린다.
    warnings.push('아주 긴 비밀번호예요. 서버 해시 방식에 따라 앞부분만 쓰일 수 있어요')
  }

  /* ── 내용 판정: 정규식으로 표현할 수 없는 조건들 ── */

  if (COMMON_PASSWORDS.has(lower)) {
    errors.push('너무 흔한 비밀번호예요')
  } else {
    const hit = commonWordHit(lower)
    if (hit.blocking) {
      errors.push(`'${hit.word}' 처럼 많이 쓰이는 단어가 대부분이에요. 다른 말로 바꿔주세요`)
    }
  }

  // 계정 정보가 그대로 들어간 경우 (NIST 가 지목한 context-specific words)
  const local = String(context.email || '').split('@')[0].toLowerCase()
  const personal = [local, context.nickname, context.name]
    .map(v => String(v || '').toLowerCase())
    .filter(v => v.length >= 3)
  if (personal.some(v => lower.includes(v))) {
    errors.push('이메일·닉네임·이름이 그대로 들어 있어요')
  }

  const strength = estimateStrength(pw)
  if (strength.score < p.minStrength && !errors.length) {
    errors.push(strength.notes[0] || '더 길거나 예측하기 어려운 비밀번호로 바꿔주세요')
  }

  return { ok: errors.length === 0, errors, warnings, strength, matchedRegex }
}

/**
 * 화면에 띄울 안내 항목.
 * **통과/실패를 가르는 조건**과 **권장 사항**을 시각적으로 구분하기 위해 required 를 붙인다.
 * 조합 규칙은 강제 모드가 아니면 required:false (참고용) 다.
 */
export function passwordChecklist (raw) {
  const pw = normalizePassword(raw)
  const p = PASSWORD_POLICY
  const specials = countSpecials(pw)
  const items = [
    { key: 'length', required: true, label: `${p.minLength}자 이상`, pass: codePointLength(pw) >= p.minLength },
    { key: 'strength', required: true, label: '흔하지 않은 조합', pass: estimateStrength(pw).score >= p.minStrength }
  ]
  if (p.enforceComposition) {
    const c = p.composition
    // 화면에 "무엇이 필요한지" 를 개수까지 그대로 보여 준다
    if (c.requireUpper) items.push({ key: 'upper', required: true, label: '대문자 1개 이상', pass: PASSWORD_PATTERNS.upper.test(pw) })
    if (c.requireLower) items.push({ key: 'lower', required: true, label: '소문자 1개 이상', pass: PASSWORD_PATTERNS.lower.test(pw) })
    if (c.requireDigit) items.push({ key: 'digit', required: true, label: '숫자 1개 이상', pass: PASSWORD_PATTERNS.digit.test(pw) })
    items.push({
      key: 'special',
      required: true,
      label: Number.isFinite(c.specialMax)
        ? `특수문자 ${c.specialMin}~${c.specialMax}개`
        : `특수문자 ${c.specialMin}개 이상`,
      pass: specials >= c.specialMin && specials <= c.specialMax
    })
  } else {
    items.push({
      key: 'variety',
      required: false,
      label: '문장처럼 길게 쓰면 더 안전해요',
      pass: codePointLength(pw) >= 20 || /\s/u.test(pw)
    })
  }
  return items
}

/**
 * 시스템이 만드는 비밀번호 (임시 비밀번호·초기 비밀번호).
 * ASVS 6.4.1: "securely randomly generated, follow the existing password policy,
 * and expire after a short period of time or after they are initially used."
 * → CSPRNG 사용 + 현재 정책 통과 + 호출 측에서 만료·1회성 처리.
 */
export function generateCompliantSecret () {
  const alphabet = 'ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnopqrstuvwxyz23456789-_'
  const length = Math.max(PASSWORD_POLICY.minLength + 5, 20)
  const c = globalThis.crypto
  if (!c?.getRandomValues) {
    throw new Error('CSPRNG(crypto.getRandomValues)를 쓸 수 없어 비밀번호를 만들 수 없습니다')
  }
  // 모듈로 편향 제거: 알파벳 길이의 배수를 넘는 바이트는 버린다(rejection sampling)
  const limit = 256 - (256 % alphabet.length)

  const draw = n => {
    let out = ''
    while (out.length < n) {
      const bytes = new Uint8Array(n * 2)
      c.getRandomValues(bytes)
      for (const b of bytes) {
        if (b >= limit) continue // 버린 바이트는 그냥 다시 뽑는다
        out += alphabet[b % alphabet.length]
        if (out.length === n) break
      }
    }
    return out
  }

  // 정책(특히 강제 모드의 조합 조건)을 만족할 때까지 다시 뽑는다
  for (let attempt = 0; attempt < 200; attempt++) {
    const candidate = draw(length)
    if (checkPassword(candidate).ok) return candidate
  }
  throw new Error('정책을 만족하는 임시 비밀번호를 만들지 못했습니다')
}
