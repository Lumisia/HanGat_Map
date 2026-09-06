-- Hangat local development database reset (MariaDB 10.6)
-- Destructive by design: this script is only for the user-owned `web` catalog.
-- It intentionally recreates only the Course persistence tables listed below.

USE `web`;

SET @HANGAT_OLD_FOREIGN_KEY_CHECKS = @@FOREIGN_KEY_CHECKS;
SET FOREIGN_KEY_CHECKS = 0;

DROP TABLE IF EXISTS `course_items`;
DROP TABLE IF EXISTS `courses`;
DROP TABLE IF EXISTS `place_source_mappings`;
DROP TABLE IF EXISTS `places`;
DROP TABLE IF EXISTS `place_categories`;
DROP TABLE IF EXISTS `regions`;
DROP TABLE IF EXISTS `data_sources`;

SET FOREIGN_KEY_CHECKS = @HANGAT_OLD_FOREIGN_KEY_CHECKS;

CREATE TABLE `regions` (
    `id` SMALLINT UNSIGNED NOT NULL AUTO_INCREMENT,
    `code` VARCHAR(20) NOT NULL,
    `name` VARCHAR(30) NOT NULL,
    `center_lat` DECIMAL(10,7) NULL DEFAULT NULL,
    `center_lng` DECIMAL(10,7) NULL DEFAULT NULL,
    `kma_grid_x` SMALLINT UNSIGNED NULL DEFAULT NULL,
    `kma_grid_y` SMALLINT UNSIGNED NULL DEFAULT NULL,
    `display_order` TINYINT UNSIGNED NOT NULL DEFAULT 1,
    `is_active` BOOLEAN NOT NULL DEFAULT 1,
    `created_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    `updated_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    CONSTRAINT `pk_regions` PRIMARY KEY (`id`),
    CONSTRAINT `uk_regions_code` UNIQUE (`code`),
    CONSTRAINT `uk_regions_name` UNIQUE (`name`),
    CONSTRAINT `uk_regions_display_order` UNIQUE (`display_order`),
    CONSTRAINT `chk_regions_center_lat`
        CHECK (`center_lat` BETWEEN -90 AND 90 OR `center_lat` IS NULL),
    CONSTRAINT `chk_regions_center_lng`
        CHECK (`center_lng` BETWEEN -180 AND 180 OR `center_lng` IS NULL),
    CONSTRAINT `chk_regions_active` CHECK (`is_active` IN (0, 1))
) ENGINE=InnoDB;

CREATE TABLE `place_categories` (
    `id` SMALLINT UNSIGNED NOT NULL AUTO_INCREMENT,
    `code` VARCHAR(30) NOT NULL,
    `name` VARCHAR(50) NOT NULL,
    `display_order` SMALLINT UNSIGNED NOT NULL DEFAULT 1,
    `is_active` BOOLEAN NOT NULL DEFAULT 1,
    `created_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    `updated_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    CONSTRAINT `pk_place_categories` PRIMARY KEY (`id`),
    CONSTRAINT `uk_place_categories_code` UNIQUE (`code`),
    CONSTRAINT `uk_place_categories_name` UNIQUE (`name`),
    CONSTRAINT `chk_place_categories_active` CHECK (`is_active` IN (0, 1)),
    INDEX `idx_place_categories_display_order` (`display_order`)
) ENGINE=InnoDB;

CREATE TABLE `data_sources` (
    `code` VARCHAR(30) NOT NULL,
    `display_name` VARCHAR(100) NOT NULL,
    `provider_name` VARCHAR(100) NOT NULL,
    `homepage_url` VARCHAR(1000) NULL DEFAULT NULL,
    `api_url` VARCHAR(1000) NULL DEFAULT NULL,
    `license_name` VARCHAR(100) NULL DEFAULT NULL,
    `license_url` VARCHAR(1000) NULL DEFAULT NULL,
    `attribution_text` VARCHAR(300) NOT NULL,
    `disclaimer_text` VARCHAR(500) NULL DEFAULT NULL,
    `display_order` SMALLINT UNSIGNED NOT NULL DEFAULT 1,
    `is_active` BOOLEAN NOT NULL DEFAULT 1,
    `created_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    `updated_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    CONSTRAINT `pk_data_sources` PRIMARY KEY (`code`),
    CONSTRAINT `uk_data_sources_display_name` UNIQUE (`display_name`),
    CONSTRAINT `chk_data_sources_active` CHECK (`is_active` IN (0, 1)),
    INDEX `idx_data_sources_provider_name` (`provider_name`),
    INDEX `idx_data_sources_display_order` (`display_order`)
) ENGINE=InnoDB;

