package com.bizmcp.suitea;

import com.bizmcp.support.AbstractPostgresTest;
import io.modelcontextprotocol.server.McpServerFeatures;
import io.modelcontextprotocol.spec.McpSchema;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The data dictionary resource (spec section 6.4, requirement F-11).
 *
 * <p>Giving the model vocabulary but not a database connection is the point:
 * it can learn what COMPLETED means without gaining a way to read anything.
 * Registration was previously unverified, and a resource that fails to
 * register does so silently.
 */
@SpringBootTest
class McpResourceTest extends AbstractPostgresTest {

    @Autowired List<McpServerFeatures.SyncResourceSpecification> resources;

    @Test
    void theDataDictionaryIsRegistered() {
        assertThat(resources).extracting(spec -> spec.resource().uri())
                .contains("schema://bizmcp/data-dictionary");
    }

    @Test
    void theDataDictionaryExplainsTheStatusMachinesAndMaskingRules() {
        McpServerFeatures.SyncResourceSpecification specification = resources.stream()
                .filter(spec -> spec.resource().uri().equals("schema://bizmcp/data-dictionary"))
                .findFirst()
                .orElseThrow();

        McpSchema.ReadResourceResult result = specification.readHandler()
                .apply(null, new McpSchema.ReadResourceRequest("schema://bizmcp/data-dictionary"));

        String text = result.contents().stream()
                .filter(McpSchema.TextResourceContents.class::isInstance)
                .map(contents -> ((McpSchema.TextResourceContents) contents).text())
                .reduce("", String::concat);

        assertThat(text).contains("COMPLETED", "CANCELLED", "EXECUTION_FAILED");
        // The revenue definition has to be stated once, or the model will guess.
        assertThat(text).contains("營收只計入 COMPLETED");
        assertThat(text).contains("遮罩");
    }

    @Test
    void theDataDictionaryContainsNoCustomerData() {
        McpServerFeatures.SyncResourceSpecification specification = resources.stream()
                .filter(spec -> spec.resource().uri().equals("schema://bizmcp/data-dictionary"))
                .findFirst()
                .orElseThrow();

        String text = specification.readHandler()
                .apply(null, new McpSchema.ReadResourceRequest("schema://bizmcp/data-dictionary"))
                .contents().stream()
                .filter(McpSchema.TextResourceContents.class::isInstance)
                .map(contents -> ((McpSchema.TextResourceContents) contents).text())
                .reduce("", String::concat);

        // It describes shapes, never rows.
        assertThat(text).doesNotContain("王小明").doesNotContain("0912");
    }
}
