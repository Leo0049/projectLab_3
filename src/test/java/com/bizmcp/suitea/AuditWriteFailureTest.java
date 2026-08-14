package com.bizmcp.suitea;

import com.bizmcp.audit.AuditWriter;
import com.bizmcp.governance.GovernanceExceptions.AuditWriteFailedException;
import com.bizmcp.governance.Role;
import com.bizmcp.support.GovernanceTestBase;
import com.bizmcp.support.ToolArguments;
import io.modelcontextprotocol.spec.McpSchema;
import org.junit.jupiter.api.Test;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The other half of fail-closed: when the audit trail cannot be written, the
 * call must not happen (spec section 9.1).
 *
 * <p>Separate class because it replaces {@link AuditWriter} with a failing
 * stub for the whole context.
 */
class AuditWriteFailureTest extends GovernanceTestBase {

    @MockitoBean AuditWriter auditWriter;

    @Test
    void aCallIsRefusedWhenTheAuditRecordCannotBeWritten() {
        when(auditWriter.recordPre(any(), any(), any()))
                .thenThrow(new AuditWriteFailedException(new RuntimeException("disk full")));

        actAs(Role.ADMIN, TENANT_A);
        McpSchema.CallToolResult result =
                tools.call("query_sales_summary", ToolArguments.validFor("query_sales_summary"));

        assertThat(tools.isError(result)).isTrue();

        String message = result.content().stream()
                .filter(McpSchema.TextContent.class::isInstance)
                .map(content -> ((McpSchema.TextContent) content).text())
                .reduce("", String::concat);
        assertThat(message).contains("稽核寫入失敗");
        // The internal cause must not survive Spring AI's root-cause unwrapping.
        assertThat(message).doesNotContain("disk full");

        // An unlogged operation must leave no trace of having run either.
        verify(auditWriter, never()).recordSuccess(any(), any(), org.mockito.ArgumentMatchers.anyLong());
    }
}
