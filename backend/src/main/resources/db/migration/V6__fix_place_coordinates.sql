-- V6: 원천 좌표 오류 보정 - 영주산(KTO contentId 2704351) 경도.
-- KTO detailCommon2 가 mapx="12.79737228191"(앞자리 '6.' 누락)을 주고, 적재가 제주 범위 밖으로 걸러 NULL로 남겼다.
-- 복원값 126.7973723 은 OSM 봉우리 좌표(33.4046, 126.7963)와 110m 이내로 교차 확인했다(2026-09-07).
-- 내부 id 가 아니라 원천 ID로 찾는다 - 어느 환경이든 같은 행을 맞춘다. IS NULL 조건이라 두 번 돌아도 무해.
-- 재적재가 되돌리지 않도록 Place.updateFromSource 는 원천 좌표가 비면 기존값을 유지한다(같은 커밋).
-- 같은 유형이 더 나오면 아래에 UPDATE 한 줄씩 추가한다 - 세 건을 넘기면 보정표(place_overrides)로 승격할 것.

UPDATE places p
  JOIN place_source_mappings m ON m.place_id = p.id
SET p.longitude = 126.7973723
WHERE m.source_code = 'KTO'
  AND m.source_place_id = '2704351'
  AND p.longitude IS NULL;
