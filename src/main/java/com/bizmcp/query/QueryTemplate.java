package com.bizmcp.query;

import com.bizmcp.governance.RiskTier;

/** One entry from {@code query-templates.yml}. */
public class QueryTemplate {

    private String id;
    private RiskTier riskTier = RiskTier.T1;
    private String sql;
    private int maxRows = 200;

    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id;
    }

    public RiskTier getRiskTier() {
        return riskTier;
    }

    public void setRiskTier(RiskTier riskTier) {
        this.riskTier = riskTier;
    }

    public String getSql() {
        return sql;
    }

    public void setSql(String sql) {
        this.sql = sql;
    }

    public int getMaxRows() {
        return maxRows;
    }

    public void setMaxRows(int maxRows) {
        this.maxRows = maxRows;
    }
}
