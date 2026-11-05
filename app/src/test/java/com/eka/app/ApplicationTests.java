package com.eka.app;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

@SpringBootTest
@Testcontainers
class ApplicationTests {
    @Container
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:17-alpine");

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry registry) {
        TestSigningKeys.configure(registry);
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
    }

    @Autowired Flyway flyway;
    @Autowired JdbcClient jdbc;

    @Test
    void contextLoadsWithMigratedDatabase() {
        assertThat(flyway.info().current().getVersion().getVersion()).isEqualTo("2");
        assertThat(jdbc.sql("SELECT count(*) FROM auth.accounts").query(Long.class).single())
                .isZero();
        UUID marker = UUID.randomUUID();
        jdbc.sql("INSERT INTO auth.tenants (id, slug) VALUES (?, 'restart-marker')")
                .param(marker)
                .update();
        try (var restarted =
                new SpringApplicationBuilder(Application.class)
                        .web(WebApplicationType.NONE)
                        .run(
                                "--spring.datasource.url=" + POSTGRES.getJdbcUrl(),
                                "--spring.datasource.username=" + POSTGRES.getUsername(),
                                "--spring.datasource.password=" + POSTGRES.getPassword(),
                                "--eka.auth.private-key=" + TestSigningKeys.PRIVATE,
                                "--eka.auth.public-key=" + TestSigningKeys.PUBLIC,
                                "--eka.auth.issuer=" + TestSigningKeys.ISSUER,
                                "--eka.auth.audience=" + TestSigningKeys.AUDIENCE)) {
            assertThat(restarted.getBean(Flyway.class).migrate().migrationsExecuted).isZero();
            assertThat(
                            restarted
                                    .getBean(JdbcClient.class)
                                    .sql("SELECT count(*) FROM auth.tenants WHERE id = ?")
                                    .param(marker)
                                    .query(Long.class)
                                    .single())
                    .isEqualTo(1L);
        }
    }
}
