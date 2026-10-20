-- 화면에서 삭제하되 중복 방지 키는 보존하여 배치 재시도 시 같은 알림이 재생성되지 않게 한다.
ALTER TABLE notifications ADD COLUMN deleted_at DATETIME(6) DEFAULT NULL;
CREATE INDEX idx_notification_user_visible ON notifications (user_id, deleted_at, id);
