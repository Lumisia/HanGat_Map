-- 운영자가 명시적으로 실행하는 데모 계정 준비 SQL. Flyway 자동 실행 대상이 아니다.
-- 기존 계정은 변경하지 않는다. 이메일/닉네임 충돌 시 상태를 확인하고 수동 판단한다.
-- 비밀번호는 NULL: 이메일·비밀번호 로그인이 아닌 /auth/demo-login 전용이다.
SET NAMES utf8mb4 COLLATE utf8mb4_unicode_ci;
START TRANSACTION;

INSERT INTO users (email, nickname, password, status, email_verified_at, created_at, updated_at)
SELECT 'demo@hangatjeju.com', '한갓지도 데모계정', NULL, 'ACTIVE',
       UTC_TIMESTAMP(6), UTC_TIMESTAMP(6), UTC_TIMESTAMP(6)
WHERE NOT EXISTS (SELECT 1 FROM users WHERE email = 'demo@hangatjeju.com');

COMMIT;

SELECT email, nickname, status,
       email_verified_at IS NOT NULL AS email_verified,
       password IS NULL AS password_login_disabled
FROM users WHERE email = 'demo@hangatjeju.com';
