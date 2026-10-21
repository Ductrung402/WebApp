package com.ezam.service;

import com.ezam.dto.SubmissionDTO.*;
import com.ezam.model.runtime.RuntimeData.Grade;
import java.util.Objects;
import java.util.function.Consumer;

/** Transport-independent post-commit notification. Failed delivery must not undo a saved Grade. */
public final class SubmissionService {
    private final GradingService grading;
    private final Consumer<Submitted> notifications;
    public SubmissionService(GradingService grading, Consumer<Submitted> notifications) {
        this.grading = Objects.requireNonNull(grading); this.notifications = Objects.requireNonNull(notifications);
    }
    public Outcome submit(Request request, String runId, String reason) {
        Outcome outcome = grading.grade(request, runId, reason);
        try { notifications.accept(outcome.event()); }
        catch (RuntimeException deliveryFailure) { System.err.println("student_submitted delivery unavailable; committed score preserved."); }
        return outcome;
    }
    public Grade result(String student, String room) { return grading.result(student, room); }
}
