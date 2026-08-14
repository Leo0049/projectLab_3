package com.bizmcp.governance;

/**
 * The outcome of an authorization vote. {@code reason} is written for the
 * model to read (spec section 10): it says what is missing and what the user
 * should do next, without leaking the full policy.
 */
public record Decision(boolean allowed, String reasonCode, String message) {

    public static Decision allow() {
        return new Decision(true, null, null);
    }

    public static Decision denyInsufficientRole(String toolName, Role[] allowed) {
        String roles = String.join(" 或 ", java.util.Arrays.stream(allowed).map(Enum::name).toList());
        return new Decision(false, "INSUFFICIENT_ROLE",
                "此操作需要 %s 角色，目前身分無權呼叫 %s。請告知使用者聯繫主管開通權限。"
                        .formatted(roles.isBlank() ? "更高權限" : roles, toolName));
    }

    public static Decision denyUnknownTool(String toolName) {
        return new Decision(false, "UNKNOWN_TOOL",
                "工具 %s 未註冊或未宣告風險級別，已拒絕。".formatted(toolName));
    }

    public boolean denied() {
        return !allowed;
    }
}
