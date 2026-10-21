package com.ezam.controller;

import com.ezam.dto.SubmissionDTO;
import com.ezam.service.SubmissionService;
import com.ezam.util.*;
import com.google.gson.JsonObject;
import java.io.IOException;
import java.util.*;
import javax.servlet.annotation.WebServlet;
import javax.servlet.http.*;

@WebServlet(urlPatterns="/api/submissions/*", loadOnStartup=4)
public final class SubmissionServlet extends HttpServlet {
    @Override protected void service(HttpServletRequest request, HttpServletResponse response) throws IOException {
        try {
            if (!Set.of("GET", "POST").contains(request.getMethod())) throw new AuthException(405, "Method Not Allowed");
            super.service(request, response);
        } catch (AuthException error) { JsonUtil.error(response, error); }
        catch (RuntimeException | javax.servlet.ServletException error) { JsonUtil.error(response, new AuthException(503, "Submission service unavailable.")); }
    }
    private SubmissionService submissions() { return AuthApplication.get(getServletContext()).submissions(); }
    @Override protected void doPost(HttpServletRequest request, HttpServletResponse response) throws IOException {
        if (!"/submit".equals(request.getPathInfo())) throw new AuthException(404, "Not Found");
        var payload = SubmissionDTO.parse(JsonUtil.readBody(request));
        JsonUtil.send(response, 200, submissions().submit(payload, null, "manual").result());
    }
    @Override protected void doGet(HttpServletRequest request, HttpServletResponse response) throws IOException {
        if (!"/result".equals(request.getPathInfo())) throw new AuthException(404, "Not Found");
        var query = new JsonObject();
        for (var entry : request.getParameterMap().entrySet()) {
            if (entry.getValue().length != 1) throw new AuthException(422, "Duplicate query parameter.");
            query.addProperty(entry.getKey(), entry.getValue()[0]);
        }
        var object = DtoValidator.aliases(DtoValidator.object(query, Set.of("idHs", "idPhongThi", "id_hs", "id_phong_thi")), Map.of("id_hs", "idHs", "id_phong_thi", "idPhongThi"));
        String student = DtoValidator.nonEmpty(DtoValidator.string(object, "idHs", false), "idHs");
        String room = DtoValidator.nonEmpty(DtoValidator.string(object, "idPhongThi", false), "idPhongThi");
        JsonUtil.send(response, 200, submissions().result(student, room));
    }
}
