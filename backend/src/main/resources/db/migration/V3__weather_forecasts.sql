-- V3: 권역 날씨 예보(테이블 명세서 17.0) + course_items 날씨 스냅숏 FK 전환
-- (V2는 숙소 저장 연동이 먼저 썼다. 새 스크립트는 git ls-tree origin/dev backend/src/main/resources/db/migration 으로
--  현재 최대 버전을 확인하고 그 다음 번호를 쓴다 - FlywayMigrationResolutionTest가 중복을 잡는다.)
--
-- 발표 시각(base_at)별 append 이력이다 - UPSERT로 덮어쓰면 "저장 시점 예보 vs 최신 예보" 비교(MY_008)가
-- 불가능해진다. congestion_forecasts(16.0)와 같은 구조.
-- 1단계(2026-09-03)는 DAILY 행만 적재한다. HOURLY 전용 컬럼(temperature, precipitation_mm, snow_cm,
-- wind_speed, humidity)은 명세서와 스키마를 맞추기 위해 두고 NULL로 남긴다.
-- UNSIGNED·CHECK는 팀 컨벤션대로 재현하지 않는다(값 범위는 적재 코드가 거른다).
--
-- ⚠️ collation을 박아 넣지 않는다. source_code가 data_sources.code(varchar)를 FK로 참조하는데,
-- FK는 두 컬럼의 collation이 같아야 한다(다르면 errno 150 "Foreign key constraint is incorrectly formed").
-- V1로 만든 운영 DB는 utf8mb4_unicode_ci, Hibernate ddl-auto로 만든 개발 DB는 서버 기본(MariaDB 11+는
-- utf8mb4_uca1400_ai_ci)이라 환경마다 다르다. 참조 테이블의 collation을 읽어 그대로 쓴다.

SET @weather_collation = (
  SELECT COLLATION_NAME FROM information_schema.COLUMNS
  WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'data_sources' AND COLUMN_NAME = 'code'
);

SET @weather_ddl = CONCAT('CREATE TABLE `weather_forecasts` (
  `id` bigint(20) NOT NULL AUTO_INCREMENT,
  `region_id` smallint(6) NOT NULL,
  `forecast_at` datetime(6) NOT NULL,
  `base_at` datetime(6) NOT NULL,
  `granularity` enum(''DAILY'',''HOURLY'') NOT NULL,
  `sky_code` varchar(20) DEFAULT NULL,
  `precipitation_type` enum(''NONE'',''RAIN'',''RAIN_SNOW'',''SNOW'',''SHOWER'',''UNKNOWN'') DEFAULT NULL,
  `temperature` decimal(4,1) DEFAULT NULL,
  `temp_min` decimal(4,1) DEFAULT NULL,
  `temp_max` decimal(4,1) DEFAULT NULL,
  `rain_probability` tinyint(4) DEFAULT NULL,
  `precipitation_mm` decimal(7,2) DEFAULT NULL,
  `snow_cm` decimal(6,2) DEFAULT NULL,
  `wind_speed` decimal(5,2) DEFAULT NULL,
  `humidity` tinyint(4) DEFAULT NULL,
  `source_code` varchar(30) NOT NULL,
  `fetched_at` datetime(6) NOT NULL,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_weather_region_forecast_base_gran` (`region_id`,`forecast_at`,`base_at`,`granularity`),
  KEY `idx_weather_region_forecast` (`region_id`,`forecast_at`,`base_at`),
  KEY `idx_weather_base_at` (`base_at`),
  KEY `fk_weather_source` (`source_code`),
  CONSTRAINT `fk_weather_region` FOREIGN KEY (`region_id`) REFERENCES `regions` (`id`),
  CONSTRAINT `fk_weather_source` FOREIGN KEY (`source_code`) REFERENCES `data_sources` (`code`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=', @weather_collation);

PREPARE weather_stmt FROM @weather_ddl;
EXECUTE weather_stmt;
DEALLOCATE PREPARE weather_stmt;

-- course_items.planned_weather_forecast_id 는 V1부터 있었지만 참조 테이블이 없어 FK가 빠져 있었다.
-- 명세서 FK 관계 34.0: ON DELETE SET NULL - 예보 행이 지워지면 스냅숏은 '날씨 정보 없음'으로 돌아간다.
ALTER TABLE `course_items`
  ADD KEY `fk_course_items_planned_weather` (`planned_weather_forecast_id`),
  ADD CONSTRAINT `fk_course_items_planned_weather`
    FOREIGN KEY (`planned_weather_forecast_id`) REFERENCES `weather_forecasts` (`id`) ON DELETE SET NULL;
