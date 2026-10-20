<script setup>
/**
 * 인라인 SVG 아이콘.
 *
 * ⚠️ 왜 아이콘 폰트를 쓰지 않는가
 *    Stitch 시안은 Google 의 Material Symbols 를 CDN 에서 불러온다.
 *    이 앱의 CSP 는 `font-src 'self'` 라(vite.config.js) 외부 아이콘 폰트가 차단된다.
 *    폰트를 번들하면 파일 하나가 수백 KB인데 실제로 쓰는 아이콘은 십수 개뿐이라,
 *    필요한 것만 path 로 들고 있는 편이 훨씬 가볍고 CSP 도 만족한다.
 *
 *    모양은 Material Symbols(Outlined, weight 400) 를 눈으로 맞춘 것이지 동일 자산이 아니다.
 *    24x24 좌표계·1.8 스트로크로 통일했다.
 */
defineProps({
  name: { type: String, required: true },
  size: { type: [Number, String], default: 20 },
  /** true 면 채운 아이콘 (시안의 `font-variation-settings: 'FILL' 1`) */
  fill: { type: Boolean, default: false }
})

// 선(stroke) 기반 아이콘 — d 문자열만 담는다
const STROKE = {
  calendar: 'M5 5h14a2 2 0 0 1 2 2v13H3V7a2 2 0 0 1 2-2ZM7 3v4m10-4v4M3 10h18M7 14h2m4 0h2m-8 3h2',
  car: 'M4 11l2-6h12l2 6M3 11h18v8H3ZM6 19v2m12-2v2M6 14h2m8 0h2',
  bus: 'M5 3h14v16H5ZM5 11h14M8 19v2m8-2v2M8 15h1m6 0h1M12 3v8',
  walk: 'M14 5a2 2 0 1 0 0-4 2 2 0 0 0 0 4ZM10 13l2-6 4 5 4 1M12 7l-5 3-2 4M11 13l5 3 1 6M11 13l-3 9',
  coffee: 'M4 4h12v10a5 5 0 0 1-10 0V4ZM16 6h2a3 3 0 0 1 0 6h-2M3 21h17',
  pin: 'M19 10c0 5-7 11-7 11S5 15 5 10a7 7 0 0 1 14 0ZM15 10a3 3 0 1 1-6 0 3 3 0 0 1 6 0',
  mountain: 'M2 20 9 5l5 9 3-5 5 11ZM6 11l3 2 2-3',
  waves: 'M2 6q3-4 6 0t6 0 6 0M2 12q3-4 6 0t6 0 6 0M2 18q3-4 6 0t6 0 6 0',
  sun: 'M12 2v2m0 16v2M2 12h2m16 0h2M5 5l2 2m10 10 2 2M5 19l2-2M17 7l2-2M16 12a4 4 0 1 1-8 0 4 4 0 0 1 8 0',
  bell: 'M6.5 10a5.5 5.5 0 0 1 11 0c0 3.2.7 4.7 1.4 5.6.4.5 0 1.2-.6 1.2H5.7c-.6 0-1-.7-.6-1.2.7-.9 1.4-2.4 1.4-5.6ZM10 20a2.2 2.2 0 0 0 4 0',
  close: 'M6 6l12 12M18 6 6 18',
  copy: 'M8 8h12v13H8zM16 8V3H3v13h5',
  share: 'M8 12l8-5M8 12l8 5M6 15a3 3 0 1 0 0-6 3 3 0 0 0 0 6zM18 8a3 3 0 1 0 0-6 3 3 0 0 0 0 6zM18 22a3 3 0 1 0 0-6 3 3 0 0 0 0 6z',
  trash: 'M4 7h16M9 7V4h6v3M6 7l1 14h10l1-14M10 10v7M14 10v7',
  mail: 'M3 7.5A2.5 2.5 0 0 1 5.5 5h13A2.5 2.5 0 0 1 21 7.5v9a2.5 2.5 0 0 1-2.5 2.5h-13A2.5 2.5 0 0 1 3 16.5zM3.5 7l8.5 6 8.5-6',
  lock: 'M6 10.5h12a1.5 1.5 0 0 1 1.5 1.5v7A1.5 1.5 0 0 1 18 20.5H6A1.5 1.5 0 0 1 4.5 19v-7A1.5 1.5 0 0 1 6 10.5zM8 10.5V7.5a4 4 0 0 1 8 0v3',
  lockReset: 'M6 11h12a1.5 1.5 0 0 1 1.5 1.5v7A1.5 1.5 0 0 1 18 21H6a1.5 1.5 0 0 1-1.5-1.5v-7A1.5 1.5 0 0 1 6 11zM8 11V8a4 4 0 0 1 6.9-2.7M12 14v3M15.5 3.2v3h-3',
  person: 'M12 12a4 4 0 1 0 0-8 4 4 0 0 0 0 8zM4.5 20.5a7.5 7.5 0 0 1 15 0',
  route: 'M7 20V9.5A3.5 3.5 0 0 1 10.5 6H15M15 6l-2.5-2.5M15 6l-2.5 2.5M17 4v10.5a3.5 3.5 0 0 1-3.5 3.5H9M9 18l2.5-2.5M9 18l2.5 2.5',
  album: 'M5 4.5h14A1.5 1.5 0 0 1 20.5 6v12a1.5 1.5 0 0 1-1.5 1.5H5A1.5 1.5 0 0 1 3.5 18V6A1.5 1.5 0 0 1 5 4.5zM3.5 15.5l4.5-4 3.5 3 3-2.5 6 5',
  bookmark: 'M7 3.5h10a1.5 1.5 0 0 1 1.5 1.5v15.5L12 16.5 5.5 20.5V5A1.5 1.5 0 0 1 7 3.5z',
  inbox: 'M3.5 8.5 12 3l8.5 5.5V19a1.5 1.5 0 0 1-1.5 1.5H5A1.5 1.5 0 0 1 3.5 19zM3.5 8.5 12 14l8.5-5.5',
  tune: 'M4 7h9M17 7h3M4 17h3M11 17h9M15 4v6M8 14v6',
  edit: 'M4 20h4l10-10a2.1 2.1 0 0 0-3-3L5 17zM14.5 6.5l3 3',
  eye: 'M2.5 12S6 5.5 12 5.5 21.5 12 21.5 12 18 18.5 12 18.5 2.5 12 2.5 12zM12 15a3 3 0 1 0 0-6 3 3 0 0 0 0 6z',
  eyeOff: 'M4 4l16 16M9.9 5.9A9.6 9.6 0 0 1 12 5.5c6 0 9.5 6.5 9.5 6.5a17 17 0 0 1-3.3 4.1M6.4 7.7A16.7 16.7 0 0 0 2.5 12S6 18.5 12 18.5c1 0 1.9-.2 2.7-.5M9.9 9.9a3 3 0 0 0 4.2 4.2',
  arrowRight: 'M4 12h15M13 6l6 6-6 6',
  arrowLeft: 'M20 12H5M11 18l-6-6 6-6',
  send: 'M4.5 12 20 4.5 15 20l-3.5-6.5z',
  leaf: 'M20 4S18.5 15 11 17.5C7 18.8 4.6 17 4.6 17S4 9.5 11 6.5c3.5-1.5 9-2.5 9-2.5zM4.5 20C6 15.5 9 11.5 14 8.5',
  flower: 'M12 8.5a3.5 3.5 0 1 0 0 7 3.5 3.5 0 0 0 0-7zM12 8.5V4M12 15.5V20M8.5 12H4M15.5 12H20',
  logout: 'M14.5 16.5V19A1.5 1.5 0 0 1 13 20.5H6A1.5 1.5 0 0 1 4.5 19V5A1.5 1.5 0 0 1 6 3.5h7A1.5 1.5 0 0 1 14.5 5v2.5M10 12h10M17 8.5l3 3.5-3 3.5',
  check: 'M4.5 12.5 9.5 17.5 19.5 6.5',
  clock: 'M12 20.5a8.5 8.5 0 1 0 0-17 8.5 8.5 0 0 0 0 17zM12 7v5.2l3.4 2',
  // 모바일 하단 탭바용
  home: 'M3.5 10.5 12 3.5l8.5 7V19a1.5 1.5 0 0 1-1.5 1.5h-4v-6h-6v6H5A1.5 1.5 0 0 1 3.5 19z',
  map: 'M9 4.5 3.5 6.9V20l5.5-2.4 6 2.4 5.5-2.4V4.5L15 6.9zM9 4.5v13.1M15 6.9V20'
}

// 채움(fill) 기반 아이콘 — 시안이 FILL 1 로 쓰는 자리
const FILLED = {
  leaf: 'M20.5 3.5S18.9 15 11.2 17.7C7.6 18.9 5 17.5 5 17.5s-.4-8.2 6.6-11.2c3.6-1.6 8.9-2.8 8.9-2.8z',
  flower: 'M12 8a4 4 0 1 0 0 8 4 4 0 0 0 0-8z',
  dot: 'M12 6a6 6 0 1 0 0 12 6 6 0 0 0 0-12z'
}
</script>

<template>
  <svg
    :width="size" :height="size" viewBox="0 0 24 24"
    :fill="fill ? 'currentColor' : 'none'"
    aria-hidden="true" focusable="false"
    class="ico"
  >
    <path
      :d="fill ? (FILLED[name] || STROKE[name]) : STROKE[name]"
      :stroke="fill ? 'none' : 'currentColor'"
      stroke-width="1.8" stroke-linecap="round" stroke-linejoin="round"
    />
  </svg>
</template>

<style scoped>
.ico { display: block; flex-shrink: 0; }
</style>
