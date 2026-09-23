package com.ecobank.rccportal.dto;

import java.util.List;

public record GameEvaluationResultRequest(
        Integer attemptNumber,
        Integer score,
        Integer correctCount,
        Integer totalCount,
        List<AnswerDetail> answers
) {
    public record AnswerDetail(
            String questionText,
            List<String> options,
            Integer selectedIndex,
            Integer correctIndex,
            Boolean correct
    ) {}
}
