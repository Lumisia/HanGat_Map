ALTER TABLE notifications ADD COLUMN header_hidden_at DATETIME(6) NULL;
-- 공식 기상특보 수신 기능은 사용하지 않는다. 기존 배포와의 전환을 위해 컬럼은 유지한다.
UPDATE user_notification_settings SET weather_warning = FALSE;
ALTER TABLE user_notification_settings ALTER COLUMN weather_warning SET DEFAULT FALSE;
