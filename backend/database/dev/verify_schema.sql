-- Read-only verification for the Hangat local development schema.
USE `web`;

SELECT DATABASE() AS target_catalog;

SELECT table_name, table_rows
FROM information_schema.tables
WHERE table_schema = DATABASE()
  AND table_name IN (
      'course_items', 'courses', 'place_source_mappings', 'places',
      'place_categories', 'regions', 'data_sources'
  )
ORDER BY table_name;

SELECT table_name, column_name, column_type, is_nullable, column_default, extra
FROM information_schema.columns
WHERE table_schema = DATABASE()
  AND table_name IN (
      'course_items', 'courses', 'place_source_mappings', 'places',
      'place_categories', 'regions', 'data_sources'
  )
ORDER BY table_name, ordinal_position;

SELECT table_name, constraint_name, constraint_type
FROM information_schema.table_constraints
WHERE constraint_schema = DATABASE()
  AND table_name IN (
      'course_items', 'courses', 'place_source_mappings', 'places',
      'place_categories', 'regions', 'data_sources'
  )
ORDER BY table_name, constraint_type, constraint_name;

SELECT table_name, column_name, referenced_table_name, referenced_column_name
FROM information_schema.key_column_usage
WHERE constraint_schema = DATABASE()
  AND referenced_table_name IS NOT NULL
  AND table_name IN (
      'course_items', 'courses', 'place_source_mappings', 'places',
      'place_categories', 'regions', 'data_sources'
  )
ORDER BY table_name, constraint_name, ordinal_position;

SELECT
    c.column_name,
    c.column_type,
    c.is_nullable,
    k.constraint_name,
    k.referenced_table_name,
    k.referenced_column_name,
    s.index_name
FROM information_schema.columns c
LEFT JOIN information_schema.key_column_usage k
  ON k.constraint_schema = c.table_schema
 AND k.table_name = c.table_name
 AND k.column_name = c.column_name
 AND k.referenced_table_name IS NOT NULL
LEFT JOIN information_schema.statistics s
  ON s.table_schema = c.table_schema
 AND s.table_name = c.table_name
 AND s.column_name = c.column_name
WHERE c.table_schema = DATABASE()
  AND c.table_name = 'courses'
  AND c.column_name = 'accommodation_source_mapping_id'
  AND k.constraint_name = 'fk_courses_accommodation_source_mapping'
  AND k.referenced_table_name = 'place_source_mappings'
  AND k.referenced_column_name = 'id'
  AND s.index_name = 'idx_courses_accommodation_source_mapping';

SELECT code, name, center_lat, center_lng, kma_grid_x, kma_grid_y,
       display_order, is_active
FROM regions
ORDER BY display_order;

SELECT code, name, display_order, is_active
FROM place_categories
ORDER BY display_order;

SELECT code, display_name, provider_name, attribution_text,
       display_order, is_active
FROM data_sources
ORDER BY display_order;
