package com.ezam.filter;

import com.ezam.dto.ExamDTO;
import com.ezam.util.*;
import java.io.IOException;
import javax.servlet.*;
import javax.servlet.http.*;

/** Legacy manual-exam writes validate before the teacher dependency performs SQL. */
public final class ExamValidationFilter implements Filter {
    public static final String PAYLOAD = ExamValidationFilter.class.getName() + ".payload";
    @Override public void doFilter(ServletRequest req, ServletResponse res, FilterChain chain) throws IOException, ServletException {
        var request = (HttpServletRequest) req;
        String path = request.getServletPath() + java.util.Objects.toString(request.getPathInfo(), "");
        boolean collection = path.equals("/api/exams") || path.equals("/api/exams/");
        boolean validate = ("POST".equals(request.getMethod()) && collection)
                || ("PUT".equals(request.getMethod()) && path.matches("/api/exams/[^/]+"));
        boolean ai = "POST".equals(request.getMethod()) && (path.equals("/api/exams/preview-ai") || path.equals("/api/exams/generate-ai"));
        validate |= ai;
        if (!validate) { chain.doFilter(req, res); return; }
        try {
            var cached = new CachedJsonRequest(request);
            String body = JsonUtil.readBody(cached);
            cached.setAttribute(PAYLOAD, ai ? ExamDTO.ai(body) : ExamDTO.manual(body));
            chain.doFilter(cached, res);
        } catch (AuthException invalid) { JsonUtil.error((HttpServletResponse) res, invalid); }
    }
}
