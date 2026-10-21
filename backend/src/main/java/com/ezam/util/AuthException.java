package com.ezam.util;

import java.util.List;

/** Only safe, public messages. Never attach JDBC/parser exceptions or submitted credentials. */
public final class AuthException extends RuntimeException {
    private final int status;
    private final Object detail;

    public AuthException(int status, String detail) {
        super(detail); this.status = status; this.detail = detail;
    }

    private AuthException(String field, String type, String message) {
        super("Invalid authentication request."); status = 422;
        detail = List.of(new Issue(type, List.of("body", field), message));
    }

    public record Issue(String type, List<String> loc, String msg) { }
    public int status() { return status; }
    public Object detail() { return detail; }
    public static AuthException invalid(String field, String type, String message) {
        return new AuthException(field, type, message);
    }
    public static AuthException credentials() {
        return new AuthException(401, "Invalid or expired credentials.");
    }
}
