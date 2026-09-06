package com.example.hangat.config;

import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationInfo;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Arrays;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Flyway 마이그레이션 <b>해석</b>만 검증한다(실행하지 않는다).
 *
 * <p>Flyway는 운영 프로필에서만 켜지고 테스트는 H2 create-drop이라, 같은 버전 번호의 스크립트가 두 개
 * 생기면(브랜치 두 개가 각자 V2를 추가하는 흔한 사고) 운영 부팅에서야 "Found more than one migration with
 * version N"으로 터진다. 중복 버전 검사는 히스토리 테이블을 보기 전 해석 단계에서 일어나므로
 * 빈 H2에 대고 {@code info()}만 불러도 잡힌다.
 */
class FlywayMigrationResolutionTest {

    @Test
    @DisplayName("db/migration의 버전 번호가 겹치지 않고 전부 해석된다 - 운영 부팅 전에 잡는다")
    void migrationsResolveWithoutDuplicateVersions() {
        Flyway flyway = Flyway.configure()
                .dataSource("jdbc:h2:mem:flyway_resolution;MODE=MariaDB;DB_CLOSE_DELAY=-1", "sa", "")
                .locations("classpath:db/migration")
                .load();

        MigrationInfo[] all = flyway.info().all();

        assertThat(all).isNotEmpty();
        assertThat(Arrays.stream(all).map(info -> info.getVersion().toString()))
                .doesNotHaveDuplicates()
                .contains("1", "2", "3");
    }
}
