<script setup>
/**
 * 상단 헤더 없는 껍데기 — 로그인 · 회원가입 · 비밀번호 재설정.
 *
 * 이 화면들은 components/auth/AuthLayout.vue 라는 **자체 2단 구성**을 안에서 쓰고
 * 거기에 브랜드가 이미 들어 있다. 위에 헤더를 또 얹으면 브랜드가 두 번 나온다.
 * 모바일 하단 메뉴는 레이아웃 밖의 App.vue 가 계속 표시한다.
 *
 * ⚠️ 이름이 비슷한 components/auth/AuthLayout.vue 와 혼동하지 말 것.
 *    그쪽은 title/lead/heroImages props 를 받는 화면 내부 컴포넌트다.
 */

/* App.vue 가 세 레이아웃에 같은 props(footer, compactHeader)를 넘긴다.
   여기는 둘 다 안 쓰므로 상속을 끈다 — 켜 두면 Vue 가 경고를 낸다. */
defineOptions({ inheritAttrs: false })
</script>

<template>
  <slot />
</template>
