<script setup>
/**
 * 회원 화면 공통 레이아웃.
 *
 * Stitch 시안(로그인·회원가입·비밀번호찾기 3벌 공통)의 구성을 그대로 옮겼다.
 *   왼쪽  5/12 — 제주 항공사진 + 위로 갈수록 옅어지는 검은 베일 + 하단 카피
 *                `hidden lg:block` 이라 좁은 화면에서는 **완전히 숨긴다**
 *   오른쪽 7/12 — 브랜드 앵커 → 제목/리드 → 폼 → 푸터, `max-w-md` 안에 담긴다
 *
 * ⚠️ 이전 판에서는 왼쪽에 "오늘 한산한 곳" 실데이터를 얹었었다.
 *    시안이 그 자리를 사진 하나로 쓰기 때문에 **뺐다.** 데이터 자체가 사라진 건 아니고
 *    홈·지도 화면에 그대로 있다. 시안을 따르기로 한 결정의 대가로 적어 둔다.
 *
 * ⚠️ 사진은 `public/images/hero-jeju.jpg` 로 **번들**한다.
 *    시안 HTML 은 googleusercontent 를 직접 물고 있는데, 이 앱 CSP 의
 *    `img-src 'self'` 로는 외부 이미지가 차단된다(vite.config.js).
 */
import { computed, onBeforeUnmount, onMounted, ref } from 'vue'
import { nextHeroIndex } from '../../utils/heroCarousel.js'

const props = defineProps({
  title: { type: String, required: true },
  lead: { type: String, default: '' },
  heroImages: { type: Array, default: () => [] },
  backTo: { type: String, default: '' },
  backLabel: { type: String, default: '뒤로가기' }
})

const heroIndex = ref(0)
const previousHeroIndex = ref(-1)
const readyImages = ref(new Set())
const hasHeroCarousel = computed(() => props.heroImages.length > 0)
let heroTimer
let disposed = false

async function prepareImage (event, index) {
  const image = event.target
  try {
    // 다운로드뿐 아니라 브라우저가 그릴 준비까지 끝나야 전환 대상에 넣는다.
    await image.decode()
    if (disposed || !image.naturalWidth) return
    readyImages.value.add(index)
    if (!readyImages.value.has(heroIndex.value)) heroIndex.value = index
  } catch {
    // 실패한 사진은 건너뛰고 현재 사진을 계속 보여준다.
  }
}

onMounted(() => {
  if (props.heroImages.length < 2) return
  heroTimer = window.setInterval(() => {
    const next = nextHeroIndex(heroIndex.value, props.heroImages.length, readyImages.value)
    if (next === heroIndex.value) return
    previousHeroIndex.value = heroIndex.value
    heroIndex.value = next
  }, 5000)
})

onBeforeUnmount(() => {
  disposed = true
  window.clearInterval(heroTimer)
})
</script>

<template>
  <main class="auth" :class="{ 'has-carousel': hasHeroCarousel }">
    <!-- 왼쪽: 사진 캔버스 -->
    <aside class="hero" :class="{ carousel: hasHeroCarousel }">
      <template v-if="hasHeroCarousel">
        <img
          v-for="(src, index) in heroImages"
          :key="src"
          class="hero-photo"
          :class="{
            'is-current': index === heroIndex && readyImages.has(index),
            'is-previous': index === previousHeroIndex
          }"
          :src="src"
          :fetchpriority="index === 0 ? 'high' : 'low'"
          decoding="async"
          alt=""
          @load="prepareImage($event, index)"
        >
      </template>
      <div class="veil" />
      <div class="hero-copy">
        <h2>Discover the<br>unseen paths.</h2>
        <p>붐비는 시간을 비껴가는 코스로, 제주를 <span class="keep-together">한갓지게 걷습니다.</span></p>
        <span v-if="hasHeroCarousel" class="photo-credit">출처 : Unsplash</span>
      </div>
    </aside>

    <!-- 오른쪽: 폼 캔버스 -->
    <section class="panel">
      <div class="form">
        <div class="brand-row">
          <RouterLink v-if="backTo" :to="backTo" class="back-link" :aria-label="backLabel" :title="backLabel">
            <svg width="24" height="24" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true">
              <path d="m15 5-7 7 7 7" />
            </svg>
          </RouterLink>
          <RouterLink to="/home" class="brand">
            <img class="brand-mk" src="/hangat-mark.png" alt="" width="166" height="144">
            <span>한<em>갓</em>지도</span>
          </RouterLink>
        </div>

        <header class="head">
          <h1>{{ title }}</h1>
          <p v-if="lead" class="lead">{{ lead }}</p>
        </header>

        <slot />

        <footer class="pfoot">
          <slot name="footer" />
        </footer>
      </div>
    </section>
  </main>
