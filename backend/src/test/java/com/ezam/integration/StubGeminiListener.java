package com.ezam.integration;

import com.ezam.service.*;
import java.net.URI;
import javax.servlet.*;

/** Only injected into a private test deployment; Maven never packages test classes in ROOT.war. */
public final class StubGeminiListener implements ServletContextListener {
    @Override public void contextInitialized(ServletContextEvent event) {
        var service = new ExamAIService(new GeminiService("synthetic-gemini-key", "fixture-model", URI.create(System.getenv("TEST_GEMINI_ENDPOINT")), GeminiService.TIMEOUT));
        event.getServletContext().setAttribute(ExamAIService.class.getName(), service);
    }
}