CREATE TABLE `places` (
    `id` BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    `region_id` SMALLINT UNSIGNED NOT NULL,
    `primary_category_id` SMALLINT UNSIGNED NOT NULL,
    `name` VARCHAR(200) NOT NULL,
    `normalized_name` VARCHAR(200) NOT NULL,
    `road_address` VARCHAR(300) NULL DEFAULT NULL,
    `lot_address` VARCHAR(300) NULL DEFAULT NULL,
    `latitude` DECIMAL(10,7) NULL DEFAULT NULL,
    `longitude` DECIMAL(10,7) NULL DEFAULT NULL,
    `overview` TEXT NULL,
    `phone` VARCHAR(30) NULL DEFAULT NULL,
    `operating_hours_text` VARCHAR(500) NULL DEFAULT NULL,
    `rest_day_text` VARCHAR(300) NULL DEFAULT NULL,
    `parking_available` BOOLEAN NULL DEFAULT NULL,
    `toilet_available` BOOLEAN NULL DEFAULT NULL,
    `business_status` ENUM('OPEN','TEMP_CLOSED','CLOSED','UNKNOWN') NOT NULL DEFAULT 'UNKNOWN',
    `is_good_price` BOOLEAN NOT NULL DEFAULT 0,
    `good_price_base_date` DATE NULL DEFAULT NULL,
    `is_hidden_gem` BOOLEAN NOT NULL DEFAULT 0,
    `hidden_gem_score` DECIMAL(6,3) NULL DEFAULT NULL,
    `hidden_gem_algorithm_version` VARCHAR(30) NULL DEFAULT NULL,
    `hidden_gem_calculated_at` DATETIME NULL DEFAULT NULL,
    `rating_avg` DECIMAL(3,2) NOT NULL DEFAULT 0.00,
    `review_count` INT UNSIGNED NOT NULL DEFAULT 0,
    `created_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    `updated_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    CONSTRAINT `pk_places` PRIMARY KEY (`id`),
    CONSTRAINT `fk_places_region`
        FOREIGN KEY (`region_id`) REFERENCES `regions` (`id`)
        ON DELETE RESTRICT ON UPDATE RESTRICT,
    CONSTRAINT `fk_places_primary_category`
        FOREIGN KEY (`primary_category_id`) REFERENCES `place_categories` (`id`)
        ON DELETE RESTRICT ON UPDATE RESTRICT,
    CONSTRAINT `chk_places_latitude`
        CHECK (`latitude` BETWEEN -90 AND 90 OR `latitude` IS NULL),
    CONSTRAINT `chk_places_longitude`
        CHECK (`longitude` BETWEEN -180 AND 180 OR `longitude` IS NULL),
    CONSTRAINT `chk_places_parking`
        CHECK (`parking_available` IN (0, 1) OR `parking_available` IS NULL),
    CONSTRAINT `chk_places_toilet`
        CHECK (`toilet_available` IN (0, 1) OR `toilet_available` IS NULL),
    CONSTRAINT `chk_places_good_price` CHECK (`is_good_price` IN (0, 1)),
    CONSTRAINT `chk_places_hidden_gem` CHECK (`is_hidden_gem` IN (0, 1)),
    CONSTRAINT `chk_places_hidden_gem_score`
        CHECK (`hidden_gem_score` BETWEEN 0 AND 100 OR `hidden_gem_score` IS NULL),
    CONSTRAINT `chk_places_hidden_gem_metadata`
        CHECK (`hidden_gem_score` IS NULL OR
            (`hidden_gem_algorithm_version` IS NOT NULL AND `hidden_gem_calculated_at` IS NOT NULL)),
    CONSTRAINT `chk_places_rating` CHECK (`rating_avg` BETWEEN 0 AND 5),
    INDEX `idx_places_region` (`region_id`),
    INDEX `idx_places_primary_category` (`primary_category_id`),
    INDEX `idx_places_region_category` (`region_id`, `primary_category_id`),
    INDEX `idx_places_name` (`name`),
    INDEX `idx_places_normalized_name` (`normalized_name`),
    INDEX `idx_places_coordinates` (`latitude`, `longitude`),
    INDEX `idx_places_business_status` (`business_status`),
    INDEX `idx_places_good_price` (`is_good_price`),
    INDEX `idx_places_hidden_gem` (`is_hidden_gem`, `hidden_gem_score`),
    INDEX `idx_places_hidden_gem_algorithm` (`hidden_gem_algorithm_version`)
) ENGINE=InnoDB;

