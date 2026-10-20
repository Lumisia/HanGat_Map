-- V5: 찜(테이블 명세서 13.0 favorite) - MAP_009 지도 하트 ↔ MY_006 마이페이지 찜 목록의 단일 저장소.
-- (V4까지 존재. 새 스크립트는 git ls-tree origin/dev backend/src/main/resources/db/migration 으로
--  현재 최대 버전을 확인하고 그 다음 번호를 쓴다 - FlywayMigrationResolutionTest가 중복을 잡는다.)
--
-- 명세서 대비 편차:
--   * 테이블·컬럼명은 실제 스키마(users / places)에 맞춘다 - 명세서의 spot 은 places 로 통합됐다.
--   * created_at 추가 - "최근 찜한 순" 정렬(MY_006)에 필요한데 명세서엔 없다.
--   * UNSIGNED·CHECK 는 팀 컨벤션대로 재현하지 않는다(엔티티와 validate 가 어긋난다).
-- 회원 탈퇴·장소 삭제 시 찜도 함께 지운다(CASCADE) - 고아 행이 마이페이지에 '알 수 없는 장소'로 남지 않게.
-- 토글은 UNIQUE (user_id, place_id) 존재 여부로 판정한다 - 같은 장소를 두 번 찜하는 행은 생기지 않는다.

CREATE TABLE `favorites` (
  `id` bigint(20) NOT NULL AUTO_INCREMENT,
  `user_id` bigint(20) NOT NULL,
  `place_id` bigint(20) NOT NULL,
  `created_at` datetime(6) NOT NULL,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_favorites_user_place` (`user_id`, `place_id`),
  KEY `fk_favorites_place` (`place_id`),
  CONSTRAINT `fk_favorites_user` FOREIGN KEY (`user_id`) REFERENCES `users` (`id`) ON DELETE CASCADE,
  CONSTRAINT `fk_favorites_place` FOREIGN KEY (`place_id`) REFERENCES `places` (`id`) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
