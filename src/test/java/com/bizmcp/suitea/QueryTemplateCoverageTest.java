package com.bizmcp.suitea;

import com.bizmcp.governance.BizPrincipal;
import com.bizmcp.governance.Role;
import com.bizmcp.query.QueryTemplateRegistry;
import com.bizmcp.support.GovernanceTestBase;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.beans.factory.annotation.Autowired;

import java.time.OffsetDateTime;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Executes every registered query template against real PostgreSQL.
 *
 * <p>Reaching a template only through the tool that happens to use it leaves
 * holes: before this test existed, {@code sales.summary_by_day} and
 * {@code sales.summary_by_product} had never run, because the tool fixtures
 * only ever passed {@code groupBy=STORE}. Broken SQL in either would have
 * shipped and only failed when a user asked a slightly different question.
 *
 * <p>The coverage assertion is the important part: adding a template without
 * adding parameters here fails the build, so this cannot silently rot.
 */
class QueryTemplateCoverageTest extends GovernanceTestBase {

    private static final OffsetDateTime JANUARY_START = OffsetDateTime.parse("2026-01-01T00:00+08:00");
    private static final OffsetDateTime FEBRUARY_START = OffsetDateTime.parse("2026-02-01T00:00+08:00");

    @Autowired QueryTemplateRegistry registry;

    /** Every template, with a parameter set that satisfies its bindings. */
    private static Map<String, Map<String, Object>> templateParameters() {
        Map<String, Map<String, Object>> parameters = new LinkedHashMap<>();

        Map<String, Object> dateRange = Map.of(
                "startDate", JANUARY_START, "endDateExclusive", FEBRUARY_START);

        parameters.put("sales.summary_by_store", dateRange);
        parameters.put("sales.summary_by_product", dateRange);
        parameters.put("sales.summary_by_day", dateRange);
        parameters.put("products.top_selling", dateRange);

        Map<String, Object> inventory = new HashMap<>();
        inventory.put("productId", null);
        inventory.put("belowThreshold", false);
        parameters.put("inventory.levels", inventory);

        Map<String, Object> counts = new HashMap<>(dateRange);
        counts.put("status", null);
        parameters.put("group_orders.count_by_status", counts);

        Map<String, Object> search = new HashMap<>(counts);
        search.put("keyword", null);
        parameters.put("group_orders.search", search);

        parameters.put("orders.detail", Map.of("orderId", 90001L));
        parameters.put("orders.detail_items", Map.of("orderId", 90001L));

        return parameters;
    }

    static Stream<String> templateIds() {
        return templateParameters().keySet().stream();
    }

    @Test
    void everyRegisteredTemplateIsCoveredByThisTest() {
        assertThat(templateParameters().keySet())
                .as("a template without parameters here would never be executed by any test")
                .containsExactlyInAnyOrderElementsOf(Set.copyOf(registry.templateIds()));
    }

    @ParameterizedTest(name = "{0} executes for both tenants")
    @MethodSource("templateIds")
    void templateExecutesForBothTenants(String templateId) {
        Map<String, Object> parameters = templateParameters().get(templateId);

        // Running as two different tenants also proves the injected :__tenant
        // bind is type-compatible in every template, not just the common ones.
        for (long tenantId : new long[]{TENANT_A, TENANT_B}) {
            BizPrincipal principal = BizPrincipal.of(1L, "coverage", Role.ADMIN, tenantId);
            QueryTemplateRegistry.QueryResult result =
                    registry.execute(templateId, principal, parameters);

            assertThat(result.rows()).as("%s for tenant %d", templateId, tenantId).isNotNull();
            assertThat(result.size()).isLessThanOrEqualTo(result.cap());
        }
    }

    @Test
    void aCallerCannotBindTheTenantParameterItself() {
        BizPrincipal principal = BizPrincipal.of(1L, "coverage", Role.ADMIN, TENANT_A);
        Map<String, Object> hostile = new HashMap<>(templateParameters().get("inventory.levels"));
        hostile.put(QueryTemplateRegistry.TENANT_PARAM, TENANT_B);

        assertThatThrownBy(() -> registry.execute("inventory.levels", principal, hostile))
                .isInstanceOf(com.bizmcp.governance.GovernanceExceptions.TenantIsolationException.class)
                .hasMessageContaining(QueryTemplateRegistry.TENANT_PARAM);
    }

    @Test
    void anUnknownTemplateIsRejected() {
        BizPrincipal principal = BizPrincipal.of(1L, "coverage", Role.ADMIN, TENANT_A);
        assertThatThrownBy(() -> registry.execute("sales.does_not_exist", principal, Map.of()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("sales.does_not_exist");
    }
}
