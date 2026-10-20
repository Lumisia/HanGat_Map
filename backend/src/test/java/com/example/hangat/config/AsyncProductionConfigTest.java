package com.example.hangat.config;

import org.junit.jupiter.api.Test;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.core.io.ClassPathResource;
import java.util.Map;
import static org.assertj.core.api.Assertions.assertThat;

class AsyncProductionConfigTest {
    @Test void commonConfigEnablesMemberAsyncWithoutPersonalOptions() throws Exception {
        var env = new StandardEnvironment();
        env.getPropertySources().remove(StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME);
        env.getPropertySources().remove(StandardEnvironment.SYSTEM_PROPERTIES_PROPERTY_SOURCE_NAME);
        for (var source : new YamlPropertySourceLoader().load("shared", new ClassPathResource("application.yaml"))) {
            env.getPropertySources().addLast(source);
        }
        assertThat(env.getProperty("hangat.async.enabled", Boolean.class)).isTrue();
        env.getPropertySources().addFirst(new MapPropertySource("override", Map.of("HANGAT_ASYNC_ENABLED", "false")));
        assertThat(env.getProperty("hangat.async.enabled", Boolean.class)).isFalse();
    }
    private StandardEnvironment production() throws Exception {
        var env = new StandardEnvironment();
        env.getPropertySources().remove(StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME);
        env.getPropertySources().remove(StandardEnvironment.SYSTEM_PROPERTIES_PROPERTY_SOURCE_NAME);
        for (var source : new YamlPropertySourceLoader().load("prod", new ClassPathResource("application-prod.yaml"))) {
            env.getPropertySources().addLast(source);
        }
        return env;
    }
    @Test void productionEnablesAsyncAndMigrationsWithoutPersonalSettings() throws Exception {
        var env = production();
        assertThat(env.getProperty("hangat.async.enabled", Boolean.class)).isTrue();
        assertThat(env.getProperty("spring.flyway.enabled", Boolean.class)).isTrue();
        assertThat(env.getProperty("spring.flyway.baseline-on-migrate", Boolean.class)).isFalse();
        assertThat(env.getProperty("spring.jpa.hibernate.ddl-auto")).isEqualTo("validate");
    }
    @Test void explicitApplicationOverrideStillWorksOutsidePairedDeployment() throws Exception {
        var env = production();
        env.getPropertySources().addFirst(new MapPropertySource("override", Map.of("HANGAT_ASYNC_ENABLED", "false")));
        assertThat(env.getProperty("hangat.async.enabled", Boolean.class)).isFalse();
        env.getPropertySources().addFirst(new MapPropertySource("deploymentArgs", Map.of("hangat.async.enabled", "true")));
        assertThat(env.getProperty("hangat.async.enabled", Boolean.class)).isTrue();
    }
}
