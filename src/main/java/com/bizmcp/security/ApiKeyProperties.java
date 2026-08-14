package com.bizmcp.security;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Local-testing API keys (spec section 5.1).
 *
 * <p><b>This is a development shortcut, not the production path.</b> OAuth 2.0
 * is how a real client authenticates. Static keys exist so MCP Inspector can
 * switch between roles in one sitting without re-running an authorization code
 * flow per identity — which is what makes the permission part of the demo
 * possible at all. Disable with {@code bizmcp.api-key.enabled=false}.
 */
@ConfigurationProperties(prefix = "bizmcp.api-key")
public class ApiKeyProperties {

    private boolean enabled = true;

    private String header = "X-API-Key";

    /** key -> username in {@code app_users}. */
    private Map<String, String> keys = new LinkedHashMap<>();

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public String getHeader() {
        return header;
    }

    public void setHeader(String header) {
        this.header = header;
    }

    public Map<String, String> getKeys() {
        return keys;
    }

    public void setKeys(Map<String, String> keys) {
        this.keys = keys;
    }
}
