-- --------------------------------------------------------
-- 호스트:                          localhost
-- 서버 버전:                        12.2.2-MariaDB - MariaDB Server
-- 서버 OS:                        Win64
-- HeidiSQL 버전:                  12.14.0.7165
-- --------------------------------------------------------

/*!40101 SET @OLD_CHARACTER_SET_CLIENT=@@CHARACTER_SET_CLIENT */;
/*!40101 SET NAMES utf8 */;
/*!50503 SET NAMES utf8mb4 */;
/*!40103 SET @OLD_TIME_ZONE=@@TIME_ZONE */;
/*!40103 SET TIME_ZONE='+00:00' */;
/*!40014 SET @OLD_FOREIGN_KEY_CHECKS=@@FOREIGN_KEY_CHECKS, FOREIGN_KEY_CHECKS=0 */;
/*!40101 SET @OLD_SQL_MODE=@@SQL_MODE, SQL_MODE='NO_AUTO_VALUE_ON_ZERO' */;
/*!40111 SET @OLD_SQL_NOTES=@@SQL_NOTES, SQL_NOTES=0 */;

-- 테이블 hangat_schema_gen.congestion_forecasts 구조 내보내기
CREATE TABLE `congestion_forecasts` (
  `rate` decimal(5,2) NOT NULL,
  `base_at` datetime(6) NOT NULL,
  `fetched_at` datetime(6) NOT NULL,
  `forecast_at` datetime(6) NOT NULL,
  `id` bigint(20) NOT NULL AUTO_INCREMENT,
  `place_id` bigint(20) NOT NULL,
  `source_code` varchar(30) NOT NULL,
  `level` enum('CROWDED','NORMAL','QUIET') NOT NULL,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_congestion_place_forecast_base` (`place_id`,`forecast_at`,`base_at`),
  KEY `idx_congestion_place_forecast` (`place_id`,`forecast_at`,`base_at`),
  KEY `idx_congestion_base_at` (`base_at`),
  KEY `idx_congestion_lookup` (`forecast_at`,`base_at`,`level`,`rate`),
  KEY `fk_congestion_source` (`source_code`),
  CONSTRAINT `fk_congestion_place` FOREIGN KEY (`place_id`) REFERENCES `places` (`id`),
  CONSTRAINT `fk_congestion_source` FOREIGN KEY (`source_code`) REFERENCES `data_sources` (`code`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- 내보낼 데이터가 선택되어 있지 않습니다.

-- 테이블 hangat_schema_gen.course_item_costs 구조 내보내기
CREATE TABLE `course_item_costs` (
  `amount_max` int(11) DEFAULT NULL,
  `amount_min` int(11) DEFAULT NULL,
  `calculated_at` datetime(6) NOT NULL,
  `course_id` bigint(20) NOT NULL,
  `course_item_id` bigint(20) DEFAULT NULL,
  `id` bigint(20) NOT NULL AUTO_INCREMENT,
  `menu_id` bigint(20) DEFAULT NULL,
  `basis_text` varchar(300) DEFAULT NULL,
  `currency` char(3) NOT NULL,
  `accuracy_type` enum('ESTIMATED','UNKNOWN','VERIFIED') NOT NULL,
  `category` enum('ACTIVITY','FOOD','LODGING','OTHER','TRANSPORT') NOT NULL,
  PRIMARY KEY (`id`),
  KEY `idx_course_item_costs_course_category` (`course_id`,`category`),
  KEY `fk_course_item_costs_item` (`course_item_id`),
  CONSTRAINT `fk_course_item_costs_course` FOREIGN KEY (`course_id`) REFERENCES `courses` (`id`) ON DELETE CASCADE,
  CONSTRAINT `fk_course_item_costs_item` FOREIGN KEY (`course_item_id`) REFERENCES `course_items` (`id`) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- 내보낼 데이터가 선택되어 있지 않습니다.

-- 테이블 hangat_schema_gen.course_items 구조 내보내기
CREATE TABLE `course_items` (
  `day_no` smallint(6) NOT NULL,
  `end_time` time(6) DEFAULT NULL,
  `inbound_distance_m` int(11) DEFAULT NULL,
  `inbound_travel_minutes` smallint(6) DEFAULT NULL,
  `position` smallint(6) NOT NULL,
  `recommendation_score` decimal(8,4) DEFAULT NULL,
  `start_time` time(6) DEFAULT NULL,
  `visit_date` date NOT NULL,
  `course_id` bigint(20) NOT NULL,
  `created_at` datetime(6) NOT NULL,
  `id` bigint(20) NOT NULL AUTO_INCREMENT,
  `place_id` bigint(20) NOT NULL,
  `planned_congestion_forecast_id` bigint(20) DEFAULT NULL,
  `planned_weather_forecast_id` bigint(20) DEFAULT NULL,
  `replaced_from_place_id` bigint(20) DEFAULT NULL,
  `updated_at` datetime(6) NOT NULL,
  `recommendation_reason_code` varchar(30) DEFAULT NULL,
  `memo` varchar(300) DEFAULT NULL,
  `recommendation_reason` varchar(300) DEFAULT NULL,
  `item_source` enum('AI_RECOMMENDED','REPLACEMENT','USER_FIXED') NOT NULL,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_course_items_day_position` (`course_id`,`day_no`,`position`),
  KEY `idx_course_items_course_visit` (`course_id`,`visit_date`),
  KEY `fk_course_items_place` (`place_id`),
  KEY `fk_course_items_planned_congestion` (`planned_congestion_forecast_id`),
  KEY `fk_course_items_replaced_place` (`replaced_from_place_id`),
  CONSTRAINT `fk_course_items_course` FOREIGN KEY (`course_id`) REFERENCES `courses` (`id`) ON DELETE CASCADE,
  CONSTRAINT `fk_course_items_place` FOREIGN KEY (`place_id`) REFERENCES `places` (`id`),
  CONSTRAINT `fk_course_items_planned_congestion` FOREIGN KEY (`planned_congestion_forecast_id`) REFERENCES `congestion_forecasts` (`id`) ON DELETE SET NULL,
  CONSTRAINT `fk_course_items_replaced_place` FOREIGN KEY (`replaced_from_place_id`) REFERENCES `places` (`id`) ON DELETE SET NULL
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- 내보낼 데이터가 선택되어 있지 않습니다.

-- 테이블 hangat_schema_gen.course_presets 구조 내보내기
CREATE TABLE `course_presets` (
  `default_budget_total` int(11) DEFAULT NULL,
  `default_people` smallint(6) NOT NULL,
  `duration_days` smallint(6) NOT NULL,
  `is_active` tinyint(1) NOT NULL,
  `created_at` datetime(6) NOT NULL,
  `id` bigint(20) NOT NULL AUTO_INCREMENT,
  `updated_at` datetime(6) NOT NULL,
  `code` varchar(30) NOT NULL,
  `default_title` varchar(100) NOT NULL,
  `name` varchar(100) NOT NULL,
  `description` varchar(300) DEFAULT NULL,
  `filter_json` longtext DEFAULT NULL,
  `default_transport` enum('PUBLIC_TRANSIT','RENTAL_CAR','TAXI','WALK_BIKE') DEFAULT NULL,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_course_presets_code` (`code`),
  CONSTRAINT `ck_course_presets_filter_json` CHECK (`filter_json` is null or json_valid(`filter_json`))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- 내보낼 데이터가 선택되어 있지 않습니다.

-- 테이블 hangat_schema_gen.courses 구조 내보내기
CREATE TABLE `courses` (
  `average_congestion_rate` decimal(5,2) DEFAULT NULL,
  `budget_total` int(11) DEFAULT NULL,
  `end_date` date NOT NULL,
  `estimated_cost_max` int(11) DEFAULT NULL,
  `estimated_cost_min` int(11) DEFAULT NULL,
  `people` smallint(6) NOT NULL,
  `start_date` date NOT NULL,
  `created_at` datetime(6) NOT NULL,
  `deleted_at` datetime(6) DEFAULT NULL,
  `generation_completed_at` datetime(6) DEFAULT NULL,
  `id` bigint(20) NOT NULL AUTO_INCREMENT,
  `preset_id` bigint(20) DEFAULT NULL,
  `saved_at` datetime(6) DEFAULT NULL,
  `updated_at` datetime(6) NOT NULL,
  `user_id` bigint(20) DEFAULT NULL,
  `algorithm_version` varchar(30) DEFAULT NULL,
  `generation_error_code` varchar(50) DEFAULT NULL,
  `title` varchar(100) DEFAULT NULL,
  `input_fingerprint` char(64) DEFAULT NULL,
  `course_type` enum('SAMPLE','USER') NOT NULL,
  `generation_reason` enum('INITIAL','SAMPLE_BATCH','USER_REGENERATE','WEATHER_REPLAN') NOT NULL,
  `status` enum('DELETED','EXPIRED','FAILED','GENERATING','READY','SAVED') NOT NULL,
  `transport` enum('PUBLIC_TRANSIT','RENTAL_CAR','TAXI','WALK_BIKE') NOT NULL,
  PRIMARY KEY (`id`),
  KEY `idx_courses_user_status_saved` (`user_id`,`status`,`saved_at`),
  KEY `idx_courses_type_status` (`course_type`,`status`),
  KEY `fk_courses_preset` (`preset_id`),
  CONSTRAINT `fk_courses_preset` FOREIGN KEY (`preset_id`) REFERENCES `course_presets` (`id`) ON DELETE SET NULL,
  CONSTRAINT `fk_courses_user` FOREIGN KEY (`user_id`) REFERENCES `users` (`id`) ON DELETE SET NULL
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- 내보낼 데이터가 선택되어 있지 않습니다.

-- 테이블 hangat_schema_gen.data_sources 구조 내보내기
CREATE TABLE `data_sources` (
  `display_order` smallint(6) NOT NULL,
  `is_active` tinyint(1) NOT NULL,
  `created_at` datetime(6) NOT NULL,
  `updated_at` datetime(6) NOT NULL,
  `code` varchar(30) NOT NULL,
  `display_name` varchar(100) NOT NULL,
  `license_name` varchar(100) DEFAULT NULL,
  `provider_name` varchar(100) NOT NULL,
  `attribution_text` varchar(300) NOT NULL,
  `disclaimer_text` varchar(500) DEFAULT NULL,
  `api_url` varchar(1000) DEFAULT NULL,
  `homepage_url` varchar(1000) DEFAULT NULL,
  `license_url` varchar(1000) DEFAULT NULL,
  PRIMARY KEY (`code`),
  UNIQUE KEY `uk_data_sources_display_name` (`display_name`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- 내보낼 데이터가 선택되어 있지 않습니다.

-- 테이블 hangat_schema_gen.email_verification_tokens 구조 내보내기
CREATE TABLE `email_verification_tokens` (
  `created_at` datetime(6) NOT NULL,
  `expires_at` datetime(6) NOT NULL,
  `id` bigint(20) NOT NULL AUTO_INCREMENT,
  `used_at` datetime(6) DEFAULT NULL,
  `user_id` bigint(20) NOT NULL,
  `token_hash` varchar(64) NOT NULL,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_evt_token_hash` (`token_hash`),
  KEY `idx_evt_user_used` (`user_id`,`used_at`),
  KEY `idx_evt_expires_at` (`expires_at`),
  CONSTRAINT `FKi1c4mmamlb8keqt74k4lrtwhc` FOREIGN KEY (`user_id`) REFERENCES `users` (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- 내보낼 데이터가 선택되어 있지 않습니다.

-- 테이블 hangat_schema_gen.oauth_login_flows 구조 내보내기
CREATE TABLE `oauth_login_flows` (
  `attempt_count` int(11) NOT NULL,
  `code_expires_at` datetime(6) DEFAULT NULL,
  `consumed_at` datetime(6) DEFAULT NULL,
  `created_at` datetime(6) NOT NULL,
  `email_verified_at` datetime(6) DEFAULT NULL,
  `expires_at` datetime(6) NOT NULL,
  `id` bigint(20) NOT NULL AUTO_INCREMENT,
  `target_user_id` bigint(20) DEFAULT NULL,
  `updated_at` datetime(6) NOT NULL,
  `nickname` varchar(50) DEFAULT NULL,
  `code_hash` varchar(64) DEFAULT NULL,
  `flow_token_hash` varchar(64) NOT NULL,
  `provider_email` varchar(255) DEFAULT NULL,
  `provider_uid` varchar(255) NOT NULL,
  `target_email` varchar(255) DEFAULT NULL,
  `code_purpose` enum('OAUTH_LINK','OAUTH_SIGNUP','PASSWORD_RESET') DEFAULT NULL,
  `provider` enum('GOOGLE','KAKAO') NOT NULL,
  `step` enum('CANCELLED','CODE_REQUIRED','COMPLETED','LINK_CONFIRMATION','PROFILE_REQUIRED','VERIFIED_LINK_CONFIRMATION') NOT NULL,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_olf_flow_token_hash` (`flow_token_hash`),
  KEY `idx_olf_provider_uid` (`provider`,`provider_uid`),
  KEY `idx_olf_expires_at` (`expires_at`),
  KEY `idx_olf_consumed_at` (`consumed_at`),
  KEY `FKodbmahv64q90gvqc1vlunpmun` (`target_user_id`),
  CONSTRAINT `FKodbmahv64q90gvqc1vlunpmun` FOREIGN KEY (`target_user_id`) REFERENCES `users` (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- 내보낼 데이터가 선택되어 있지 않습니다.

-- 테이블 hangat_schema_gen.password_reset_requests 구조 내보내기
CREATE TABLE `password_reset_requests` (
  `attempt_count` int(11) NOT NULL,
  `created_at` datetime(6) NOT NULL,
  `expires_at` datetime(6) NOT NULL,
  `id` bigint(20) NOT NULL AUTO_INCREMENT,
  `ticket_expires_at` datetime(6) DEFAULT NULL,
  `used_at` datetime(6) DEFAULT NULL,
  `user_id` bigint(20) NOT NULL,
  `verified_at` datetime(6) DEFAULT NULL,
  `request_id` varchar(32) NOT NULL,
  `requester_ip` varchar(45) DEFAULT NULL,
  `ticket_hash` varchar(64) DEFAULT NULL,
  `token_hash` varchar(64) NOT NULL,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_prr_request_id` (`request_id`),
  UNIQUE KEY `uk_prr_ticket_hash` (`ticket_hash`),
  KEY `idx_prr_user_used` (`user_id`,`used_at`),
  KEY `idx_prr_expires_at` (`expires_at`),
  CONSTRAINT `FK1xtvwnh0xfmemjmmgamr3y83f` FOREIGN KEY (`user_id`) REFERENCES `users` (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- 내보낼 데이터가 선택되어 있지 않습니다.

-- 테이블 hangat_schema_gen.place_categories 구조 내보내기
CREATE TABLE `place_categories` (
  `display_order` smallint(6) NOT NULL,
  `id` smallint(6) NOT NULL AUTO_INCREMENT,
  `is_active` tinyint(1) NOT NULL,
  `created_at` datetime(6) NOT NULL,
  `updated_at` datetime(6) NOT NULL,
  `code` varchar(30) NOT NULL,
  `name` varchar(50) NOT NULL,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_place_categories_code` (`code`),
  UNIQUE KEY `uk_place_categories_name` (`name`)
) ENGINE=InnoDB AUTO_INCREMENT=8 DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- 내보낼 데이터가 선택되어 있지 않습니다.

-- 테이블 hangat_schema_gen.place_images 구조 내보내기
CREATE TABLE `place_images` (
  `is_primary` bit(1) NOT NULL,
  `sort_order` int(11) NOT NULL,
  `created_at` datetime(6) NOT NULL,
  `id` bigint(20) NOT NULL AUTO_INCREMENT,
  `place_id` bigint(20) NOT NULL,
  `source_mapping_id` bigint(20) DEFAULT NULL,
  `license_code` varchar(20) DEFAULT NULL,
  `url_hash` varchar(64) NOT NULL,
  `attribution` varchar(100) DEFAULT NULL,
  `caption` varchar(200) DEFAULT NULL,
  `image_url` varchar(500) NOT NULL,
  `thumbnail_url` varchar(500) DEFAULT NULL,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_place_images_url` (`place_id`,`url_hash`),
  UNIQUE KEY `uk_place_images_order` (`place_id`,`sort_order`),
  KEY `FKdw2frlswiiu0jhbwmtqqq6e8o` (`source_mapping_id`),
  CONSTRAINT `FKdw2frlswiiu0jhbwmtqqq6e8o` FOREIGN KEY (`source_mapping_id`) REFERENCES `place_source_mappings` (`id`) ON DELETE SET NULL,
  CONSTRAINT `FKfaf8mjrs1svk7fgqxufaajmiq` FOREIGN KEY (`place_id`) REFERENCES `places` (`id`) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- 내보낼 데이터가 선택되어 있지 않습니다.

-- 테이블 hangat_schema_gen.place_source_mappings 구조 내보내기
CREATE TABLE `place_source_mappings` (
  `is_active` tinyint(1) NOT NULL,
  `created_at` datetime(6) NOT NULL,
  `id` bigint(20) NOT NULL AUTO_INCREMENT,
  `last_synced_at` datetime(6) NOT NULL,
  `place_id` bigint(20) NOT NULL,
  `source_updated_at` datetime(6) DEFAULT NULL,
  `updated_at` datetime(6) NOT NULL,
  `source_code` varchar(30) NOT NULL,
  `data_hash` varchar(64) DEFAULT NULL,
  `source_place_id` varchar(100) NOT NULL,
  `source_url` varchar(1000) DEFAULT NULL,
  `raw_payload` text DEFAULT NULL,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_place_source_mappings_source` (`source_code`,`source_place_id`),
  KEY `FK4vmoug1ufqlxtegir4v6uthmn` (`place_id`),
  CONSTRAINT `FK4vmoug1ufqlxtegir4v6uthmn` FOREIGN KEY (`place_id`) REFERENCES `places` (`id`),
  CONSTRAINT `FKd982xu99hgc103pbheusvouu5` FOREIGN KEY (`source_code`) REFERENCES `data_sources` (`code`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- 내보낼 데이터가 선택되어 있지 않습니다.

-- 테이블 hangat_schema_gen.place_tags 구조 내보내기
CREATE TABLE `place_tags` (
  `tag_id` smallint(6) NOT NULL,
  `weight` decimal(5,4) NOT NULL,
  `place_id` bigint(20) NOT NULL,
  `updated_at` datetime(6) NOT NULL,
  `source_type` enum('ADMIN','API','MODEL','REVIEW') NOT NULL,
  PRIMARY KEY (`tag_id`,`place_id`),
  KEY `fk_place_tags_place` (`place_id`),
  CONSTRAINT `fk_place_tags_place` FOREIGN KEY (`place_id`) REFERENCES `places` (`id`),
  CONSTRAINT `fk_place_tags_tag` FOREIGN KEY (`tag_id`) REFERENCES `tags` (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- 내보낼 데이터가 선택되어 있지 않습니다.

-- 테이블 hangat_schema_gen.places 구조 내보내기
CREATE TABLE `places` (
  `good_price_base_date` date DEFAULT NULL,
  `hidden_gem_score` decimal(6,3) DEFAULT NULL,
  `is_good_price` tinyint(1) NOT NULL,
  `is_hidden_gem` tinyint(1) NOT NULL,
  `latitude` decimal(10,7) DEFAULT NULL,
  `longitude` decimal(10,7) DEFAULT NULL,
  `parking_available` tinyint(1) DEFAULT NULL,
  `primary_category_id` smallint(6) NOT NULL,
  `rating_avg` decimal(3,2) NOT NULL,
  `region_id` smallint(6) NOT NULL,
  `review_count` int(11) NOT NULL,
  `toilet_available` tinyint(1) DEFAULT NULL,
  `created_at` datetime(6) NOT NULL,
  `hidden_gem_calculated_at` datetime(6) DEFAULT NULL,
  `id` bigint(20) NOT NULL AUTO_INCREMENT,
  `updated_at` datetime(6) NOT NULL,
  `hidden_gem_algorithm_version` varchar(30) DEFAULT NULL,
  `phone` varchar(30) DEFAULT NULL,
  `name` varchar(200) NOT NULL,
  `normalized_name` varchar(200) NOT NULL,
  `lot_address` varchar(300) DEFAULT NULL,
  `rest_day_text` varchar(300) DEFAULT NULL,
  `road_address` varchar(300) DEFAULT NULL,
  `image_url` varchar(500) DEFAULT NULL,
  `operating_hours_text` varchar(500) DEFAULT NULL,
  `use_fee_text` varchar(1000) DEFAULT NULL,
  `overview` text DEFAULT NULL,
  `business_status` enum('CLOSED','OPEN','TEMP_CLOSED','UNKNOWN') NOT NULL,
  PRIMARY KEY (`id`),
  KEY `FKsic9b3be5q0a38vb3aii7yfhs` (`primary_category_id`),
  KEY `FK2lkjoxuwr4yu8n2asamaimeug` (`region_id`),
  CONSTRAINT `FK2lkjoxuwr4yu8n2asamaimeug` FOREIGN KEY (`region_id`) REFERENCES `regions` (`id`),
  CONSTRAINT `FKsic9b3be5q0a38vb3aii7yfhs` FOREIGN KEY (`primary_category_id`) REFERENCES `place_categories` (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- 내보낼 데이터가 선택되어 있지 않습니다.

-- 테이블 hangat_schema_gen.refresh_tokens 구조 내보내기
CREATE TABLE `refresh_tokens` (
  `created_at` datetime(6) NOT NULL,
  `expires_at` datetime(6) NOT NULL,
  `id` bigint(20) NOT NULL AUTO_INCREMENT,
  `last_used_at` datetime(6) DEFAULT NULL,
  `revoked_at` datetime(6) DEFAULT NULL,
  `user_id` bigint(20) NOT NULL,
  `ip_address` varchar(45) DEFAULT NULL,
  `token_hash` varchar(64) NOT NULL,
  `device_label` varchar(100) DEFAULT NULL,
  `user_agent` varchar(500) DEFAULT NULL,
  `revoked_reason` enum('LOGOUT','PASSWORD_RESET','REUSE_DETECTED','ROTATED','SUSPENDED') DEFAULT NULL,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_rt_token_hash` (`token_hash`),
  KEY `idx_rt_user_revoked_expires` (`user_id`,`revoked_at`,`expires_at`),
  KEY `idx_rt_expires_at` (`expires_at`),
  KEY `idx_rt_revoked_at` (`revoked_at`),
  CONSTRAINT `FK1lih5y2npsf8u5o3vhdb9y0os` FOREIGN KEY (`user_id`) REFERENCES `users` (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- 내보낼 데이터가 선택되어 있지 않습니다.

-- 테이블 hangat_schema_gen.regions 구조 내보내기
CREATE TABLE `regions` (
  `center_lat` decimal(10,7) DEFAULT NULL,
  `center_lng` decimal(10,7) DEFAULT NULL,
  `display_order` tinyint(4) NOT NULL,
  `id` smallint(6) NOT NULL AUTO_INCREMENT,
  `is_active` tinyint(1) NOT NULL,
  `kma_grid_x` smallint(6) DEFAULT NULL,
  `kma_grid_y` smallint(6) DEFAULT NULL,
  `created_at` datetime(6) NOT NULL,
  `updated_at` datetime(6) NOT NULL,
  `code` varchar(20) NOT NULL,
  `name` varchar(30) NOT NULL,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_regions_code` (`code`),
  UNIQUE KEY `uk_regions_name` (`name`),
  UNIQUE KEY `uk_regions_display_order` (`display_order`)
) ENGINE=InnoDB AUTO_INCREMENT=5 DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- 내보낼 데이터가 선택되어 있지 않습니다.

-- 테이블 hangat_schema_gen.review_images 구조 내보내기
CREATE TABLE `review_images` (
  `sort_order` int(11) NOT NULL,
  `created_at` datetime(6) NOT NULL,
  `id` bigint(20) NOT NULL AUTO_INCREMENT,
  `review_id` bigint(20) NOT NULL,
  `storage_key` varchar(200) NOT NULL,
  `image_url` varchar(500) NOT NULL,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_review_images_key` (`storage_key`),
  KEY `FK3aayo5bjciyemf3bvvt987hkr` (`review_id`),
  CONSTRAINT `FK3aayo5bjciyemf3bvvt987hkr` FOREIGN KEY (`review_id`) REFERENCES `reviews` (`id`) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- 내보낼 데이터가 선택되어 있지 않습니다.

-- 테이블 hangat_schema_gen.reviews 구조 내보내기
CREATE TABLE `reviews` (
  `rating` tinyint(4) DEFAULT NULL,
  `created_at` datetime(6) NOT NULL,
  `deleted_at` datetime(6) DEFAULT NULL,
  `id` bigint(20) NOT NULL AUTO_INCREMENT,
  `place_id` bigint(20) NOT NULL,
  `updated_at` datetime(6) NOT NULL,
  `user_id` bigint(20) NOT NULL,
  `content` varchar(60) DEFAULT NULL,
  `congestion_report` enum('CROWDED','NORMAL','QUIET') DEFAULT NULL,
  `status` enum('ACTIVE','DELETED') NOT NULL,
  PRIMARY KEY (`id`),
  KEY `idx_reviews_place_status` (`place_id`,`status`,`created_at`),
  CONSTRAINT `FKcnnkvk0h9vrih5xqxef7sv2r6` FOREIGN KEY (`place_id`) REFERENCES `places` (`id`) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- 내보낼 데이터가 선택되어 있지 않습니다.

-- 테이블 hangat_schema_gen.tags 구조 내보내기
CREATE TABLE `tags` (
  `id` smallint(6) NOT NULL AUTO_INCREMENT,
  `is_active` tinyint(1) NOT NULL,
  `created_at` datetime(6) NOT NULL,
  `updated_at` datetime(6) NOT NULL,
  `code` varchar(30) NOT NULL,
  `name` varchar(50) NOT NULL,
  `description` varchar(200) DEFAULT NULL,
  `tag_type` enum('BOTH','PLACE','STYLE') NOT NULL,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_tags_code` (`code`),
  UNIQUE KEY `uk_tags_name` (`name`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- 내보낼 데이터가 선택되어 있지 않습니다.

-- 테이블 hangat_schema_gen.user_social_accounts 구조 내보내기
CREATE TABLE `user_social_accounts` (
  `created_at` datetime(6) NOT NULL,
  `id` bigint(20) NOT NULL AUTO_INCREMENT,
  `linked_at` datetime(6) NOT NULL,
  `user_id` bigint(20) NOT NULL,
  `email` varchar(255) DEFAULT NULL,
  `provider_uid` varchar(255) NOT NULL,
  `provider` enum('GOOGLE','KAKAO') NOT NULL,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_usa_provider_uid` (`provider`,`provider_uid`),
  UNIQUE KEY `uk_usa_user_provider` (`user_id`,`provider`),
  KEY `idx_usa_user_id` (`user_id`),
  CONSTRAINT `FKbgx256ax3u7afnmixgorqa7im` FOREIGN KEY (`user_id`) REFERENCES `users` (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- 내보낼 데이터가 선택되어 있지 않습니다.

-- 테이블 hangat_schema_gen.users 구조 내보내기
CREATE TABLE `users` (
  `birth_date` date DEFAULT NULL,
  `created_at` datetime(6) NOT NULL,
  `email_verified_at` datetime(6) DEFAULT NULL,
  `id` bigint(20) NOT NULL AUTO_INCREMENT,
  `last_login_at` datetime(6) DEFAULT NULL,
  `password_changed_at` datetime(6) DEFAULT NULL,
  `updated_at` datetime(6) NOT NULL,
  `withdrawn_at` datetime(6) DEFAULT NULL,
  `nickname` varchar(50) NOT NULL,
  `email` varchar(255) NOT NULL,
  `password` varchar(255) DEFAULT NULL,
  `status` enum('ACTIVE','PENDING','SUSPENDED','WITHDRAWN') NOT NULL,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_users_email` (`email`),
  UNIQUE KEY `uk_users_nickname` (`nickname`),
  KEY `idx_users_status` (`status`),
  KEY `idx_users_last_login_at` (`last_login_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- 내보낼 데이터가 선택되어 있지 않습니다.

/*!40103 SET TIME_ZONE=IFNULL(@OLD_TIME_ZONE, 'system') */;
/*!40101 SET SQL_MODE=IFNULL(@OLD_SQL_MODE, '') */;
/*!40014 SET FOREIGN_KEY_CHECKS=IFNULL(@OLD_FOREIGN_KEY_CHECKS, 1) */;
/*!40101 SET CHARACTER_SET_CLIENT=@OLD_CHARACTER_SET_CLIENT */;
/*!40111 SET SQL_NOTES=IFNULL(@OLD_SQL_NOTES, 1) */;
