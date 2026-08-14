package com.bizmcp.suitea;

import com.bizmcp.governance.GovernanceExceptions.TenantIsolationException;
import com.bizmcp.governance.GovernanceProperties;
import com.bizmcp.governance.RiskTier;
import com.bizmcp.governance.Role;
import com.bizmcp.governance.ToolRisk;
import com.bizmcp.governance.startup.ToolGovernanceValidator;
import com.bizmcp.query.QueryTemplateProperties;
import com.bizmcp.query.QueryTemplateRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.ai.mcp.annotation.McpTool;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.ClassPathResource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Startup gates (spec sections 5.3, 7.1, 7.3).
 *
 * <p>These rules could be checked at request time, but a tenant leak found at
 * request time has already leaked. Making them boot failures means the mistake
 * cannot reach production quietly — so these tests assert that the application
 * genuinely refuses to start, not merely that it logs a warning.
 */
class StartupValidationTest {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner();

    @Test
    void aQueryTemplateWithoutATenantPredicateStopsStartup() {
        QueryTemplateProperties properties =
                new QueryTemplateProperties(new ClassPathResource("templates-missing-tenant.yml"));
        QueryTemplateRegistry registry =
                new QueryTemplateRegistry(properties, null, new GovernanceProperties());

        assertThatThrownBy(registry::afterPropertiesSet)
                .isInstanceOf(TenantIsolationException.class)
                .hasMessageContaining("sales.unscoped")
                .hasMessageContaining("__tenant");
    }

    @Test
    void theShippedTemplatesAllBindTheTenant() {
        QueryTemplateProperties properties =
                new QueryTemplateProperties(new ClassPathResource("query-templates.yml"));
        QueryTemplateRegistry registry =
                new QueryTemplateRegistry(properties, null, new GovernanceProperties());

        registry.afterPropertiesSet();
        assertThat(registry.templateIds()).isNotEmpty();
    }

    @Test
    void aToolExposingATenantParameterStopsStartup() {
        contextRunner.withUserConfiguration(TenantParameterToolConfig.class).run(context -> {
            assertThat(context).hasFailed();
            assertThat(rootCauseOf(context.getStartupFailure()))
                    .contains("merchantId")
                    .contains("tenant must come from the principal only");
        });
    }

    @Test
    void aToolWithoutADeclaredRiskTierStopsStartup() {
        contextRunner.withUserConfiguration(UndeclaredRiskToolConfig.class).run(context -> {
            assertThat(context).hasFailed();
            assertThat(rootCauseOf(context.getStartupFailure())).contains("@ToolRisk");
        });
    }

    @Test
    void aCompliantToolStartsCleanly() {
        contextRunner.withUserConfiguration(CompliantToolConfig.class)
                .run(context -> assertThat(context).hasNotFailed());
    }

    private String rootCauseOf(Throwable failure) {
        Throwable current = failure;
        StringBuilder messages = new StringBuilder();
        while (current != null) {
            messages.append(current.getMessage()).append('\n');
            current = current.getCause();
        }
        return messages.toString();
    }

    // --- fixtures -------------------------------------------------------

    @Configuration
    static class TenantParameterToolConfig {
        @Bean ToolGovernanceValidator validator(
                org.springframework.beans.factory.config.ConfigurableListableBeanFactory beanFactory) {
            return new ToolGovernanceValidator(beanFactory);
        }

        @Bean LeakyTool leakyTool() {
            return new LeakyTool();
        }
    }

    public static class LeakyTool {
        @McpTool(name = "leaky_sales", description = "takes a merchant id")
        @ToolRisk(tier = RiskTier.T1, allow = {Role.ADMIN})
        public String query(Long merchantId) {
            return "leaked";
        }
    }

    @Configuration
    static class UndeclaredRiskToolConfig {
        @Bean ToolGovernanceValidator validator(
                org.springframework.beans.factory.config.ConfigurableListableBeanFactory beanFactory) {
            return new ToolGovernanceValidator(beanFactory);
        }

        @Bean UngovernedTool ungovernedTool() {
            return new UngovernedTool();
        }
    }

    public static class UngovernedTool {
        @McpTool(name = "ungoverned", description = "no risk tier declared")
        public String query() {
            return "ungoverned";
        }
    }

    @Configuration
    static class CompliantToolConfig {
        @Bean ToolGovernanceValidator validator(
                org.springframework.beans.factory.config.ConfigurableListableBeanFactory beanFactory) {
            return new ToolGovernanceValidator(beanFactory);
        }

        @Bean GoodTool goodTool() {
            return new GoodTool();
        }
    }

    public static class GoodTool {
        @McpTool(name = "good_tool", description = "declares its policy")
        @ToolRisk(tier = RiskTier.T1, allow = {Role.ADMIN})
        public String query(String startDate) {
            return "ok";
        }
    }
}
