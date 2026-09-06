-- Persist the selected AI course accommodation by its provider mapping.
-- V1 defines place_source_mappings.id as signed BIGINT, so the child FK must match.

ALTER TABLE courses
    ADD COLUMN accommodation_source_mapping_id BIGINT NULL DEFAULT NULL
        AFTER preset_id,
    ADD INDEX idx_courses_accommodation_source_mapping
        (accommodation_source_mapping_id),
    ADD CONSTRAINT fk_courses_accommodation_source_mapping
        FOREIGN KEY (accommodation_source_mapping_id)
        REFERENCES place_source_mappings (id)
        ON DELETE RESTRICT
        ON UPDATE RESTRICT;
