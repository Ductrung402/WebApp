package com.ezam.util;

/** ERR_01 safe public error; never retains provider bodies, URLs, keys or exception causes. */
public final class GeminiException extends RuntimeException {
    private final int status;
    public GeminiException(int status, String message) { super(message); this.status = status; }
    public int status() { return status; }
    public String errorCode() { return "ERR_01"; }
    public static GeminiException invalid() { return new GeminiException(400, "Gemini returned invalid JSON or question data."); }
    public static GeminiException timeout() { return new GeminiException(503, "Gemini generation exceeded the 15-second timeout."); }
}
