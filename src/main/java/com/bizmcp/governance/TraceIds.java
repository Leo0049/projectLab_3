package com.bizmcp.governance;

import java.security.SecureRandom;
import java.util.HexFormat;

/**
 * Short correlation ids. Internal errors surface only this id to the model
 * (spec section 10) so a failure stays traceable without leaking a stack trace.
 */
public final class TraceIds {

    private static final SecureRandom RANDOM = new SecureRandom();

    private TraceIds() {
    }

    public static String next() {
        byte[] bytes = new byte[4];
        RANDOM.nextBytes(bytes);
        return "trc_" + HexFormat.of().formatHex(bytes);
    }
}
