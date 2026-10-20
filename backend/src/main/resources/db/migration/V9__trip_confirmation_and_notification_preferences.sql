-- 회원별 수신 설정과 현재 확정한 여행.
-- 같은 행을 잠가 설정 변경과 알림 생성을 순서대로 처리한다.
CREATE TABLE user_notification_settings (
                                            user_id BIGINT NOT NULL,

                                            ai_course BOOLEAN NOT NULL DEFAULT TRUE,
                                            weather_warning BOOLEAN NOT NULL DEFAULT TRUE,
                                            forecast_change BOOLEAN NOT NULL DEFAULT TRUE,
                                            congestion BOOLEAN NOT NULL DEFAULT TRUE,
                                            trip_summary BOOLEAN NOT NULL DEFAULT TRUE,
                                            review_request BOOLEAN NOT NULL DEFAULT TRUE,

    -- 수신 설정의 동시 수정 충돌 방지.
                                            preferences_version BIGINT NOT NULL DEFAULT 0,

    -- 한 회원은 한 코스를 여행 알림 대상으로 확정한다.
                                            trip_course_id BIGINT DEFAULT NULL,
                                            trip_title VARCHAR(100) DEFAULT NULL,
                                            trip_start_date DATE DEFAULT NULL,
                                            trip_end_date DATE DEFAULT NULL,
                                            trip_confirmed_at DATETIME(6) DEFAULT NULL,

    -- 취소해도 버전을 초기화하지 않는다.
    -- 취소 후 같은 코스를 다시 확정했을 때 예전 작업을 구분한다.
                                            trip_version BIGINT NOT NULL DEFAULT 0,

                                            updated_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),

                                            PRIMARY KEY (user_id),
                                            KEY idx_notification_trip_course (trip_course_id),
                                            KEY idx_notification_trip_dates (trip_start_date, trip_end_date),

                                            CONSTRAINT fk_notification_settings_user
                                                FOREIGN KEY (user_id)
                                                    REFERENCES users (id)
                                                    ON DELETE CASCADE,

                                            CONSTRAINT fk_notification_settings_course
                                                FOREIGN KEY (trip_course_id)
                                                    REFERENCES courses (id)
                                                    ON DELETE SET NULL,

                                            CONSTRAINT chk_notification_trip_dates
                                                CHECK (
                                                    trip_start_date IS NULL
                                                        OR trip_end_date >= trip_start_date
                                                    )
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;