CREATE TABLE `place_source_mappings` (
    `id` BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    `place_id` BIGINT UNSIGNED NOT NULL,
    `source_code` VARCHAR(30) NOT NULL,
    `source_place_id` VARCHAR(100) NOT NULL,
    `source_url` VARCHAR(1000) NULL DEFAULT NULL,
    `source_updated_at` DATETIME NULL DEFAULT NULL,
    `last_synced_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    `data_hash` CHAR(64) NULL DEFAULT NULL,
    `raw_payload` JSON NULL,
    `is_active` BOOLEAN NOT NULL DEFAULT 1,
    `created_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    `updated_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    CONSTRAINT `pk_place_source_mappings` PRIMARY KEY (`id`),
    CONSTRAINT `uk_place_source_identity` UNIQUE (`source_code`, `source_place_id`),
    CONSTRAINT `fk_place_source_mappings_place`
        FOREIGN KEY (`place_id`) REFERENCES `places` (`id`)
        ON DELETE CASCADE ON UPDATE RESTRICT,
    CONSTRAINT `fk_place_source_mappings_source`
        FOREIGN KEY (`source_code`) REFERENCES `data_sources` (`code`)
        ON DELETE RESTRICT ON UPDATE RESTRICT,
    CONSTRAINT `chk_place_source_mappings_active` CHECK (`is_active` IN (0, 1)),
    INDEX `idx_place_source_mappings_place` (`place_id`),
    INDEX `idx_place_source_mappings_source_active` (`source_code`, `is_active`),
    INDEX `idx_place_source_mappings_last_synced` (`last_synced_at`)
) ENGINE=InnoDB;

CREATE TABLE `courses` (
    `id` BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    `user_id` BIGINT UNSIGNED NULL DEFAULT NULL,
    `preset_id` BIGINT UNSIGNED NULL DEFAULT NULL,
    `accommodation_source_mapping_id` BIGINT UNSIGNED NULL DEFAULT NULL,
    `parent_course_id` BIGINT UNSIGNED NULL DEFAULT NULL,
    `course_type` ENUM('USER','SAMPLE') NOT NULL DEFAULT 'USER',
    `generation_reason` ENUM('INITIAL','USER_REGENERATE','WEATHER_REPLAN','SAMPLE_BATCH') NOT NULL DEFAULT 'INITIAL',
    `status` ENUM('GENERATING','READY','SAVED','FAILED','EXPIRED','DELETED') NOT NULL DEFAULT 'GENERATING',
    `title` VARCHAR(100) NULL DEFAULT NULL,
    `start_date` DATE NOT NULL,
    `end_date` DATE NOT NULL,
    `people` SMALLINT UNSIGNED NOT NULL DEFAULT 1,
    `budget_total` INT UNSIGNED NULL DEFAULT NULL,
    `transport` ENUM('RENTAL_CAR','PUBLIC_TRANSIT','TAXI','WALK_BIKE') NOT NULL,
    `input_fingerprint` CHAR(64) NULL DEFAULT NULL,
    `algorithm_version` VARCHAR(30) NULL DEFAULT NULL,
    `estimated_cost_min` INT UNSIGNED NULL DEFAULT NULL,
    `estimated_cost_max` INT UNSIGNED NULL DEFAULT NULL,
    `average_congestion_rate` DECIMAL(5,2) NULL DEFAULT NULL,
    `generation_error_code` VARCHAR(50) NULL DEFAULT NULL,
    `generation_completed_at` DATETIME NULL DEFAULT NULL,
    `saved_at` DATETIME NULL DEFAULT NULL,
    `deleted_at` DATETIME NULL DEFAULT NULL,
    `created_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    `updated_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    CONSTRAINT `pk_courses` PRIMARY KEY (`id`),
    CONSTRAINT `fk_courses_parent`
        FOREIGN KEY (`parent_course_id`) REFERENCES `courses` (`id`)
        ON DELETE SET NULL ON UPDATE RESTRICT,
    CONSTRAINT `fk_courses_accommodation_source_mapping`
        FOREIGN KEY (`accommodation_source_mapping_id`) REFERENCES `place_source_mappings` (`id`)
        ON DELETE RESTRICT ON UPDATE RESTRICT,
    CONSTRAINT `chk_courses_sample_shape`
        CHECK (`course_type` <> 'SAMPLE' OR
            (`preset_id` IS NOT NULL AND `user_id` IS NULL AND `title` IS NOT NULL)),
    CONSTRAINT `chk_courses_saved_shape`
        CHECK (`status` <> 'SAVED' OR
            (`user_id` IS NOT NULL AND `title` IS NOT NULL
                AND CHAR_LENGTH(TRIM(`title`)) >= 1 AND `saved_at` IS NOT NULL)),
    CONSTRAINT `chk_courses_title`
        CHECK (`title` IS NULL OR CHAR_LENGTH(TRIM(`title`)) BETWEEN 1 AND 100),
    CONSTRAINT `chk_courses_date_range` CHECK (`start_date` <= `end_date`),
    CONSTRAINT `chk_courses_people` CHECK (`people` BETWEEN 1 AND 100),
    CONSTRAINT `chk_courses_cost_range`
        CHECK (`estimated_cost_min` IS NULL OR `estimated_cost_max` IS NULL
            OR `estimated_cost_min` <= `estimated_cost_max`),
    CONSTRAINT `chk_courses_congestion`
        CHECK (`average_congestion_rate` BETWEEN 0 AND 100 OR `average_congestion_rate` IS NULL),
    CONSTRAINT `chk_courses_deleted_shape`
        CHECK ((`status` = 'DELETED' AND `deleted_at` IS NOT NULL)
            OR (`status` <> 'DELETED' AND `deleted_at` IS NULL)),
    INDEX `idx_courses_user_status_saved` (`user_id`, `status`, `saved_at`),
    INDEX `idx_courses_preset` (`preset_id`),
    INDEX `idx_courses_accommodation_source_mapping` (`accommodation_source_mapping_id`),
    INDEX `idx_courses_parent` (`parent_course_id`),
    INDEX `idx_courses_course_type_status` (`course_type`, `status`),
    INDEX `idx_courses_status` (`status`),
    INDEX `idx_courses_title` (`title`),
    INDEX `idx_courses_user_status_start` (`user_id`, `status`, `start_date`),
    INDEX `idx_courses_input_fingerprint` (`input_fingerprint`),
    INDEX `idx_courses_algorithm_version` (`algorithm_version`),
    INDEX `idx_courses_saved_at` (`saved_at`),
    INDEX `idx_courses_deleted_at` (`deleted_at`)
) ENGINE=InnoDB;

CREATE TABLE `course_items` (
    `id` BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    `course_id` BIGINT UNSIGNED NOT NULL,
    `place_id` BIGINT UNSIGNED NOT NULL,
    `day_no` SMALLINT UNSIGNED NOT NULL,
    `position` SMALLINT UNSIGNED NOT NULL,
    `visit_date` DATE NOT NULL,
    `start_time` TIME NULL DEFAULT NULL,
    `end_time` TIME NULL DEFAULT NULL,
    `item_source` ENUM('USER_FIXED','AI_RECOMMENDED','REPLACEMENT') NOT NULL DEFAULT 'AI_RECOMMENDED',
    `inbound_distance_m` INT UNSIGNED NULL DEFAULT NULL,
    `inbound_travel_minutes` SMALLINT UNSIGNED NULL DEFAULT NULL,
    `planned_congestion_forecast_id` BIGINT UNSIGNED NULL DEFAULT NULL,
    `planned_weather_forecast_id` BIGINT UNSIGNED NULL DEFAULT NULL,
    `recommendation_score` DECIMAL(8,4) NULL DEFAULT NULL,
    `recommendation_reason_code` VARCHAR(30) NULL DEFAULT NULL,
    `recommendation_reason` VARCHAR(300) NULL DEFAULT NULL,
    `replaced_from_place_id` BIGINT UNSIGNED NULL DEFAULT NULL,
    `memo` VARCHAR(300) NULL DEFAULT NULL,
    `created_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    `updated_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    CONSTRAINT `pk_course_items` PRIMARY KEY (`id`),
    CONSTRAINT `uk_course_day_position` UNIQUE (`course_id`, `day_no`, `position`),
    CONSTRAINT `fk_course_items_course`
        FOREIGN KEY (`course_id`) REFERENCES `courses` (`id`)
        ON DELETE CASCADE ON UPDATE RESTRICT,
    CONSTRAINT `fk_course_items_place`
        FOREIGN KEY (`place_id`) REFERENCES `places` (`id`)
        ON DELETE RESTRICT ON UPDATE RESTRICT,
    CONSTRAINT `fk_course_items_replaced_from_place`
        FOREIGN KEY (`replaced_from_place_id`) REFERENCES `places` (`id`)
        ON DELETE SET NULL ON UPDATE RESTRICT,
    CONSTRAINT `chk_course_items_day_no` CHECK (`day_no` >= 1),
    CONSTRAINT `chk_course_items_position` CHECK (`position` >= 1),
    CONSTRAINT `chk_course_items_time`
        CHECK (`start_time` IS NULL OR `end_time` IS NULL OR `start_time` < `end_time`),
    INDEX `idx_course_items_course_visit` (`course_id`, `visit_date`),
    INDEX `idx_course_items_place` (`place_id`),
    INDEX `idx_course_items_visit_date` (`visit_date`)
) ENGINE=InnoDB;

-- Deferred until the referenced domain tables are part of the active schema:
-- courses.user_id -> users.id
-- courses.preset_id -> course_presets.id
-- course_items.planned_congestion_forecast_id -> congestion_forecasts.id
-- course_items.planned_weather_forecast_id -> weather_forecasts.id
