# syntax=docker/dockerfile:1

# ────────────────────────── 프론트엔드 빌드 ──────────────────────────

FROM node:22-alpine AS builder

WORKDIR /workspace

COPY frontend/package.json frontend/package-lock.json ./

RUN --mount=type=cache,target=/root/.npm \
    npm ci

COPY frontend/index.html ./
COPY frontend/vite.config.js ./
COPY frontend/tsconfig.json ./
COPY frontend/tsconfig.app.json ./
COPY frontend/tsconfig.node.json ./
COPY frontend/public ./public
COPY frontend/src ./src

ARG VITE_API_BASE_URL=/api

# 회원 비동기 생성은 운영 기본 기능. 다른 알림 플래그는 기존 기본값 유지.
ARG VITE_NOTIFICATIONS_ENABLED=false
ARG VITE_ASYNC_COURSES_ENABLED=true
ARG VITE_TRIP_ALERTS_ENABLED=false
ARG VITE_TRIP_NOTIFICATIONS_ENABLED=false

RUN --mount=type=secret,id=kakao_map_key,required=true \
    KAKAO_MAP_VALUE="$(cat /run/secrets/kakao_map_key)" \
    && test -n "${KAKAO_MAP_VALUE}" \
    && VITE_API_BASE_URL="${VITE_API_BASE_URL}" \
       VITE_NOTIFICATIONS_ENABLED="${VITE_NOTIFICATIONS_ENABLED}" \
       VITE_ASYNC_COURSES_ENABLED="${VITE_ASYNC_COURSES_ENABLED}" \
       VITE_TRIP_ALERTS_ENABLED="${VITE_TRIP_ALERTS_ENABLED}" \
       VITE_TRIP_NOTIFICATIONS_ENABLED="${VITE_TRIP_NOTIFICATIONS_ENABLED}" \
       VITE_KAKAO_MAP_KEY="${KAKAO_MAP_VALUE}" \
       VITE_KAKAO_MAP_APP_KEY="${KAKAO_MAP_VALUE}" \
       npm run build

# ────────────────────────── 프론트엔드 실행 ──────────────────────────

FROM nginxinc/nginx-unprivileged:1.30.4-alpine AS runtime

COPY cicd/nginx.conf /etc/nginx/conf.d/default.conf
COPY --from=builder /workspace/dist /usr/share/nginx/html

EXPOSE 8080

CMD ["nginx", "-g", "daemon off;"]