</template>

<style scoped>
.auth {
  display: grid;
  grid-template-columns: 5fr 7fr;   /* 시안 lg:w-5/12 · lg:w-7/12 */
  height: 100%;
  min-height: 0;
}
.auth.has-carousel { grid-template-columns: 3fr 2fr; }

/* ── 왼쪽: 사진 ── */
.hero {
  position: relative;
  background-image: url('/images/hero-jeju.jpg');
  background-size: cover;
  background-position: center;
  background-color: var(--surf3);   /* 이미지가 뜨기 전 바탕 */
}
.hero.carousel { overflow: hidden; background-image: none; }
.hero-photo {
  position: absolute; inset: 0;
  width: 100%; height: 100%;
  object-fit: cover;
  opacity: 0;
  transition: opacity .9s ease;
}
/* 기존 사진을 불투명한 바탕으로 남겨 전환 중에도 회색 배경이 비치지 않는다. */
.hero-photo.is-previous { opacity: 1; z-index: 0; transition: none; }
.hero-photo.is-current { opacity: 1; z-index: 1; }

.veil { position: absolute; inset: 0; z-index: 2; background: var(--hero-veil); pointer-events: none; }

.hero-copy {
  position: absolute;
  z-index: 3;
  left: var(--sp-xl); right: var(--sp-xl); bottom: var(--sp-xl);
  color: #fff;
}
.hero-copy h2 {
  font-family: var(--font-head);
  font-size: 30px; font-weight: 800; line-height: 1.13; letter-spacing: -.02em;
  margin-bottom: var(--sp-sm);
  text-shadow: 0 2px 12px rgba(0, 0, 0, .45);
}
.hero-copy p {
  font-size: 15px; line-height: 1.75;
  color: rgba(255, 255, 255, .9);
  word-break: keep-all;
  text-shadow: 0 1px 8px rgba(0, 0, 0, .4);
}
.keep-together { white-space: nowrap; }
.photo-credit {
  display: inline-block; margin-top: 4px;
  font-size: 12px; line-height: 1.75; color: rgba(255, 255, 255, .9);
  text-shadow: 0 1px 8px rgba(0, 0, 0, .4);
}

/* ── 오른쪽: 폼 ── */
.panel {
  background: var(--bg);
  padding: var(--sp-md) var(--sp-xl) var(--sp-xl);
  display: flex; flex-direction: column;
  overflow-y: auto;
}

.form { width: 100%; max-width: 420px; margin: auto; padding: var(--sp-lg) 0; }

.brand-row { display: flex; align-items: center; gap: 8px; margin-bottom: var(--sp-xl); }
.back-link {
  display: inline-flex; align-items: center; justify-content: center;
  width: 44px; height: 44px; flex-shrink: 0;
  /* SVG 내부 여백까지 보정해 화살표의 왼쪽 선을 본문에 맞춘다. 클릭 영역은 44px 유지. */
  margin-left: -17px;
  color: var(--ac); border-radius: var(--rp);
}
.back-link:hover { background: var(--ac-bg); }
.back-link:focus-visible { outline: 2px solid currentColor; outline-offset: 3px; }
.brand {
  display: flex; align-items: center; gap: var(--sp-sm);
  font-family: var(--font-head);
  font-size: 22px; font-weight: 900; letter-spacing: -.03em;
  color: var(--ac);
}
.brand em { font-style: normal; }

.head { margin-bottom: var(--sp-lg); }
h1 { font-size: 30px; line-height: 1.13; letter-spacing: -.02em; margin-bottom: var(--sp-xs); }
.lead { font-size: 15px; color: var(--tx2); line-height: 1.6; }

.pfoot { padding-top: var(--sp-lg); }

/* 시안의 fade-in — 진입 시 살짝 올라오며 나타난다 */
.form, .hero-copy { animation: rise .5s ease-out both; }
.hero-copy { animation-delay: .12s; }
@keyframes rise {
  from { opacity: 0; transform: translateY(10px); }
  to   { opacity: 1; transform: none; }
}
@media (prefers-reduced-motion: reduce) {
  .form, .hero-copy { animation: none; }
  .hero-photo { transition: none; }
}

/* 시안은 lg 미만에서 사진을 통째로 숨긴다 */
@media (max-width: 1023px) {
  .auth, .auth.has-carousel { grid-template-columns: 1fr; }
  .hero { display: none; }
  .panel {
    padding: var(--sp-md) var(--margin-mobile)
      calc(var(--sp-xl) + var(--mobile-tabbar-h));
  }
  .form { padding: var(--sp-sm) 0 var(--sp-lg); }
  h1 { font-size: 26px; }
  .brand-row { margin-bottom: var(--sp-lg); }
}
</style>
