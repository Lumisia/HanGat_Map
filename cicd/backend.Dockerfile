# syntax=docker/dockerfile:1

# ────────────────────────── 백엔드 빌드. ──────────────────────────

FROM eclipse-temurin:17-jdk-jammy AS builder

WORKDIR /workspace

# 의존성 파일을 먼저 복사해 소스 변경 시에도 Gradle 캐시를 재사용.
COPY backend/gradlew ./gradlew
COPY backend/gradle ./gradle
COPY backend/build.gradle backend/settings.gradle ./

# Windows에서 체크아웃된 gradlew의 CRLF를 Linux 형식으로 정리.
RUN sed -i 's/\r$//' ./gradlew \
    && chmod +x ./gradlew

# BuildKit 캐시에 Gradle 의존성 보관.
RUN --mount=type=cache,target=/root/.gradle \
    ./gradlew dependencies --no-daemon

# 실행 JAR 생성에는 main 소스만 필요하다.
COPY backend/src/main ./src/main

RUN --mount=type=cache,target=/root/.gradle \
    ./gradlew bootJar --no-daemon


# ────────────────────────── 백엔드 실행. ──────────────────────────

FROM eclipse-temurin:17-jre-jammy AS runtime

WORKDIR /app

# 컨테이너를 root가 아닌 전용 사용자로 실행.
# 현재 후기 이미지가 로컬 디렉터리를 사용하므로 쓰기 권한도 준비.
RUN groupadd --gid 10001 hangat \
    && useradd \
       --uid 10001 \
       --gid hangat \
       --no-create-home \
       --home-dir /app \
       --shell /usr/sbin/nologin \
       hangat \
    && mkdir -p /app/uploads/reviews \
    && chown -R hangat:hangat /app

COPY --from=builder \
    --chown=hangat:hangat \
    /workspace/build/libs/hangat-api.jar \
    /app/app.jar

USER hangat

EXPOSE 8080

ENTRYPOINT ["java", "-jar", "/app/app.jar"]