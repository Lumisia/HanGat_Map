-- 알림용 최신 시간별 강수 예보.
-- 기존 weather_forecasts의 일별 요약과 분리한다.
CREATE TABLE trip_weather_snapshots (
                                        region_id SMALLINT NOT NULL,
                                        issued_at DATETIME(6) NOT NULL,
                                        fetched_at DATETIME(6) NOT NULL,
                                        payload_json LONGTEXT NOT NULL,

                                        PRIMARY KEY (region_id),

                                        CONSTRAINT fk_trip_weather_region
                                            FOREIGN KEY (region_id)
                                                REFERENCES regions(id)
                                                ON DELETE CASCADE
);

-- 현재 확정 여행의 기준값과 마지막 비교 결과.
-- 여행이 교체되면 trip_version과 기준값을 함께 교체한다.
CREATE TABLE trip_notification_checkpoints (
                                               user_id BIGINT NOT NULL,
                                               trip_version BIGINT NOT NULL,
                                               change_revision BIGINT NOT NULL DEFAULT 0,
                                               baseline_json LONGTEXT NOT NULL,
                                               last_json LONGTEXT NOT NULL,
                                               updated_at DATETIME(6) NOT NULL,

                                               PRIMARY KEY (user_id),

                                               CONSTRAINT fk_trip_notification_checkpoint_user
                                                   FOREIGN KEY (user_id)
                                                       REFERENCES users(id)
                                                       ON DELETE CASCADE
);
