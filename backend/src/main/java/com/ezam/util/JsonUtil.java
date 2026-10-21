package com.ezam.util;

import com.google.gson.*;
import java.io.IOException;
import java.util.Map;
import javax.servlet.http.*;

/** UTF-8 JSON boundary shared by API controllers/filters, without a global naming policy. */
public final class JsonUtil {
    private static final Gson JSON = new GsonBuilder().serializeNulls().create();
    private JsonUtil() { }
    public static String readBody(HttpServletRequest request) throws IOException {
        request.setCharacterEncoding("UTF-8");
        var reader = request.getReader(); var text = new StringBuilder(); char[] buffer = new char[4096]; int n;
        while ((n = reader.read(buffer)) != -1) {
            text.append(buffer, 0, n);
            if (text.length() > 1_048_576) throw new AuthException(413, "Request body too large.");
        }
        return text.toString();
    }
    public static void send(HttpServletResponse response, int status, Object value) throws IOException {
        response.setHeader("Cache-Control", "no-store");
        response.setCharacterEncoding("UTF-8"); response.setContentType("application/json"); response.setStatus(status);
        JSON.toJson(value, response.getWriter());
    }
    public static void error(HttpServletResponse response, AuthException error) throws IOException {
        if (error.status() == 401) response.setHeader("WWW-Authenticate", "Bearer");
        send(response, error.status(), Map.of("detail", error.detail()));
    }
}
