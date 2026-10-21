package com.ezam.util;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.regex.Pattern;
import org.mindrot.jbcrypt.BCrypt;

/**
 * bcrypt bridge for the legacy Passlib profile (UTF-8, no NUL, <= 72 bytes).
 * Request DTOs remain responsible for the API's minimum of eight characters.
 * Never trim, normalize Unicode, log the input, or rewrite a stored hash.
 */
public final class PasswordUtil {
    private static final int COST = 12;
    // jBCrypt 0.4 supports costs 4..30. Canonical bcrypt salt/checksum padding.
    private static final Pattern HASH_FORMAT = Pattern.compile(
            "\\$2[ab]\\$(0[4-9]|[12][0-9]|30)\\$"
                    + "[./A-Za-z0-9]{21}[.Oeu][./A-Za-z0-9]{30}[.CGKOSWaeimquy26]");

    private PasswordUtil() {
    }

    /** New hashes retain jBCrypt's native $2a$ revision, explicitly at cost 12. */
    public static String hashPassword(String password) {
        if (!isSupportedPassword(password)) {
            throw new IllegalArgumentException("Unsupported password input.");
        }
        return BCrypt.hashpw(password, BCrypt.gensalt(COST));
    }

    /**
     * False for invalid credentials or malformed/unsupported stored hashes.
     * Only a local copy of a valid $2b$ hash is adapted to jBCrypt's $2a$ parser.
     * Compatibility is bounded to the <=72-byte profile tested against Passlib;
     * this is not an adapter for arbitrary bcrypt revisions or byte encodings.
     */
    public static boolean verifyPassword(String password, String storedHash) {
        if (!isSupportedPassword(password) || storedHash == null
                || storedHash.length() != 60 || !HASH_FORMAT.matcher(storedHash).matches()) {
            return false;
        }
        String compatibleHash = storedHash.startsWith("$2b$")
                ? "$2a$" + storedHash.substring(4) : storedHash;
        try {
            // jBCrypt compares the computed hash without an early-exit byte loop.
            return BCrypt.checkpw(password, compatibleHash);
        } catch (IllegalArgumentException exception) {
            return false;
        }
    }

    private static boolean isSupportedPassword(String password) {
        if (password == null || password.length() > 72 || password.indexOf('\0') >= 0) {
            return false;
        }
        // Java would otherwise silently replace unpaired UTF-16 surrogates.
        for (int index = 0; index < password.length(); index++) {
            char value = password.charAt(index);
            if (Character.isHighSurrogate(value)) {
                if (index + 1 >= password.length()
                        || !Character.isLowSurrogate(password.charAt(index + 1))) {
                    return false;
                }
                index++;
            } else if (Character.isLowSurrogate(value)) {
                return false;
            }
        }
        byte[] encoded = password.getBytes(StandardCharsets.UTF_8);
        try {
            return encoded.length <= 72;
        } finally {
            Arrays.fill(encoded, (byte) 0);
        }
    }
}
