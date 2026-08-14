package com.bizmcp.perf;

import com.bizmcp.governance.BizAuthenticationToken;
import com.bizmcp.governance.BizPrincipal;
import com.bizmcp.governance.Role;
import com.bizmcp.support.McpToolInvoker;
import io.zonky.test.db.postgres.embedded.EmbeddedPostgres;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The performance acceptance criterion from spec section 2.2: read-only tools
 * at P95 under 300ms over 50,000 orders, with {@code EXPLAIN ANALYZE} output
 * attached as evidence.
 *
 * <p>Tagged {@code perf} and excluded from the default build. It seeds a
 * separate 50,000-order database, which takes far too long to run on every
 * push, and a latency threshold on shared CI hardware is a flaky gate. Run it
 * deliberately:
 *
 * <pre>mvn test -Dgroups=perf -Dexcluded.test.groups=</pre>
 *
 * <p>It writes {@code docs/performance-report.md} so the evidence is a
 * committed artefact rather than something asserted once and forgotten.
 */
@Tag("perf")
@SpringBootTest
@ActiveProfiles("test")
@Import(McpToolInvoker.class)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class PerformanceVerificationTest {

    private static final int WARMUP_RUNS = 10;
    private static final int MEASURED_RUNS = 100;
    private static final long P95_BUDGET_MS = 300;

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

    /** Its own database, seeded with the 50,000-order demo dataset. */
    @DynamicPropertySource
    static void datasource(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", () -> POSTGRES.getJdbcUrl("postgres", "postgres"));
        registry.add("spring.datasource.username", () -> "postgres");
        registry.add("spring.datasource.password", () -> "postgres");
        registry.add("spring.flyway.locations",
                () -> "classpath:db/migration,classpath:db/seed/demo");
    }

    private static final List<String> REPORT = new ArrayList<>();

    @Autowired JdbcTemplate jdbc;
    @Autowired McpToolInvoker tools;

    @AfterEach
    void clearSecurityContext() {
        SecurityContextHolder.clearContext();
    }

    private void actAsStoreManager() {
        SecurityContextHolder.getContext().setAuthentication(new BizAuthenticationToken(
                new BizPrincipal(42L, "alice", Role.STORE_MANAGER, 7L, "perf")));
    }

    @Test
    @Order(1)
    void theDatasetIsTheSizeTheAcceptanceCriterionAssumes() {
        Integer orders = jdbc.queryForObject("SELECT count(*) FROM orders", Integer.class);
        Integer items = jdbc.queryForObject("SELECT count(*) FROM order_items", Integer.class);

        assertThat(orders).isEqualTo(50_000);
        REPORT.add("- Orders: **%,d**, order items: **%,d**".formatted(orders, items));
    }

    @Test
    @Order(2)
    void bothTenantsHaveARealShareOfTheDemoData() {
        List<java.util.Map<String, Object>> perTenant = jdbc.queryForList(
                "SELECT merchant_id, count(*) AS orders FROM orders GROUP BY merchant_id ORDER BY merchant_id");

        // The demo previously put all 50,000 orders on tenant 7: a LATERAL that
        // ordered by an expression tied between stores and resolved the tie the
        // same way every time. Isolation demonstrated against an empty tenant
        // proves nothing, so the dataset itself is asserted.
        assertThat(perTenant).as("both merchants must own orders").hasSize(2);
        for (var row : perTenant) {
            long orders = ((Number) row.get("orders")).longValue();
            assertThat(orders)
                    .as("orders for merchant %s", row.get("merchant_id"))
                    .isGreaterThan(5_000);
        }
        REPORT.add("- Tenant split: " + perTenant.stream()
                .map(row -> "merchant %s = %s orders".formatted(row.get("merchant_id"), row.get("orders")))
                .toList());
    }

    @Test
    @Order(2)
    void theCompositeIndexExists() {
        List<String> indexes = jdbc.queryForList(
                "SELECT indexname FROM pg_indexes WHERE tablename = 'orders'", String.class);

        assertThat(indexes).contains("idx_orders_merchant_status_created");
        REPORT.add("- Indexes on `orders`: " + String.join(", ", indexes));
    }

    @Test
    @Order(3)
    void aTypicalWindowUsesTheCompositeIndex() {
        // "Last week's revenue" is the shape users actually ask for: a narrow
        // slice of a long history, which is what the index is for.
        String plan = explain(LocalDate.now().minusDays(7), LocalDate.now());

        REPORT.add("\n### Plan for a 7-day window (the common query)\n\n```\n" + plan + "\n```");

        assertThat(plan)
                .as("a narrow window should not scan the whole orders table")
                .contains("idx_orders_merchant_status_created");
        assertThat(plan).doesNotContain("Seq Scan on orders");
    }

    @Test
    @Order(4)
    void theWidestAllowedWindowIsRecordedForReference() {
        // 90 days is the maximum the tools accept. Over 180 days of history it
        // touches roughly half the table, so a sequential scan can legitimately
        // be the cheaper plan - recorded as evidence rather than asserted.
        String plan = explain(LocalDate.now().minusDays(90), LocalDate.now());
        REPORT.add("\n### Plan for the 90-day maximum window\n\n```\n" + plan + "\n```");

        assertThat(plan).contains("Execution Time");
    }

    @Test
    @Order(5)
    void readOnlyToolsMeetTheP95Budget() {
        actAsStoreManager();
        Map<String, Object> arguments = Map.of(
                "startDate", LocalDate.now().minusDays(7).toString(),
                "endDate", LocalDate.now().toString(),
                "groupBy", "STORE");

        for (int i = 0; i < WARMUP_RUNS; i++) {
            tools.call("query_sales_summary", arguments);
        }

        long[] samples = new long[MEASURED_RUNS];
        for (int i = 0; i < MEASURED_RUNS; i++) {
            long startedAt = System.nanoTime();
            tools.call("query_sales_summary", arguments);
            samples[i] = (System.nanoTime() - startedAt) / 1_000_000;
        }
        java.util.Arrays.sort(samples);

        long p50 = samples[(int) (MEASURED_RUNS * 0.50)];
        long p95 = samples[(int) (MEASURED_RUNS * 0.95)];
        long max = samples[MEASURED_RUNS - 1];

        REPORT.add(("\n### End-to-end tool latency (`query_sales_summary`, 7-day window)\n\n"
                    + "Measured through the MCP tool specification, so the governance chain, "
                    + "masking and serialization are all inside the measurement.\n\n"
                    + "| Runs | P50 | P95 | Max | Budget |\n"
                    + "|---|---|---|---|---|\n"
                    + "| %d | %d ms | **%d ms** | %d ms | 300 ms |")
                .formatted(MEASURED_RUNS, p50, p95, max));

        assertThat(p95)
                .as("P95 latency over %d runs (spec section 2.2)", MEASURED_RUNS)
                .isLessThan(P95_BUDGET_MS);
    }

    @Test
    @Order(6)
    void detailLookupsAreAlsoWithinBudget() {
        actAsStoreManager();
        SecurityContextHolder.getContext().setAuthentication(new BizAuthenticationToken(
                new BizPrincipal(43L, "bob", Role.CS_LEAD, 7L, "perf")));

        Long orderId = jdbc.queryForObject(
                "SELECT order_id FROM orders WHERE merchant_id = 7 LIMIT 1", Long.class);
        Map<String, Object> arguments = Map.of("orderId", orderId);

        for (int i = 0; i < WARMUP_RUNS; i++) {
            tools.call("get_order_detail", arguments);
        }

        long[] samples = new long[MEASURED_RUNS];
        for (int i = 0; i < MEASURED_RUNS; i++) {
            long startedAt = System.nanoTime();
            tools.call("get_order_detail", arguments);
            samples[i] = (System.nanoTime() - startedAt) / 1_000_000;
        }
        java.util.Arrays.sort(samples);
        long p95 = samples[(int) (MEASURED_RUNS * 0.95)];

        REPORT.add("\n### `get_order_detail` (masked, two queries per call)\n\n"
                   + "P95 over %d runs: **%d ms**".formatted(MEASURED_RUNS, p95));

        assertThat(p95).isLessThan(P95_BUDGET_MS);
        writeReport();
    }

    private String explain(LocalDate from, LocalDate to) {
        List<String> lines = jdbc.queryForList("""
                EXPLAIN (ANALYZE, BUFFERS)
                SELECT s.store_id, s.store_name,
                       COUNT(DISTINCT o.order_id) AS order_count,
                       COALESCE(SUM(o.total_amount), 0) AS revenue
                FROM orders o
                JOIN stores s ON s.store_id = o.store_id
                WHERE o.created_at >= ?::timestamptz
                  AND o.created_at < ?::timestamptz
                  AND o.status = 'COMPLETED'
                  AND o.merchant_id = 7
                GROUP BY s.store_id, s.store_name
                ORDER BY revenue DESC
                """, String.class, from.toString(), to.plusDays(1).toString());
        return String.join("\n", lines);
    }

    private void writeReport() {
        String document = """
                # Performance report

                Acceptance evidence for spec section 2.2: read-only tools at P95 under
                300 ms over a 50,000-order dataset, with the composite index
                `(merchant_id, status, created_at)` in place.

                Regenerate with:

                ```bash
                mvn test -Dgroups=perf -Dexcluded.test.groups=
                ```

                Measured on embedded PostgreSQL %s in the build container, so the
                absolute numbers depend on the host. What the run establishes is the
                shape: a typical window uses the index, and the end-to-end tool call
                stays inside the budget with governance, masking and serialization
                included.

                ## Dataset

                %s

                %s
                """.formatted(
                postgresVersion(),
                String.join("\n", REPORT.subList(0, Math.min(2, REPORT.size()))),
                String.join("\n", REPORT.subList(Math.min(2, REPORT.size()), REPORT.size())));

        try {
            Path path = Path.of("docs/performance-report.md");
            Files.createDirectories(path.getParent());
            Files.writeString(path, document);
            System.out.println("### wrote " + path.toAbsolutePath());
        } catch (IOException e) {
            throw new IllegalStateException("could not write the performance report", e);
        }
    }

    private String postgresVersion() {
        String version = jdbc.queryForObject("SHOW server_version", String.class);
        return version == null ? "unknown" : version;
    }
}
