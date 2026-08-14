package com.bizmcp.suitea;

import com.bizmcp.governance.Role;
import com.bizmcp.masking.MaskStrategy;
import com.bizmcp.masking.MaskingEngine;
import com.bizmcp.masking.Masked;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The masking traversal (spec section 7.2, ADR-003).
 *
 * <p>The engine walks nested records, collections and maps, but the only
 * production result carrying personal data today is a flat record — so all of
 * that recursion was previously unexercised. A future tool returning, say, a
 * list of customers would have been the first thing to discover a bug in it.
 */
class MaskingEngineTest {

    private final MaskingEngine engine = new MaskingEngine();

    record Customer(
            @Masked(strategy = MaskStrategy.NAME) String name,
            @Masked(strategy = MaskStrategy.PHONE) String phone,
            @Masked(strategy = MaskStrategy.ADDRESS, unmaskFor = {Role.CS_LEAD, Role.ADMIN}) String address,
            int orderCount
    ) {
    }

    record Order(long orderId, Customer customer, List<Customer> alternateContacts) {
    }

    record Aggregate(String storeName, long revenue) {
    }

    @Test
    void maskedFieldsAreReplacedAndOthersLeftAlone() {
        Customer masked = (Customer) engine.mask(
                new Customer("王小明", "0912345678", "台北市信義區松高路11號", 3), Role.CS_AGENT);

        assertThat(masked.name()).isEqualTo("王○明");
        assertThat(masked.phone()).isEqualTo("0912***678");
        assertThat(masked.address()).isEqualTo("台北市信義區***");
        assertThat(masked.orderCount()).isEqualTo(3);
    }

    @Test
    void exemptRolesSeeTheRawValueOfThatFieldOnly() {
        Customer masked = (Customer) engine.mask(
                new Customer("王小明", "0912345678", "台北市信義區松高路11號", 3), Role.CS_LEAD);

        // The exemption is per field, not per record.
        assertThat(masked.address()).isEqualTo("台北市信義區松高路11號");
        assertThat(masked.name()).isEqualTo("王○明");
        assertThat(masked.phone()).isEqualTo("0912***678");
    }

    @Test
    void theInputIsNeverMutated() {
        Customer original = new Customer("王小明", "0912345678", "台北市信義區松高路11號", 3);
        engine.mask(original, Role.CS_AGENT);

        assertThat(original.name()).isEqualTo("王小明");
        assertThat(original.phone()).isEqualTo("0912345678");
    }

    @Test
    void nestedRecordsAreMasked() {
        Order order = new Order(1L,
                new Customer("王小明", "0912345678", "台北市信義區松高路11號", 1),
                List.of());

        Order masked = (Order) engine.mask(order, Role.CS_AGENT);

        assertThat(masked.customer().name()).isEqualTo("王○明");
        assertThat(masked.orderId()).isEqualTo(1L);
    }

    @Test
    void recordsInsideCollectionsAreMasked() {
        Order order = new Order(1L,
                new Customer("王小明", "0912345678", "台北市信義區松高路11號", 1),
                List.of(new Customer("陳美麗", "0922333444", "台北市大安區忠孝東路2號", 2),
                        new Customer("李大文", "0933777888", "台北市中山區南京東路9號", 5)));

        Order masked = (Order) engine.mask(order, Role.CS_AGENT);

        assertThat(masked.alternateContacts()).hasSize(2);
        assertThat(masked.alternateContacts().get(0).name()).isEqualTo("陳○麗");
        assertThat(masked.alternateContacts().get(1).phone()).isEqualTo("0933***888");
    }

    @Test
    void topLevelCollectionsAreMasked() {
        @SuppressWarnings("unchecked")
        List<Customer> masked = (List<Customer>) engine.mask(
                List.of(new Customer("王小明", "0912345678", "台北市信義區1號", 1)), Role.CS_AGENT);

        assertThat(masked.get(0).name()).isEqualTo("王○明");
    }

    @Test
    void setsStaySetsAndAreMasked() {
        @SuppressWarnings("unchecked")
        Set<Customer> masked = (Set<Customer>) engine.mask(
                Set.of(new Customer("王小明", "0912345678", "台北市信義區1號", 1)), Role.CS_AGENT);

        assertThat(masked).hasSize(1);
        assertThat(masked.iterator().next().name()).isEqualTo("王○明");
    }

    @Test
    void mapValuesAreMaskedAndKeysPreserved() {
        Map<String, Customer> source = new LinkedHashMap<>();
        source.put("primary", new Customer("王小明", "0912345678", "台北市信義區1號", 1));

        @SuppressWarnings("unchecked")
        Map<String, Customer> masked = (Map<String, Customer>) engine.mask(source, Role.CS_AGENT);

        assertThat(masked).containsOnlyKeys("primary");
        assertThat(masked.get("primary").name()).isEqualTo("王○明");
    }

    @Test
    void recordsWithNothingToMaskAreReturnedUnchanged() {
        Aggregate aggregate = new Aggregate("信義店", 450L);
        // Aggregates are the common case, so the no-op path should not allocate.
        assertThat(engine.mask(aggregate, Role.ANALYST)).isSameAs(aggregate);
    }

    @Test
    void scalarsAndNullsPassThrough() {
        assertThat(engine.mask(null, Role.ADMIN)).isNull();
        assertThat(engine.mask("0912345678", Role.ADMIN))
                .as("masking is driven by declared policy, never by guessing at string content")
                .isEqualTo("0912345678");
        assertThat(engine.mask(42, Role.ADMIN)).isEqualTo(42);
    }

    @Test
    void everyRoleWithoutAnExemptionGetsTheMaskedAddress() {
        for (Role role : Role.values()) {
            Customer masked = (Customer) engine.mask(
                    new Customer("王小明", "0912345678", "台北市信義區松高路11號", 1), role);

            boolean exempt = role == Role.CS_LEAD || role == Role.ADMIN;
            assertThat(masked.address().contains("松高路"))
                    .as("address visible to %s", role)
                    .isEqualTo(exempt);
            // Name and phone have no exemptions at all.
            assertThat(masked.name()).as("name for %s", role).isEqualTo("王○明");
            assertThat(masked.phone()).as("phone for %s", role).isEqualTo("0912***678");
        }
    }
}
