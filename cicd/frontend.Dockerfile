# syntax=docker/dockerfile:1

# ────────────────────────── 프론트엔드 빌드 ──────────────────────────

FROM node:22-alpine AS builder

WORKDIR /workspace

# 의존성 파일을 먼저 복사해 npm 캐시를 재사용한다.
COPY frontend/package.json frontend/package-lock.json ./

RUN --mount=type=cache,target=/root/.npm \
    npm ci

# 빌드에 필요한 파일만 복사한다.
COPY frontend/index.html ./
COPY frontend/vite.config.js ./
COPY frontend/tsconfig.json ./
COPY frontend/tsconfig.app.json ./
COPY frontend/tsconfig.node.json ./
COPY frontend/public ./public
COPY frontend/src ./src

# Jenkins가 운영 API 서브도메인을 전달한다.
# 별도로 전달하지 않는 로컬 이미지 빌드는 /api를 기본값으로 사용한다.
ARG VITE_API_BASE_URL=/api

# 카카오 지도 키는 빌드할 때만 임시로 마운트한다.
# 두 지도 로더가 서로 다른 변수명을 사용하므로 같은 값을 전달한다.
RUN --mount=type=secret,id=kakao_map_key,required=true \
    KAKAO_MAP_VALUE="$(cat /run/secrets/kakao_map_key)" \
    && test -n "${KAKAO_MAP_VALUE}" \
    && VITE_API_BASE_URL="${VITE_API_BASE_URL}" \
       VITE_KAKAO_MAP_KEY="${KAKAO_MAP_VALUE}" \
       VITE_KAKAO_MAP_APP_KEY="${KAKAO_MAP_VALUE}" \
       npm run build


# ────────────────────────── 프론트엔드 실행 ──────────────────────────

FROM nginxinc/nginx-unprivileged:1.30.4-alpine AS runtime

# 프론트 전용 Nginx 설정을 적용한다.
COPY cicd/nginx.conf /etc/nginx/conf.d/default.conf

# 빌드된 Vue 정적 파일만 실행 이미지로 가져온다.
COPY --from=builder \
    /workspace/dist \
    /usr/share/nginx/html

EXPOSE 8080

CMD ["nginx", "-g", "daemon off;"]