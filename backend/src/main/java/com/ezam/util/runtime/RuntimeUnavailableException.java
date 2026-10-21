package com.ezam.util.runtime;

/** Service/Router must map this safe failure to 503; never synthesize a deadline or grade. */
public final class RuntimeUnavailableException extends RuntimeException {
    private static final long serialVersionUID = 1L;
    public RuntimeUnavailableException() { super("Exam runtime is unavailable. Please retry or contact the teacher."); }
    public int getStatusCode() { return 503; }
}
