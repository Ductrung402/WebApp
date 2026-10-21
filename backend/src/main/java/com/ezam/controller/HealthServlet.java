package com.ezam.controller;

import com.ezam.util.*;
import java.io.IOException;
import java.util.Map;
import javax.servlet.annotation.WebServlet;
import javax.servlet.http.*;

@WebServlet("/api/health")
public final class HealthServlet extends HttpServlet {
    @Override protected void service(HttpServletRequest request, HttpServletResponse response) throws IOException {
        if (!"GET".equals(request.getMethod())) { JsonUtil.error(response, new AuthException(405, "Method Not Allowed")); return; }
        JsonUtil.send(response, 200, Map.of("status", "ok", "app", "Quiz AI & Exam System"));
    }
}
