-- V7: 내 리뷰 목록(MY_004) 인덱스.
-- (V6까지 존재. 새 스크립트는 git ls-tree origin/dev backend/src/main/resources/db/migration 으로
--  현재 최대 버전을 확인하고 그 다음 번호를 쓴다 - FlywayMigrationResolutionTest가 중복을 잡는다.)
--
-- reviews 는 V1 에서 장소 기준 조회용 idx_reviews_place_status (place_id, status, created_at) 만 갖고 있다.
-- 마이페이지는 반대로 "내가 쓴 리뷰"를 최신순으로 읽어 회원 기준 인덱스가 없으면 전체 스캔이 된다.
-- 정렬 키에 id 를 덧붙이는 이유: created_at 이 같은 리뷰가 있어도 더보기 페이지 사이에서
-- 순서가 흔들리지 않게 한다(같은 항목이 두 페이지에 나오는 것을 막는다).
--
-- 찜 테이블은 V5 가 이미 만들었다 - 여기서 다시 만들지 않는다.

CREATE INDEX `idx_reviews_user_status_created`
    ON `reviews` (`user_id`, `status`, `created_at`, `id`);
