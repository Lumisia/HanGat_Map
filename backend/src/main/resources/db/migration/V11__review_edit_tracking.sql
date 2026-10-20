-- 기존 후기를 수정된 것으로 간주하지 않는다. 실제 작성자 변경이 발생할 때만 기록한다.
ALTER TABLE reviews ADD COLUMN edited_at DATETIME(6) NULL;
