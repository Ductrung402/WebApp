package com.ezam.auth;

import com.ezam.dto.AuthDTO;
import com.ezam.filter.*;
import com.ezam.service.AuthService;
import com.ezam.util.*;
import com.google.gson.*;
import java.io.*;
import java.util.*;
import javax.servlet.*;
import javax.servlet.http.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class ValidationFilterTest {
    private record Fixture(HttpServletRequest req, HttpServletResponse res, FilterChain chain, StringWriter body) { }
    private static Fixture fixture(String method, String path) throws Exception {
        var req = mock(HttpServletRequest.class); var res = mock(HttpServletResponse.class); var chain = mock(FilterChain.class);
        var body = new StringWriter(); when(res.getWriter()).thenReturn(new PrintWriter(body));
        when(req.getMethod()).thenReturn(method); when(req.getServletPath()).thenReturn(path);
        return new Fixture(req, res, chain, body);
    }
    @ParameterizedTest @CsvSource({"POST,/api/auth/register", "POST,/api/auth/login", "GET,/api/health", "POST,/api/rooms/join", "POST,/api/submissions/submit", "GET,/api/submissions/result"})
    void publicOperationsNeverInvokeTeacherAuthAndDoNotReadTheBody(String method, String path) throws Exception {
        var f = fixture(method, path); var filter = new JwtAuthFilter(); var config = mock(FilterConfig.class);
        filter.init(config); filter.doFilter(f.req, f.res, f.chain);
        verify(f.chain).doFilter(f.req, f.res); verify(f.req, never()).getReader(); verify(config).getServletContext();
    }
    @ParameterizedTest @CsvSource({"GET,/api/auth/me", "PUT,/api/auth/profile", "PUT,/api/auth/change-password", "POST,/api/exams", "GET,/api/exams/id", "PUT,/api/exams/id", "DELETE,/api/exams/id", "POST,/api/rooms", "GET,/api/rooms/join", "GET,/api/submissions/submit", "POST,/api/submissions/result"})
    void protectedOperationsDoNotPassWithMissingJwt(String method, String path) throws Exception {
        var f = fixture(method, path); var filter = new JwtAuthFilter();
        var context = mock(ServletContext.class); var application = mock(AuthApplication.class); var auth = mock(AuthService.class); var config = mock(FilterConfig.class);
        when(config.getServletContext()).thenReturn(context); when(context.getAttribute(AuthApplication.class.getName())).thenReturn(application);
        when(application.auth()).thenReturn(auth); when(auth.me(null)).thenThrow(AuthException.credentials());
        filter.init(config); filter.doFilter(f.req, f.res, f.chain);
        verify(f.res).setStatus(401); verify(f.res).setHeader("WWW-Authenticate", "Bearer"); verifyNoInteractions(f.chain);
        assertEquals("Invalid or expired credentials.", JsonParser.parseString(f.body.toString()).getAsJsonObject().get("detail").getAsString());
        verify(f.req, never()).getReader();
    }
    @Test void trustedIdentityIsScopedToRequestAndBodyStaysReadable() throws Exception {
        var f = fixture("POST", "/api/exams"); var config = mock(FilterConfig.class); var context = mock(ServletContext.class);
        var app = mock(AuthApplication.class); var auth = mock(AuthService.class);
        when(config.getServletContext()).thenReturn(context); when(context.getAttribute(AuthApplication.class.getName())).thenReturn(app);
        when(app.auth()).thenReturn(auth); when(f.req.getHeader("Authorization")).thenReturn("Bearer synthetic");
        when(auth.me("Bearer synthetic")).thenReturn(new AuthDTO.TeacherResponse("trusted-id", "a@example.com", "An", null, null));
        when(f.req.getReader()).thenReturn(new BufferedReader(new StringReader("{\"tenDeThi\":\"Text\"}")));
        var filter = new JwtAuthFilter(); filter.init(config); filter.doFilter(f.req, f.res, f.chain);
        verify(f.req).setAttribute(JwtAuthFilter.TEACHER_ID, "trusted-id"); verify(f.chain).doFilter(f.req, f.res);
        assertEquals("{\"tenDeThi\":\"Text\"}", JsonUtil.readBody(f.req));
    }
    @ParameterizedTest @ValueSource(strings={"http://localhost:5173", "http://localhost:3000", "https://frontend-production-2db2d.up.railway.app"})
    void preflightPassesBeforeJwtAndAllowsConfiguredOrigin(String origin) throws Exception {
        var f = fixture("OPTIONS", "/api/auth/me"); when(f.req.getHeader("Origin")).thenReturn(origin);
        when(f.req.getHeader("Access-Control-Request-Method")).thenReturn("PUT");
        when(f.req.getHeader("Access-Control-Request-Headers")).thenReturn("authorization,content-type");
        var filter = new CorsFilter(); filter.init(mock(FilterConfig.class)); filter.doFilter(f.req, f.res, f.chain);
        verifyNoInteractions(f.chain); verify(f.res).setStatus(200); verify(f.res).setHeader("Access-Control-Allow-Origin", origin);
        verify(f.res).setHeader("Access-Control-Allow-Credentials", "true"); verify(f.req, never()).getReader();
    }
    @Test void rejectedOriginGetsNoCorsGrantOnActualOrPreflight() throws Exception {
        for (String method : List.of("GET", "OPTIONS")) {
            var f = fixture(method, "/api/auth/me"); when(f.req.getHeader("Origin")).thenReturn("https://attacker.example");
            var filter = new CorsFilter(); filter.init(mock(FilterConfig.class)); filter.doFilter(f.req, f.res, f.chain);
            verify(f.res, never()).setHeader(eq("Access-Control-Allow-Origin"), anyString());
            if (method.equals("OPTIONS")) { verifyNoInteractions(f.chain); verify(f.res).setStatus(400); }
            else verify(f.chain).doFilter(f.req, f.res);
        }
    }
    @ParameterizedTest @ValueSource(strings={"*", "https://example.com/", "https://user:secret@example.com", "null", "file:///tmp", "https://example.com?x=1", "https://example.com:99999", ""})
    void corsConfigurationRejectsWildcardCredentialsAndInvalidOrigins(String value) {
        assertThrows(IllegalArgumentException.class, () -> CorsFilter.configuredOrigins(value));
    }
    @ParameterizedTest @ValueSource(strings={"1.0", "1e0", "\"1\"", "true", "null", "2147483648", "0", "-1"})
    void strictNumbersAreNotCoerced(String value) {
        var object = DtoValidator.object("{\"minutes\":"+value+"}", Set.of("minutes"));
        assertEquals(422, assertThrows(AuthException.class, () -> DtoValidator.positiveInteger(object, "minutes")).status());
    }
    @Test void integerBoundsAndMissingExtraNullBlankAreValidated() {
        assertEquals(Integer.MAX_VALUE, DtoValidator.positiveInteger(DtoValidator.object("{\"minutes\":2147483647}", Set.of("minutes")), "minutes"));
        assertThrows(AuthException.class, () -> DtoValidator.object("{\"extra\":1}", Set.of("minutes")));
        assertThrows(AuthException.class, () -> DtoValidator.required(new JsonObject(), "minutes"));
        assertThrows(AuthException.class, () -> DtoValidator.nonEmpty(" \u00a0 ", "name"));
        assertThrows(AuthException.class, () -> DtoValidator.string(DtoValidator.object("{\"name\":null}", Set.of("name")), "name", false));
    }
    @Test void authSnakeCaseAndOtherAliasesDoNotUseGlobalNamingPolicy() {
        assertEquals("a@example.com", AuthDTO.login("{\"email\":\"A@EXAMPLE.COM\",\"mat_khau\":\"abcdefgh\"}").email());
        var object = DtoValidator.aliases(StrictJson.parse("{\"ten_de_thi\":\"Title\"}").getAsJsonObject(), Map.of("ten_de_thi", "tenDeThi"));
        assertEquals("Title", object.get("tenDeThi").getAsString());
        assertThrows(AuthException.class, () -> DtoValidator.aliases(StrictJson.parse("{\"ten_de_thi\":\"A\",\"tenDeThi\":\"B\"}").getAsJsonObject(), Map.of("ten_de_thi", "tenDeThi")));
    }
}
