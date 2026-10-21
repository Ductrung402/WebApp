package com.ezam.filter;

import com.ezam.util.*;
import java.io.IOException;
import java.net.URI;
import java.util.*;
import javax.servlet.*;
import javax.servlet.http.*;

/** Registered before JWT in web.xml; rejected CORS origins receive no access grant. */
public final class CorsFilter implements Filter {
    public static final String DEFAULT_ORIGINS = "http://localhost:5173,http://localhost:3000,https://frontend-production-2db2d.up.railway.app";
    private Set<String> origins;
    @Override public void init(FilterConfig config) {
        try { origins = configuredOrigins(System.getenv().getOrDefault("CORS_ALLOWED_ORIGINS", DEFAULT_ORIGINS)); }
        catch (RuntimeException invalid) { origins = null; }
    }
    public static Set<String> configuredOrigins(String configuration) {
        Set<String> values = new HashSet<>();
        for (String value : configuration.split(",", -1)) {
            String text = value.strip(); URI uri = URI.create(text);
            if (!("https".equals(uri.getScheme()) || "http".equals(uri.getScheme())) || uri.getHost() == null
                    || uri.getRawUserInfo() != null || uri.getRawQuery() != null || uri.getRawFragment() != null
                    || (uri.getRawPath() != null && !uri.getRawPath().isEmpty()) || uri.getPort() > 65535) throw new IllegalArgumentException("Invalid CORS configuration.");
            values.add(text);
        }
        if (values.isEmpty()) throw new IllegalArgumentException("Invalid CORS configuration.");
        return Set.copyOf(values);
    }
    @Override public void doFilter(ServletRequest req, ServletResponse res, FilterChain chain) throws IOException, ServletException {
        var request = (HttpServletRequest) req; var response = (HttpServletResponse) res;
        if (origins == null) { JsonUtil.error(response, new AuthException(503, "CORS configuration unavailable.")); return; }
        String origin = request.getHeader("Origin");
        if (origin != null) {
            response.addHeader("Vary", "Origin");
            response.setHeader("Access-Control-Allow-Credentials", "true");
            if (origins.contains(origin)) response.setHeader("Access-Control-Allow-Origin", origin);
        }
        if ("OPTIONS".equals(request.getMethod())) {
            String method = request.getHeader("Access-Control-Request-Method");
            if (origin != null && !origins.contains(origin)) { JsonUtil.error(response, new AuthException(400, "Disallowed CORS origin")); return; }
            if (method != null && !Set.of("GET", "POST", "PUT", "DELETE", "PATCH", "HEAD", "OPTIONS").contains(method)) {
                JsonUtil.error(response, new AuthException(400, "Disallowed CORS method")); return;
            }
            response.setHeader("Access-Control-Allow-Methods", "DELETE, GET, HEAD, OPTIONS, PATCH, POST, PUT");
            String headers = request.getHeader("Access-Control-Request-Headers");
            if (headers != null) response.setHeader("Access-Control-Allow-Headers", headers);
            response.setHeader("Access-Control-Max-Age", "600");
            response.setStatus(200); return;
        }
        chain.doFilter(req, res);
    }
}
