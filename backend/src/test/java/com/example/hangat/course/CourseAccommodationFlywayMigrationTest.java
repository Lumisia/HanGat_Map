package com.example.hangat.course;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;

class CourseAccommodationFlywayMigrationTest {

    @Test
    void v1KeepsTheSharedParentAndExistingChildSigned() throws IOException {
        String migration = readMigration("db/migration/V1__initial_schema.sql");
        String sourceMappings = tableDefinition(migration, "place_source_mappings");
        String placeImages = tableDefinition(migration, "place_images");

        assertThat(sourceMappings)
                .contains("`id` bigint(20) NOT NULL AUTO_INCREMENT")
                .doesNotContain("`id` bigint(20) unsigned");
        assertThat(placeImages)
                .contains("`source_mapping_id` bigint(20) DEFAULT NULL")
                .doesNotContain("`source_mapping_id` bigint(20) unsigned");
        assertThat(migration).doesNotContain("accommodation_source_mapping_id");
    }

    @Test
    void v2UsesSignedBigintAndAddsOnlyTheAccommodationRelationship() throws IOException {
        String migration = readMigration("db/migration/V2__course_accommodation_persistence.sql");
        String normalized = migration.replaceAll("\\s+", " ").trim();

        assertThat(normalized)
                .contains("ALTER TABLE courses")
                .contains("ADD COLUMN accommodation_source_mapping_id BIGINT NULL DEFAULT NULL")
                .contains("ADD INDEX idx_courses_accommodation_source_mapping")
                .contains("ADD CONSTRAINT fk_courses_accommodation_source_mapping")
                .contains("FOREIGN KEY (accommodation_source_mapping_id)")
                .contains("REFERENCES place_source_mappings (id)")
                .contains("ON DELETE RESTRICT")
                .contains("ON UPDATE RESTRICT")
                .doesNotContain("BIGINT UNSIGNED")
                .doesNotContain("UPDATE courses")
                .doesNotContain("DELETE FROM")
                .doesNotContain("DROP ")
                .doesNotContain("TRUNCATE ");
    }

    private String readMigration(String path) throws IOException {
        ClassPathResource resource = new ClassPathResource(path);
        assertThat(resource.exists()).isTrue();
        return resource.getContentAsString(StandardCharsets.UTF_8);
    }

    private String tableDefinition(String migration, String tableName) {
        String marker = "CREATE TABLE `" + tableName + "`";
        int start = migration.indexOf(marker);
        assertThat(start).isGreaterThanOrEqualTo(0);
        int end = migration.indexOf(";", start);
        assertThat(end).isGreaterThan(start);
        return migration.substring(start, end + 1);
    }
}
