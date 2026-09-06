<script setup>
/**
 * 레이아웃 분기만 한다.
 *
 * 통합 전에는 여기서 헤더·세션배너·탭바·토스트를 직접 그렸다.
 * 지도(풀블리드)와 회원 화면(껍데기 없음)이 들어오면서 뼈대가 3종이 됐고,
 * 화면별 구성은 components/layout/으로 옮겼다.
 *
 * MobileTabBar 와 ToastHost 는 레이아웃과 무관하게 항상 유지돼야 해서 여기 둔다.
 */
import { computed, watch } from 'vue'
import { useRoute } from 'vue-router'
import DefaultLayout from './components/layout/DefaultLayout.vue'
import BareLayout from './components/layout/BareLayout.vue'
import MapLayout from './components/layout/MapLayout.vue'
import MobileTabBar from './components/layout/MobileTabBar.vue'
import ToastHost from './components/common/ToastHost.vue'

const route = useRoute()

const LAYOUTS = { default: DefaultLayout, bare: BareLayout, map: MapLayout }

const layout = computed(() => LAYOUTS[route.meta?.layout] || DefaultLayout)

/** DefaultLayout 만 쓰는 옵션. 다른 레이아웃에 넘어가도 무시된다 */
const layoutProps = computed(() => ({
  compactHeader: !!route.meta?.compactHeader,
  contentStyles: route.meta?.styleScope === 'content'
}))

/*
  화면별 스킨.

  정동현님 화면(메인·관광지 상세·저장한 코스)은 Toss풍 리스킨을 쓴다.
  그 규칙이 assets/styles.css 하단에 있는데, 원래는 전역이라
  AI코스 화면의 버튼·입력·패널 모양까지 바꿔 버렸다.
  이제 html.skin-toss 안에서만 걸리므로 여기서 클래스를 붙였다 뗀다.

  <html> 에 붙이는 이유: 토큰(tokens.css) 재정의도 같은 클래스로 하기 때문이다.
  data-theme 과 같은 자리라 테마 토글과 충돌하지 않는다.
*/
watch(() => route.meta?.skin, skin => {
  document.documentElement.classList.toggle('skin-toss', skin === 'toss')
}, { immediate: true })
</script>

<template>
  <component :is="layout" v-bind="layoutProps">
    <RouterView v-slot="{ Component }">
      <component :is="Component" />
    </RouterView>
  </component>

  <MobileTabBar />
  <ToastHost />
</template>
