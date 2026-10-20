# 회원 비동기 코스 생성 배포 계약

- Jenkins Frontend Docker 빌드: `VITE_ASYNC_COURSES_ENABLED=true`를 전달한다.
- Docker 직접 빌드도 해당 ARG 기본값은 `true`다. 일반 production Vite 빌드도
  변수가 없으면 활성화하며, 명시적인 `false`는 유지한다. 개발 모드도 같은 기본값이다.
- Backend 공통 설정도 같은 활성화 기본값을 사용한다. dev/local 실행에도 개인 VM 옵션이
  필요하지 않다. 기존에 실행된 Backend에는 재기동 후 적용된다.
- Backend prod 프로필은 `hangat.async.enabled` 기본값이 `true`다.
- Helm 공유 configEnv의 `HANGAT_ASYNC_ENABLED=true`는 이미 제공된다.
  Backend 컨테이너 CLI 인자로 전달해 오래된 Secret의 동일 플래그가 가리지 않게 한다.
- Jenkins lint/template/deploy는 private values 뒤에 같은 값을 명시한다.
  Frontend만 켜지고 Backend는 private values의 false로 꺼지는 배포를 방지한다.
- 비상 비활성화 시 Frontend 빌드 인자와 Backend 배포 값을 함께 변경한다.
  이미 빌드한 nginx 컨테이너에 환경변수를 추가해도 Vite 번들 값은 바뀌지 않는다.

## Migration 및 환경별 확인

Backend Docker 이미지에 `src/main/resources/db/migration`이 포함된다.
prod는 기존대로 Flyway enabled/validate-on-migrate, Hibernate validate를 사용한다.
Flyway baseline-on-migrate는 false이고 자동 baseline/repair는 수행하지 않는다.
운영 기동 시 정상 Flyway history 기준의 미적용 migration이 실행된다.
수동 적용 DB나 history 불일치는 별도 검토가 필요하며 배포로 무조건 해결하지 않는다.

Jenkins `hangat-values-private`, 실제 운영 Secret, DB 권한/이력은 저장소 밖에서 관리된다.
특히 prod 프로필과 Flyway를 비활성화하는 외부 override가 없는지 배포 담당자가 확인해야 한다.
DB 연결·Gemini/Kakao 키 등 기존 필수 Secret 검증은 그대로 유지한다.
이번 변경은 실제 운영 Secret/DB를 읽거나 수정하지 않았다.

## 배포 후 확인

1. Backend와 Frontend를 같은 변경 기준으로 빌드·배포한다.
2. Flyway 적용 성공, Hibernate validate, Backend readiness를 확인한다.
3. 로그인 회원 입력 화면에서 최근 요청 모달을 열어 5건 서버 페이지 조회를 확인한다.
4. 비회원은 회원용 목록이 숨겨지고 기존 동기 생성 계약을 유지한다.

사용자별 설정은 필요 없다. 실제 Jenkins/Helm 실행과 운영 로그인 검증은 별도 배포 단계다.
