package com.ezam.filter;

import java.util.Set;

/** Exact method/path public allowlist. Unknown operations cannot turn into public actions. */
public final class RoutePolicy {
    private static final Set<String> PUBLIC = Set.of("GET /api/health", "POST /api/auth/register", "POST /api/auth/login",
            "POST /api/rooms/join", "POST /api/submissions/submit", "GET /api/submissions/result");
    private RoutePolicy() { }
    public static boolean isPublic(String method, String path) { return PUBLIC.contains(method + " " + path); }
    public static boolean requiresTeacher(String method, String path) {
        if (isPublic(method, path)) return false;
        return path.equals("/api/auth/me") || path.equals("/api/auth/profile") || path.equals("/api/auth/change-password")
                || path.equals("/api/exams") || path.startsWith("/api/exams/") || path.equals("/api/rooms")
                || path.startsWith("/api/rooms/") || path.startsWith("/api/submissions/");
    }
}
