package com.ecobank.rccportal.dto;

import java.util.List;

public record QuizAnswerSubmission(
        Integer selectedOptionIndex,
        List<Integer> selectedIndexes
) {}
