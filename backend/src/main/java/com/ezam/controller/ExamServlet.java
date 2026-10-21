package com.ezam.controller;

import com.ezam.dao.ExamDAO;
import com.ezam.dto.ExamDTO;
import com.ezam.filter.*;
import com.ezam.service.ExamService;
import com.ezam.service.ExamAIService;
import com.ezam.service.GeminiService;
import com.ezam.util.*;
import java.io.IOException;
import java.time.Clock;
import javax.servlet.annotation.WebServlet;
import javax.servlet.http.*;

@WebServlet(urlPatterns = "/api/exams/*", loadOnStartup = 2)
public final class ExamServlet extends HttpServlet {
    private ExamService service;
    private ExamAIService ai;
    @Override public void init() {
        try { service = new ExamService(AuthApplication.get(getServletContext()).transactions(), new ExamDAO(), Clock.systemDefaultZone()); }
        catch (RuntimeException unavailable) { service = null; }
        Object configured = getServletContext().getAttribute(ExamAIService.class.getName());
        try { ai = configured instanceof ExamAIService supplied ? supplied : new ExamAIService(GeminiService.fromEnvironment()); }
        catch (RuntimeException unavailable) { ai = null; }
    }
    @Override protected void service(HttpServletRequest request, HttpServletResponse response) throws IOException {
        try {
            if (!java.util.Set.of("GET", "POST", "PUT", "DELETE").contains(request.getMethod())) throw new AuthException(405, "Method Not Allowed");
            if (service == null) throw new AuthException(503, "Exam database unavailable.");
            super.service(request, response);
        } catch (AuthException failure) { JsonUtil.error(response, failure); }
        catch (GeminiException failure) { JsonUtil.error(response, new AuthException(failure.status(), failure.getMessage())); }
        catch (RuntimeException | javax.servlet.ServletException invalid) { JsonUtil.error(response, new AuthException(503, "Exam database unavailable.")); }
    }
    private static String id(HttpServletRequest request) {
        String path = request.getPathInfo();
        if (path == null || path.equals("/")) return null;
        if (!path.matches("/[^/]+")) throw new AuthException(404, "Not Found");
        return path.substring(1);
    }
    private static ExamDTO.Manual payload(HttpServletRequest request) throws IOException {
        Object parsed = request.getAttribute(ExamValidationFilter.PAYLOAD);
        return parsed instanceof ExamDTO.Manual manual ? manual : ExamDTO.manual(JsonUtil.readBody(request));
    }
    @Override protected void doGet(HttpServletRequest request, HttpServletResponse response) throws IOException {
        String id = id(request), teacher = JwtAuthFilter.teacherId(request);
        JsonUtil.send(response, 200, id == null ? service.list(teacher) : service.get(teacher, id));
    }
    @Override protected void doPost(HttpServletRequest request, HttpServletResponse response) throws IOException {
        if ("preview-ai".equals(id(request)) || "generate-ai".equals(id(request))) {
            if (ai == null) throw new GeminiException(503, "Invalid Gemini configuration.");
            Object parsed = request.getAttribute(ExamValidationFilter.PAYLOAD);
            var payload = parsed instanceof ExamDTO.AI value ? value : ExamDTO.ai(JsonUtil.readBody(request));
            boolean generate = "generate-ai".equals(id(request));
            JsonUtil.send(response, generate ? 201 : 200, generate ? ai.generate(service, JwtAuthFilter.teacherId(request), payload) : ai.preview(payload)); return;
        }
        if (id(request) != null) throw new AuthException(404, "Not Found");
        JsonUtil.send(response, 201, service.create(JwtAuthFilter.teacherId(request), payload(request)));
    }
    @Override public void destroy() { if (ai != null) ai.close(); }
    @Override protected void doPut(HttpServletRequest request, HttpServletResponse response) throws IOException {
        String id = id(request); if (id == null) throw new AuthException(405, "Method Not Allowed");
        JsonUtil.send(response, 200, service.update(JwtAuthFilter.teacherId(request), id, payload(request)));
    }
    @Override protected void doDelete(HttpServletRequest request, HttpServletResponse response) throws IOException {
        String id = id(request); if (id == null) throw new AuthException(405, "Method Not Allowed");
        JsonUtil.send(response, 200, service.delete(JwtAuthFilter.teacherId(request), id));
    }
}
