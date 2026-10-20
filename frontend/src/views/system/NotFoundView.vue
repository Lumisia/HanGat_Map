<script setup>
import UnavailableCard from '../../components/common/UnavailableCard.vue'
</script>
<template>
  <main class="doc">
    <UnavailableCard title="찾는 화면이 없어요" description="주소가 바뀌었거나 지워진 화면일 수 있어요.">
      <RouterLink class="btn2 primary" to="/mypage">마이페이지로</RouterLink>
      <RouterLink class="btn2" to="/login">로그인 화면으로</RouterLink>
    </UnavailableCard>
  </main>
</template>
