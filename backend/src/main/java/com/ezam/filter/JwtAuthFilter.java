package com.ezam.filter;

import com.ezam.util.*;
import java.io.IOException;
import javax.servlet.*;
import javax.servlet.http.*;

/** Does not read the body. Trusted identity is a request attribute, never client JSON. */
public final class JwtAuthFilter implements Filter {
    public static final String TEACHER_ID = JwtAuthFilter.class.getName() + ".teacherId";
    private ServletContext context;
    @Override public void init(FilterConfig config) { context = config.getServletContext(); }
    @Override public void doFilter(ServletRequest req, ServletResponse res, FilterChain chain) throws IOException, ServletException {
        var request = (HttpServletRequest) req; var response = (HttpServletResponse) res;
        // Container-decoded mapping path also covers encoded aliases and path parameters.
        String path = request.getServletPath() + java.util.Objects.toString(request.getPathInfo(), "");
        try {
            if (RoutePolicy.requiresTeacher(request.getMethod(), path)) {
                var teacher = AuthApplication.get(context).auth().me(request.getHeader("Authorization"));
                request.setAttribute(TEACHER_ID, teacher.id_gv());
            }
            chain.doFilter(req, res);
        } catch (AuthException failure) { JsonUtil.error(response, failure); }
    }
    public static String teacherId(HttpServletRequest request) {
        Object value = request.getAttribute(TEACHER_ID);
        if (!(value instanceof String id) || id.isBlank()) throw AuthException.credentials();
        return id;
    }
}
