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
import ThemeToggle from '../layout/ThemeToggle.vue'
import { nextHeroIndex } from '../../utils/heroCarousel.js'

const props = defineProps({
  title: { type: String, required: true },
  lead: { type: String, default: '' },
  heroImages: { type: Array, default: () => [] }
})

const heroIndex = ref(0)
const hasHeroCarousel = computed(() => props.heroImages.length > 0)
let heroTimer

onMounted(() => {
  if (props.heroImages.length < 2) return
  heroTimer = window.setInterval(() => {
    heroIndex.value = nextHeroIndex(heroIndex.value, props.heroImages.length)
  }, 5000)
})

onBeforeUnmount(() => window.clearInterval(heroTimer))
</script>

<template>
  <main class="auth" :class="{ 'has-carousel': hasHeroCarousel }">
    <!-- 왼쪽: 사진 캔버스 -->
    <aside class="hero" :class="{ carousel: hasHeroCarousel }" aria-hidden="true">
      <Transition v-if="hasHeroCarousel" name="hero-slide">
        <img
          :key="heroIndex"
          class="hero-photo"
          :src="heroImages[heroIndex]"
          alt=""
        >
      </Transition>
      <div class="veil" />
      <div class="hero-copy">
        <h2>Discover the<br>unseen paths.</h2>
        <p>붐비는 시간을 비껴가는 코스로, 제주를 한갓지게 걷습니다.</p>
      </div>
    </aside>

    <!-- 오른쪽: 폼 캔버스 -->
    <section class="panel">
      <div class="ptop">
        <ThemeToggle variant="icon" />
      </div>

      <div class="form">
        <RouterLink to="/home" class="brand">
          <img class="brand-mk" src="/hangat-mark.png" alt="" width="166" height="144">
          <span>한<em>갓</em>지도</span>
        </RouterLink>

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
}
.hero-slide-enter-active,
.hero-slide-leave-active {
  transition: transform .9s cubic-bezier(.76, 0, .24, 1);
}
.hero-slide-enter-active { z-index: 1; }
.hero-slide-leave-active { z-index: 0; }
.hero-slide-enter-from { transform: translateY(100%); }
.hero-slide-leave-to { transform: translateY(-100%); }

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
  max-width: 30ch;
  text-shadow: 0 1px 8px rgba(0, 0, 0, .4);
}

/* ── 오른쪽: 폼 ── */
.panel {
  background: var(--bg);
  padding: var(--sp-md) var(--sp-xl) var(--sp-xl);
  display: flex; flex-direction: column;
  overflow-y: auto;
}
.ptop { display: flex; justify-content: flex-end; }

.form { width: 100%; max-width: 420px; margin: auto; padding: var(--sp-lg) 0; }

.brand {
  display: flex; align-items: center; gap: var(--sp-sm);
  font-family: var(--font-head);
  font-size: 22px; font-weight: 900; letter-spacing: -.03em;
  color: var(--ac);
  margin-bottom: var(--sp-xl);
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
  .hero-slide-enter-active, .hero-slide-leave-active { transition: none; }
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
  .brand { margin-bottom: var(--sp-lg); }
}
</style>
