import { createApp } from 'vue'
import { createPinia } from 'pinia'
import App from './App.vue'
import router from './router/index.js'

/*
  아래 세 파일만 앱 전체에 적용한다.
    fonts  → 서체 선언
    tokens → 색·간격·모양 (메인·AI코스 팔레트가 원천, 회원·마이페이지 토큰은 별칭)
    base   → 리셋 + 공통 컴포넌트 + 헤더(.nav 계열). **헤더의 정본**

  콘텐츠 화면 규칙(styles.css)과 지도 규칙(styles/hangat.css)은 각각
  DefaultLayout과 MapLayout에서 자기 범위 안에만 불러온다.

  ⚠️ assets/home.css 는 **일부러 불러오지 않는다.**
     정동현님 전달본에 "원본과 동일 (참조용)" 으로 들어 있던 파일인데,
     정의된 25개 선택자(.hero-map .hero-weather .weather-week .today-recommendation …)를
     쓰는 화면이 지금은 하나도 없다. 옛 메인 화면용이다.
     게다가 .hero 를 다시 정의해서 현재 메인 화면의 히어로를 덮어쓴다.
     파일은 지우지 않고 남겨 뒀다 — 옛 메인을 되살릴 일이 있으면 여기서 import 하면 된다.
*/
import './assets/styles/fonts.css'
import './assets/styles/tokens.css'
import './assets/styles/base.css'

const app = createApp(App)
app.use(createPinia())
app.use(router)
app.mount('#app')
