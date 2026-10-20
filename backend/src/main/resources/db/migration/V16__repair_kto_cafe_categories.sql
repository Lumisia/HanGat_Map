-- Backfill verified KTO cafe categories. No source payload or personal data is needed.
UPDATE places
SET primary_category_id = (SELECT id FROM place_categories WHERE code = 'CAFE')
WHERE primary_category_id = (SELECT id FROM place_categories WHERE code = 'FOOD')
  AND EXISTS (SELECT 1 FROM place_categories WHERE code = 'CAFE')
  AND EXISTS (
    SELECT 1 FROM place_source_mappings m
    WHERE m.place_id = places.id AND m.source_code = 'KTO' AND m.is_active = true
  )
  AND EXISTS (
    SELECT 1 FROM place_tags pt JOIN tags t ON t.id = pt.tag_id
    WHERE pt.place_id = places.id AND pt.source_type = 'API' AND t.is_active = true
      AND t.code IN ('FD050100', 'FD050200')
  );
