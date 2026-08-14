package com.bizmcp.masking;

/**
 * Field-level masking rules. Each strategy keeps enough of the value for a
 * human to recognise the record while removing enough to make it useless as
 * leaked personal data.
 */
public enum MaskStrategy {

    /** 王小明 -> 王○明, 陳美麗 -> 陳○麗, 王明 -> 王○ */
    NAME {
        @Override
        public String apply(String value) {
            int length = value.length();
            if (length <= 1) {
                return value;
            }
            if (length == 2) {
                return value.charAt(0) + "○";
            }
            return value.charAt(0) + "○".repeat(length - 2) + value.charAt(length - 1);
        }
    },

    /** 0912345678 -> 0912***678 */
    PHONE {
        @Override
        public String apply(String value) {
            String digits = value.replaceAll("\\D", "");
            if (digits.length() < 7) {
                return "***";
            }
            return digits.substring(0, 4) + "***" + digits.substring(digits.length() - 3);
        }
    },

    /** Keeps the administrative district, drops the street address. */
    ADDRESS {
        @Override
        public String apply(String value) {
            int keep = Math.min(6, value.length());
            return value.substring(0, keep) + "***";
        }
    },

    /** ming@example.com -> m***@example.com */
    EMAIL {
        @Override
        public String apply(String value) {
            int at = value.indexOf('@');
            if (at <= 0) {
                return "***";
            }
            return value.charAt(0) + "***" + value.substring(at);
        }
    },

    /** Removes the value entirely. */
    FULL {
        @Override
        public String apply(String value) {
            return "***";
        }
    };

    public abstract String apply(String value);

    public String applyNullSafe(String value) {
        return (value == null || value.isEmpty()) ? value : apply(value);
    }
}
