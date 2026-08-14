package com.bizmcp.tools.dto;

/** Grouping dimension for {@code query_sales_summary}. */
public enum GroupByDimension {

    STORE("sales.summary_by_store"),
    PRODUCT("sales.summary_by_product"),
    DAY("sales.summary_by_day");

    private final String templateId;

    GroupByDimension(String templateId) {
        this.templateId = templateId;
    }

    /**
     * Maps the dimension to a pre-registered template id. The model picks from
     * a closed enum; it never influences the SQL that runs.
     */
    public String templateId() {
        return templateId;
    }
}
