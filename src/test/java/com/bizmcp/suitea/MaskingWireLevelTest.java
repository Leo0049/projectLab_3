package com.bizmcp.suitea;

import com.bizmcp.audit.AuditLogRepository;
import com.bizmcp.governance.Role;
import com.bizmcp.support.GovernanceTestBase;
import com.bizmcp.support.ToolArguments;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.Map;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Masking, asserted on the wire payload (spec section 7.2).
 *
 * <p>Deliberately not asserted on the DTO. Spike 2 showed that Spring AI
 * serializes tool results with its own Jackson mapper, so a DTO-level
 * assertion would pass even if nothing masked the bytes actually sent. These
 * tests read the serialized string, which is the only thing that proves the
 * guarantee.
 */
class MaskingWireLevelTest extends GovernanceTestBase {

    /** Any Taiwanese mobile number in full form. Must never appear in a payload. */
    private static final Pattern FULL_PHONE = Pattern.compile("09\\d{8}");

    @Autowired AuditLogRepository auditLogRepository;

    @Test
    void nameIsMaskedForEveryRoleThatCanReadTheOrder() {
        for (Role role : new Role[]{Role.CS_AGENT, Role.CS_LEAD, Role.ADMIN}) {
            actAs(role, TENANT_A);
            String payload = tools.callForWirePayload("get_order_detail",
                    Map.of("orderId", ToolArguments.ORDER_TENANT_A));

            assertThat(payload).as("name masked for %s", role).contains("王○明");
            assertThat(payload).as("raw name absent for %s", role).doesNotContain("王小明");
        }
    }

    @Test
    void phoneIsMaskedForEveryRole() {
        for (Role role : new Role[]{Role.CS_AGENT, Role.CS_LEAD, Role.ADMIN}) {
            actAs(role, TENANT_A);
            String payload = tools.callForWirePayload("get_order_detail",
                    Map.of("orderId", ToolArguments.ORDER_TENANT_A));

            assertThat(payload).as("phone masked for %s", role).contains("0912***678");
            assertThat(FULL_PHONE.matcher(payload).find())
                    .as("no full phone number anywhere in the payload for %s", role)
                    .isFalse();
        }
    }

    @Test
    void emailIsMaskedForEveryRole() {
        actAs(Role.CS_LEAD, TENANT_A);
        String payload = tools.callForWirePayload("get_order_detail",
                Map.of("orderId", ToolArguments.ORDER_TENANT_A));

        assertThat(payload).contains("m***@example.com");
        assertThat(payload).doesNotContain("ming@example.com");
    }

    @Test
    void addressIsMaskedForFrontLineStaff() {
        actAs(Role.CS_AGENT, TENANT_A);
        String payload = tools.callForWirePayload("get_order_detail",
                Map.of("orderId", ToolArguments.ORDER_TENANT_A));

        assertThat(payload).contains("台北市信義區***");
        assertThat(payload).doesNotContain("松高路11號5樓");
    }

    @Test
    void addressIsVisibleToTheRolesExemptedFromIt() {
        for (Role role : new Role[]{Role.CS_LEAD, Role.ADMIN}) {
            actAs(role, TENANT_A);
            String payload = tools.callForWirePayload("get_order_detail",
                    Map.of("orderId", ToolArguments.ORDER_TENANT_A));

            assertThat(payload).as("%s resolves delivery problems and needs the address", role)
                    .contains("松高路11號5樓");
        }
    }

    @Test
    void maskingIsUnaffectedByWhichMapperSerializes() {
        // The regression guard for ADR-003: if masking ever moves back to a
        // Jackson module that Spring AI does not consult, this fails.
        actAs(Role.ADMIN, TENANT_A);
        String payload = tools.callForWirePayload("get_order_detail",
                Map.of("orderId", ToolArguments.ORDER_TENANT_A));

        assertThat(payload).contains("untrusted_data");
        assertThat(payload).doesNotContain("王小明");
        assertThat(FULL_PHONE.matcher(payload).find()).isFalse();
    }

    @Test
    void aggregateToolsCarryNoPersonalDataAtAll() {
        actAs(Role.ANALYST, TENANT_A);
        String payload = tools.callForWirePayload("query_sales_summary",
                ToolArguments.validFor("query_sales_summary"));

        assertThat(payload).doesNotContain("王").doesNotContain("@example.com");
        assertThat(FULL_PHONE.matcher(payload).find()).isFalse();
    }

    @Test
    void auditedArgumentsAreMaskedSoTheLogIsNotTheLeak() {
        actAs(Role.CS_LEAD, TENANT_A);
        tools.call("search_group_orders", Map.of(
                "startDate", ToolArguments.START,
                "endDate", ToolArguments.END,
                // A phone number smuggled into a free-text search term.
                "keyword", "客戶 0912345678 的團"));

        String arguments = auditLogRepository.findByToolNameOrderByIdDesc("search_group_orders")
                .get(0).getArguments();

        assertThat(arguments).doesNotContain("0912345678");
        assertThat(arguments).contains("0912***678");
    }
}
