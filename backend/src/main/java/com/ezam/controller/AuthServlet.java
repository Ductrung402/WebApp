package com.ezam.controller;

import com.ezam.dto.AuthDTO;
import com.ezam.service.AuthService;
import com.ezam.util.*;
import com.ezam.filter.JwtAuthFilter;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import java.io.IOException;
import java.util.Map;
import javax.servlet.annotation.WebServlet;
import javax.servlet.http.*;

/** Thin Auth HTTP boundary; identity and CORS use the shared ordered filters. */
@WebServlet(urlPatterns = "/api/auth/*", loadOnStartup = 1)
public final class AuthServlet extends HttpServlet {
    public static final String SERVICE_ATTRIBUTE = AuthService.class.getName();
    private static final Gson JSON = new GsonBuilder().serializeNulls().create();
    private AuthService service;

    @Override public void init() {
        Object configured = getServletContext().getAttribute(SERVICE_ATTRIBUTE);
        if (configured instanceof AuthService auth) { service = auth; return; }
        try { service = AuthApplication.get(getServletContext()).auth(); }
        catch (AuthException unavailable) { service = null; }
    }
    @Override protected void service(HttpServletRequest request, HttpServletResponse response) throws IOException {
        response.setCharacterEncoding("UTF-8"); response.setContentType("application/json");
        response.setHeader("Cache-Control", "no-store");
        try {
            String path = request.getPathInfo(), method = request.getMethod();
            boolean registration = "/register".equals(path), login = "/login".equals(path), me = "/me".equals(path);
            boolean update = "/profile".equals(path) || "/change-password".equals(path);
            if (!registration && !login && !me && !update) throw new AuthException(404, "Not Found");
            String expectedMethod = me ? "GET" : update ? "PUT" : "POST";
            if (!expectedMethod.equals(method)) {
                response.setHeader("Allow", expectedMethod); throw new AuthException(405, "Method Not Allowed");
            }
            if (service == null) throw new AuthException(503, "Authentication service unavailable.");
            super.service(request, response);
        } catch (AuthException error) {
            response.setStatus(error.status());
            if (error.status() == 401) response.setHeader("WWW-Authenticate", "Bearer");
            JSON.toJson(Map.of("detail", error.detail()), response.getWriter());
        } catch (RuntimeException | javax.servlet.ServletException unexpected) {
            response.setStatus(503); JSON.toJson(Map.of("detail", "Authentication service unavailable."), response.getWriter());
        }
    }
    @Override protected void doGet(HttpServletRequest request, HttpServletResponse response) throws IOException {
        JSON.toJson(service.me(request.getHeader("Authorization")), response.getWriter());
    }
    @Override protected void doPost(HttpServletRequest request, HttpServletResponse response) throws IOException {
        String body = JsonUtil.readBody(request);
        boolean registration = "/register".equals(request.getPathInfo());
        Object result = registration ? service.register(AuthDTO.register(body)) : service.login(AuthDTO.login(body));
        response.setStatus(registration ? 201 : 200); JSON.toJson(result, response.getWriter());
    }
    @Override protected void doPut(HttpServletRequest request, HttpServletResponse response) throws IOException {
        String body = JsonUtil.readBody(request), teacherId = JwtAuthFilter.teacherId(request);
        Object result = "/profile".equals(request.getPathInfo())
                ? service.updateTeacherProfile(teacherId, AuthDTO.profile(body))
                : service.changeTeacherPassword(teacherId, AuthDTO.changePassword(body));
        JsonUtil.send(response, 200, result);
    }
    @Override public void destroy() {
        service = null;
    }
}
