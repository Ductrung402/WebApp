package com.ezam.baseline;

import javax.servlet.http.HttpServlet;
import javax.servlet.http.HttpServletRequest;
import java.net.http.HttpClient;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** Build tooling checks only: no authentication, network or application logic. */
class ToolchainTest {
    @Test
    void buildAndTestJvmIsJava17() {
        assertEquals(17, Runtime.version().feature());
        assertEquals("java.net.http", HttpClient.class.getModule().getName());
    }

    @Test
    void javaxServletApiIsAvailableForCompilationAndTests() throws NoSuchMethodException {
        assertEquals("javax.servlet.http", HttpServlet.class.getPackageName());
        assertNotNull(HttpServletRequest.class.getMethod("getReader"));
    }

    @Test
    void mockitoCanProvideServletTestDoublesOnJava17() {
        HttpServletRequest request = mock(HttpServletRequest.class);
        when(request.getMethod()).thenReturn("GET");
        assertEquals("GET", request.getMethod());
        verify(request).getMethod();
    }
}
