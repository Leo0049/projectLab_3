package com.bizmcp.query;

import com.bizmcp.governance.BizPrincipal;
import com.bizmcp.governance.GovernanceExceptions.TenantIsolationException;
import com.bizmcp.governance.GovernanceProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Executes the named query templates (spec section 7.1).
 *
 * <p>The model never supplies SQL, and never supplies a tenant. It selects a
 * template by id and provides bound parameters; the tenant predicate is filled
 * from the authenticated principal here, in one place.
 *
 * <p>Two invariants are enforced at startup rather than at request time,
 * because a tenant leak found in production is found too late:
 * <ul>
 *   <li>every template must reference {@code :__tenant};</li>
 *   <li>no template may declare its own tenant-like parameter.</li>
 * </ul>
 */
@Component
public class QueryTemplateRegistry implements InitializingBean {

    private static final Logger log = LoggerFactory.getLogger(QueryTemplateRegistry.class);

    /** The one parameter a caller may never bind. */
    public static final String TENANT_PARAM = "__tenant";
    private static final String LIMIT_PARAM = "__limit";

    private final QueryTemplateProperties properties;
    private final NamedParameterJdbcTemplate jdbc;
    private final GovernanceProperties governanceProperties;
    private final Map<String, QueryTemplate> templates = new LinkedHashMap<>();

    public QueryTemplateRegistry(QueryTemplateProperties properties,
                                 NamedParameterJdbcTemplate jdbc,
                                 GovernanceProperties governanceProperties) {
        this.properties = properties;
        this.jdbc = jdbc;
        this.governanceProperties = governanceProperties;
    }

    @Override
    public void afterPropertiesSet() {
        for (QueryTemplate template : properties.getTemplates()) {
            validate(template);
            templates.put(template.getId(), template);
        }
        if (templates.isEmpty()) {
            throw new IllegalStateException("no query templates loaded - check query-templates.yml");
        }
        log.info("loaded {} query templates, all tenant-scoped", templates.size());
    }

    /**
     * Startup gate from spec section 7.1: a template without the tenant
     * predicate stops the application from booting.
     */
    private void validate(QueryTemplate template) {
        if (template.getId() == null || template.getId().isBlank()) {
            throw new TenantIsolationException("query template has no id");
        }
        String sql = template.getSql();
        if (sql == null || sql.isBlank()) {
            throw new TenantIsolationException("query template " + template.getId() + " has no sql");
        }
        if (!sql.contains(":" + TENANT_PARAM)) {
            throw new TenantIsolationException(
                    "query template '%s' does not bind :%s - refusing to start"
                            .formatted(template.getId(), TENANT_PARAM));
        }
    }

    public QueryTemplate get(String templateId) {
        QueryTemplate template = templates.get(templateId);
        if (template == null) {
            throw new IllegalArgumentException("unknown query template: " + templateId);
        }
        return template;
    }

    public List<String> templateIds() {
        return List.copyOf(templates.keySet());
    }

    /**
     * Runs a template for the current principal.
     *
     * <p>One extra row beyond the cap is requested so truncation can be
     * reported honestly instead of silently dropping data (spec section 9.4).
     */
    public QueryResult execute(String templateId, BizPrincipal principal, Map<String, Object> params) {
        QueryTemplate template = get(templateId);

        if (params.containsKey(TENANT_PARAM)) {
            // Defence in depth: the caller has no legitimate reason to set this.
            throw new TenantIsolationException(
                    "caller attempted to bind " + TENANT_PARAM + " for template " + templateId);
        }

        int cap = Math.min(template.getMaxRows(), governanceProperties.getMaxRows());

        MapSqlParameterSource source = new MapSqlParameterSource();
        params.forEach(source::addValue);
        source.addValue(TENANT_PARAM, principal.tenantId());
        source.addValue(LIMIT_PARAM, cap + 1);

        String cappedSql = "SELECT * FROM (%s) AS __capped LIMIT :%s".formatted(template.getSql(), LIMIT_PARAM);

        List<Map<String, Object>> rows = jdbc.queryForList(cappedSql, source);

        boolean truncated = rows.size() > cap;
        if (truncated) {
            rows = rows.subList(0, cap);
        }
        return new QueryResult(rows, truncated, cap);
    }

    /** Rows plus whether the row cap cut them short. */
    public record QueryResult(List<Map<String, Object>> rows, boolean truncated, int cap) {

        public int size() {
            return rows.size();
        }

        public boolean isEmpty() {
            return rows.isEmpty();
        }

        public Map<String, Object> first() {
            return rows.isEmpty() ? null : rows.get(0);
        }
    }
}
