package com.bizmcp.support;

import io.zonky.test.db.postgres.embedded.EmbeddedPostgres;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.io.IOException;

/**
 * Suite A runs against real PostgreSQL. The spec asks for Testcontainers, but a
 * Docker daemon is not always available (CI runners, restricted sandboxes), and
 * the governance tables use JSONB so an H2 substitute would not exercise the
 * real thing. zonky embedded-postgres runs an actual PostgreSQL binary
 * in-process, which keeps the tests honest without needing Docker.
 *
 * One server is shared by the whole suite; each test class gets its own
 * database so Flyway state and row mutations do not leak between classes.
 */
@ActiveProfiles("test")
public abstract class AbstractPostgresTest {

    private static final EmbeddedPostgres POSTGRES;

    static {
        try {
            POSTGRES = EmbeddedPostgres.builder().start();
        } catch (IOException e) {
            throw new IllegalStateException("could not start embedded PostgreSQL", e);
        }
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            try {
                POSTGRES.close();
            } catch (IOException ignored) {
                // shutting down anyway
            }
        }));
    }

    @DynamicPropertySource
    static void datasourceProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", () -> POSTGRES.getJdbcUrl("postgres", "postgres"));
        registry.add("spring.datasource.username", () -> "postgres");
        registry.add("spring.datasource.password", () -> "postgres");
        registry.add("spring.datasource.driver-class-name", () -> "org.postgresql.Driver");
    }
}
