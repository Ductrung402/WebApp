package com.ezam.util;

import java.io.*;
import java.nio.charset.StandardCharsets;
import javax.servlet.*;
import javax.servlet.http.*;

/** Replay the validated JSON body for synchronous controllers; no body loss across filters. */
public final class CachedJsonRequest extends HttpServletRequestWrapper {
    private final byte[] body;
    public CachedJsonRequest(HttpServletRequest request) throws IOException {
        super(request); body = JsonUtil.readBody(request).getBytes(StandardCharsets.UTF_8);
    }
    @Override public String getCharacterEncoding() { return "UTF-8"; }
    @Override public int getContentLength() { return body.length; }
    @Override public long getContentLengthLong() { return body.length; }
    @Override public BufferedReader getReader() { return new BufferedReader(new InputStreamReader(getInputStream(), StandardCharsets.UTF_8)); }
    @Override public ServletInputStream getInputStream() {
        var bytes = new ByteArrayInputStream(body);
        return new ServletInputStream() {
            @Override public int read() { return bytes.read(); }
            @Override public boolean isFinished() { return bytes.available() == 0; }
            @Override public boolean isReady() { return true; }
            @Override public void setReadListener(ReadListener listener) { throw new UnsupportedOperationException("Synchronous JSON request."); }
        };
    }
}
