package com.ezam.service;

import com.ezam.dto.ExamDTO;

/** Preview has no transaction manager/DAO, so it cannot persist generated drafts. */
public final class ExamAIService implements AutoCloseable {
    private final GeminiService gemini;
    public ExamAIService(GeminiService gemini) { this.gemini = java.util.Objects.requireNonNull(gemini); }
    public ExamDTO.Manual preview(ExamDTO.AI payload) {
        return new ExamDTO.Manual(payload.tenDeThi(), payload.khoi(), "TEXT", payload.rawText(), gemini.generateQuizFromText(payload.rawText(), payload.numQuestions()));
    }
    /** AI succeeds before borrowing the write Connection; parent/questions still commit together. */
    public ExamDTO.Detail generate(ExamService exams, String teacherId, ExamDTO.AI payload) {
        return exams.create(teacherId, preview(payload));
    }
    @Override public void close() { gemini.close(); }
}
