# Gemini 오류 진단 기록

## 저장 범위

`course_ai_diagnostics`는 Gemini 재시도가 끝난 뒤의 실패와 AI 결과 검증 실패를 기록한다.
대체 코스가 성공해도 실패 기록을 남기며, 코스 트랜잭션과 별도로 커밋한다.
작업이 중단되면 `outcome=PENDING`이 남을 수 있으므로 대체 생성 실패로 단정하지 않는다.

- 서버가 생성한 이벤트·추적 UUID와 비동기 작업 UUID
- 오류 분류, 결과 검증 코드(enum), 실패 단계, HTTP 상태, 허용 목록의 Google 상태·사유와 네트워크 분류
- 제한된 형식의 모델명, 시도 횟수와 소요 시간
- 최초/교정 호출 구분, 교정·대체 코스 성공/실패

API 키, 프롬프트, 전체 또는 일부 원문 응답, 공급자 오류 메시지, 요청/응답 헤더,
URL, 스택 트레이스는 이 테이블에 저장하지 않는다. 임의 문자열을 담을 메시지/JSON 컬럼도 없다.
Google 상태·사유의 알 수 없는 값은 `OTHER`, 없는 값은 NULL로 저장한다.
새로운 사유가 필요하면 공식 정의를 검토한 뒤 코드 허용 목록에 추가한다.
네트워크 또는 요청 변환 실패에는 HTTP 응답 자체가 없을 수 있다. 이 경우 상태는 NULL이다.

## 접근과 운영

진단 조회용 사용자 API는 제공하지 않는다. 운영 권한이 있는 담당자만 DB에서 조회한다.
사용자 API는 기존의 일반 오류 코드/메시지만 반환한다.
기존 `course_generation_jobs.error_code=AI_GENERATION_FAILED` 계약은 바꾸지 않는다.
HTTP 클라이언트/변환기 본문 로그는 공통 설정에서 비활성화한다. 운영에서 해당 로거를
DEBUG/TRACE로 다시 켜거나 프록시/APM에 본문 수집을 추가하면 안 된다.

스케줄링이 활성화된 실행 환경에서는 매일 한국 시간 04:15에 30일보다 오래된 진단 기록만 정리한다.
모든 스케줄링을 끈 환경은 별도 운영 정리 작업이 필요하며, 보존 기간을 무제한으로 두면 안 된다.
DB 기록 실패는 원문 예외 없이 `AI_DIAGNOSTIC_WRITE_FAILED` 등의 고정 로그로 알리며,
기존 코스 생성/대체 처리를 중단하지 않는다. 해당 경고는 운영 모니터링 대상이다.

```sql
SELECT occurred_at, job_id, trace_id, call_kind, failure_type, validation_code, phase,
       http_status, google_status, google_reason, network_type,
       model, attempts, elapsed_ms, outcome
FROM course_ai_diagnostics
ORDER BY occurred_at DESC
LIMIT 50;
```

운영 적용에는 Flyway `V15__course_ai_diagnostics.sql` 마이그레이션이 필요하다.
이 변경은 운영 배포·실제 Gemini 호출·기존 데이터 삭제를 수행하지 않는다.
