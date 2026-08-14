package com.bizmcp.audit;

import com.bizmcp.masking.MaskStrategy;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Masks tool arguments before they are written to the audit trail
 * (spec section 7.2, "extra line of defence").
 *
 * <p>Two passes, because either alone leaves a hole: parameter names catch
 * fields that are declared personal data, and value patterns catch personal
 * data that arrives inside a free-text search term.
 */
@Component
public class ArgumentMasker {

    private static final Pattern PHONE_LIKE = Pattern.compile("\\b09\\d{8}\\b|\\b0\\d{1,2}-?\\d{6,8}\\b");
    private static final Pattern EMAIL_LIKE = Pattern.compile("\\b[\\w.+-]+@[\\w.-]+\\.[A-Za-z]{2,}\\b");

    private static final Pattern PHONE_PARAM = Pattern.compile("(?i).*(phone|mobile|tel).*");
    private static final Pattern EMAIL_PARAM = Pattern.compile("(?i).*(email|mail).*");
    private static final Pattern ADDRESS_PARAM = Pattern.compile("(?i).*(address|addr).*");
    // Free-text search terms are deliberately NOT name-masked wholesale. Doing so
    // turns "客戶 0912345678 的團" into "客○○…○團", which removes the leak but also
    // removes the audit trail's ability to answer "what was searched for". Those
    // terms fall through to value-pattern scrubbing instead, which masks the
    // personal data inside them and leaves the rest legible.
    private static final Pattern NAME_PARAM = Pattern.compile("(?i).*(customername|customer_name).*");

    public Map<String, String> maskArguments(Map<String, Object> arguments) {
        Map<String, String> masked = new LinkedHashMap<>();
        arguments.forEach((name, value) -> masked.put(name, maskValue(name, value)));
        return masked;
    }

    private String maskValue(String name, Object value) {
        if (value == null) {
            return null;
        }
        String text = String.valueOf(value);

        if (PHONE_PARAM.matcher(name).matches()) {
            return MaskStrategy.PHONE.applyNullSafe(text);
        }
        if (EMAIL_PARAM.matcher(name).matches()) {
            return MaskStrategy.EMAIL.applyNullSafe(text);
        }
        if (ADDRESS_PARAM.matcher(name).matches()) {
            return MaskStrategy.ADDRESS.applyNullSafe(text);
        }
        if (NAME_PARAM.matcher(name).matches()) {
            return MaskStrategy.NAME.applyNullSafe(text);
        }
        return scrubValuePatterns(text);
    }

    /** Catches personal data smuggled into an otherwise innocuous parameter. */
    private String scrubValuePatterns(String text) {
        String result = PHONE_LIKE.matcher(text)
                .replaceAll(match -> MaskStrategy.PHONE.applyNullSafe(match.group()));
        result = EMAIL_LIKE.matcher(result)
                .replaceAll(match -> MaskStrategy.EMAIL.applyNullSafe(match.group()));
        return result;
    }
}
