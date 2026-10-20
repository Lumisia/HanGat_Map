-- 작업 접수/실행 제한을 여러 요청에서 동시에 검사하기 위한 잠금 행.
CREATE TABLE ai_queue_control (
                                  id VARCHAR(30) NOT NULL,
                                  PRIMARY KEY (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

INSERT INTO ai_queue_control (id) VALUES ('COURSE_GENERATION');

CREATE TABLE course_generation_jobs (
                                        id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
                                        user_id BIGINT NOT NULL,
                                        request_key CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
                                        request_hash CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
                                        request_json LONGTEXT NOT NULL,

                                        status VARCHAR(20) NOT NULL,
                                        start_date DATE NOT NULL,
                                        end_date DATE NOT NULL,

                                        course_id BIGINT DEFAULT NULL,
                                        error_code VARCHAR(60) DEFAULT NULL,

                                        lease_token CHAR(36) CHARACTER SET ascii COLLATE ascii_bin DEFAULT NULL,
                                        lease_until DATETIME(6) DEFAULT NULL,
                                        execution_deadline DATETIME(6) DEFAULT NULL,

                                        created_at DATETIME(6) NOT NULL,
                                        started_at DATETIME(6) DEFAULT NULL,
                                        completed_at DATETIME(6) DEFAULT NULL,

                                        PRIMARY KEY (id),
                                        UNIQUE KEY uk_generation_user_request (user_id, request_key),
                                        UNIQUE KEY uk_generation_course (course_id),
                                        KEY idx_generation_queue (status, created_at),
                                        KEY idx_generation_user_created (user_id, created_at),
                                        KEY idx_generation_lease (status, lease_until),

                                        CONSTRAINT fk_generation_user
                                            FOREIGN KEY (user_id) REFERENCES users (id),

                                        CONSTRAINT fk_generation_course
                                            FOREIGN KEY (course_id) REFERENCES courses (id),

                                        CONSTRAINT chk_generation_request_json
                                            CHECK (JSON_VALID(request_json))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE notifications (
                               id BIGINT NOT NULL AUTO_INCREMENT,
                               user_id BIGINT NOT NULL,

                               type VARCHAR(50) NOT NULL,
                               title VARCHAR(150) NOT NULL,
                               message VARCHAR(2000) NOT NULL,

                               target_type VARCHAR(40) DEFAULT NULL,
                               target_id VARCHAR(100) DEFAULT NULL,

                               dedupe_key VARCHAR(190) NOT NULL,
                               created_at DATETIME(6) NOT NULL,
                               read_at DATETIME(6) DEFAULT NULL,

                               PRIMARY KEY (id),
                               UNIQUE KEY uk_notification_dedupe (user_id, dedupe_key),
                               KEY idx_notification_user_id (user_id, id),
                               KEY idx_notification_user_unread (user_id, read_at),

                               CONSTRAINT fk_notification_user
                                   FOREIGN KEY (user_id) REFERENCES users (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- 전송 신호가 프로세스 재시작으로 사라지지 않도록 DB에 보관한다.
-- 알림 본문 자체의 보관 장소는 notifications다.
CREATE TABLE notification_outbox (
                                     notification_id BIGINT NOT NULL,
                                     user_id BIGINT NOT NULL,
                                     created_at DATETIME(6) NOT NULL,

                                     PRIMARY KEY (notification_id),
                                     KEY idx_notification_outbox_created (created_at),

                                     CONSTRAINT fk_notification_outbox_notification
                                         FOREIGN KEY (notification_id)
                                             REFERENCES notifications (id)
                                             ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;