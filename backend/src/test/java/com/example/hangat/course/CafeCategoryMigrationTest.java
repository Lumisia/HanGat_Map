package com.example.hangat.course;

import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.core.io.ClassPathResource;
import static org.assertj.core.api.Assertions.*;

class CafeCategoryMigrationTest {
    @Test void repairsOnlyFoodWithActiveKtoMappingAndVerifiedCafeTagAndIsIdempotent() {
        var source=new DriverManagerDataSource("jdbc:h2:mem:cafe-migration;MODE=MariaDB","sa","");
        try(var connection=source.getConnection()) {
            var jdbc=new JdbcTemplate(source);
            jdbc.execute("CREATE TABLE place_categories(id INT PRIMARY KEY,code VARCHAR(30))");
            jdbc.execute("CREATE TABLE places(id BIGINT PRIMARY KEY,primary_category_id INT)");
            jdbc.execute("CREATE TABLE tags(id INT PRIMARY KEY,code VARCHAR(30),is_active BOOLEAN)");
            jdbc.execute("CREATE TABLE place_tags(place_id BIGINT,tag_id INT,source_type VARCHAR(20))");
            jdbc.execute("CREATE TABLE place_source_mappings(place_id BIGINT,source_code VARCHAR(30),is_active BOOLEAN)");
            jdbc.update("INSERT INTO place_categories VALUES(1,'FOOD'),(2,'CAFE'),(3,'TOURIST')");
            jdbc.update("INSERT INTO places VALUES(1,1),(2,1),(3,1),(4,3),(5,1),(6,1)");
            jdbc.update("INSERT INTO tags VALUES(1,'FD050100',true),(2,'FD010100',true),(3,'FD050200',true)");
            jdbc.update("INSERT INTO place_tags VALUES(1,1,'API'),(2,2,'API'),(3,1,'API'),(4,1,'API'),(5,3,'API'),(6,1,'REVIEW')");
            jdbc.update("INSERT INTO place_source_mappings VALUES(1,'KTO',true),(2,'KTO',true),(3,'KTO',false),(4,'KTO',true),(5,'KTO',true),(6,'KTO',true)");
            var script=new ResourceDatabasePopulator(new ClassPathResource("db/migration/V16__repair_kto_cafe_categories.sql"));
            script.execute(source);script.execute(source);
            assertThat(jdbc.queryForList("SELECT primary_category_id FROM places ORDER BY id",Integer.class))
                    .containsExactly(2,1,1,3,2,1);
        } catch(java.sql.SQLException e) { throw new AssertionError(e); }
    }
}
